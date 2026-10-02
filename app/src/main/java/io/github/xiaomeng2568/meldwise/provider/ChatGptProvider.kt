package io.github.xiaomeng2568.meldwise.provider

import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class ProviderFailure(val error:LlmError):Exception(error.kind.name)
internal class CatalogParseFailure(val protocol:CatalogProtocol):Exception(protocol.name)
internal fun catalogNetworkError(failure:NetworkFault):LlmError {
    val kind=when(failure.outcome) {
        Outcome.NETWORK->ErrorKind.NETWORK;Outcome.TIMEOUT->ErrorKind.TIMEOUT
        Outcome.CANCELLED->ErrorKind.CANCELLED;else->ErrorKind.PROTOCOL
    }
    return LlmError(kind,isRetryable=kind in setOf(ErrorKind.NETWORK,ErrorKind.TIMEOUT))
}
class ChatGptProvider(private val tokens:TokenManager,private val network:NetworkClient,
    private val base:String=Siwc.RESOURCE) : LlmProvider {
    override val id="openai-plan"
    override val displayName="ChatGPT Plan"
    override val capabilities=ProviderCapability(setOf(Capability.STREAMING,Capability.STORE_FALSE,Capability.USAGE,Capability.PLAN_USAGE))
    @Volatile private var models:List<LlmModel> = emptyList()
    @Volatile var catalogDiagnostic:ModelCatalogDiagnostic? = null
        private set
    override suspend fun validateConnection():ProviderStatus = when(val state=tokens.state.value) {
        is AuthState.Connected->if(state.planEnabled) ProviderStatus.READY else ProviderStatus.IDENTITY_ONLY
        is AuthState.ReauthRequired->ProviderStatus.REAUTH_REQUIRED
        AuthState.StorageUnavailable->ProviderStatus.UNAVAILABLE
        else->ProviderStatus.DISCONNECTED
    }
    override suspend fun listModels():List<LlmModel> {
        val trace=ModelCatalogTrace()
        var stage=CatalogStage.CREDENTIAL
        try {
            val token=tokens.accessToken()
            trace.tokenAvailable()
            stage=CatalogStage.REQUEST
            val request=Request.Builder().url("$base/models").header("Authorization","Bearer ${token.value}").get().build()
            trace.requestCreated()
            val result=network.request(request,Operation.MODELS,trace)
            stage=CatalogStage.HTTP
            if(result.status!=200) {
                val code=runCatching {Json.parseToJsonElement(result.body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content}.getOrNull()
                val error=ProviderErrors.map(result.status,code)
                trace.finish(CatalogStage.HTTP,when(error.kind) {
                    ErrorKind.AUTHENTICATION->CatalogFailure.AUTHENTICATION
                    ErrorKind.AUTHORIZATION->CatalogFailure.AUTHORIZATION
                    else->CatalogFailure.HTTP_ERROR
                })
                if(error.requiresReauth) tokens.requireReauth(if(code=="insufficient_scope") AuthReason.SCOPE_CHANGED else AuthReason.TOKEN_REJECTED)
                throw ProviderFailure(error)
            }
            stage=CatalogStage.JSON
            return parseModels(result.body,trace).also { models=it;trace.finish() }
        } catch(cancel:CancellationException) {
            trace.finish(CatalogStage.CANCELLED,CatalogFailure.CANCELLED,TransportCategory.CANCELLED)
            throw cancel
        }
        catch(failure:ProviderFailure) { throw failure }
        catch(failure:AuthFailure) {
            val kind=when(failure.reason) {
                AuthReason.NETWORK->ErrorKind.NETWORK;AuthReason.TIMEOUT->ErrorKind.TIMEOUT
                AuthReason.CANCELLED->ErrorKind.CANCELLED;AuthReason.PROTOCOL->ErrorKind.PROTOCOL
                AuthReason.STORAGE_UNAVAILABLE->ErrorKind.STORAGE;AuthReason.SCOPE_CHANGED->ErrorKind.AUTHORIZATION
                else->ErrorKind.AUTHENTICATION
            }
            val category=when(kind) {
                ErrorKind.NETWORK->CatalogFailure.NETWORK_IO;ErrorKind.TIMEOUT->CatalogFailure.TIMEOUT
                ErrorKind.CANCELLED->CatalogFailure.CANCELLED;ErrorKind.AUTHENTICATION->CatalogFailure.AUTHENTICATION
                ErrorKind.AUTHORIZATION->CatalogFailure.AUTHORIZATION;else->CatalogFailure.PROTOCOL
            }
            trace.finish(CatalogStage.CREDENTIAL,category)
            throw ProviderFailure(LlmError(kind,requiresReauth=authError(failure).requiresReauth && kind==ErrorKind.AUTHENTICATION))
        }
        catch(failure:NetworkFault) {
            val error=catalogNetworkError(failure)
            val kind=error.kind
            val category=when(kind) {
                ErrorKind.NETWORK->when(failure.transport) {
                    TransportCategory.NETWORK_DNS->CatalogFailure.NETWORK_DNS
                    TransportCategory.NETWORK_CONNECT->CatalogFailure.NETWORK_CONNECT
                    TransportCategory.NETWORK_TLS->CatalogFailure.NETWORK_TLS
                    else->CatalogFailure.NETWORK_IO
                }
                ErrorKind.TIMEOUT->CatalogFailure.TIMEOUT;ErrorKind.CANCELLED->CatalogFailure.CANCELLED
                else->CatalogFailure.PROTOCOL
            }
            trace.finish(failure.stage,category,failure.transport,failure.protocol)
            throw ProviderFailure(error)
        }
        catch(failure:CatalogParseFailure) {
            val json=failure.protocol==CatalogProtocol.PROTOCOL_JSON
            trace.finish(if(json) CatalogStage.JSON else CatalogStage.MODEL_CATALOG,
                if(json) CatalogFailure.PROTOCOL_JSON else CatalogFailure.PROTOCOL_MODEL_CATALOG,protocol=failure.protocol)
            network.diagnostics.record(Operation.MODELS,Outcome.PROTOCOL)
            throw ProviderFailure(LlmError(ErrorKind.PROTOCOL))
        }
        catch(_:Exception) {
            trace.finish(stage,CatalogFailure.PROTOCOL,protocol=CatalogProtocol.UNEXPECTED)
            throw ProviderFailure(LlmError(ErrorKind.PROTOCOL))
        } finally { catalogDiagnostic=trace.snapshot() }
    }
    internal fun parseModels(body:String,trace:ModelCatalogTrace = ModelCatalogTrace()):List<LlmModel> {
        val root=try { Json.parseToJsonElement(body) } catch(_:Exception) {
            throw CatalogParseFailure(CatalogProtocol.PROTOCOL_JSON)
        }
        trace.jsonParsed()
        try {
            val catalog=(root as? JsonObject)?.get("models") as? JsonArray ?: error("MODELS_FORMAT")
            trace.modelsFound()
            require(catalog.size<=1024)
            val visible=catalog.filter {
                val obj=it as? JsonObject ?: error("MODEL_OBJECT_REQUIRED")
                val visibility=obj["visibility"] as? JsonPrimitive
                visibility?.isString==true && visibility.content=="list"
            }
            trace.visible(visible.size)
            val result=visible.map {
                val obj=it.jsonObject
                val slug=requireNotNull(obj["slug"] as? JsonPrimitive).also { require(it.isString) }.content
                val name=requireNotNull(obj["display_name"] as? JsonPrimitive).also { require(it.isString) }.content
                require(slug.matches(Regex("[A-Za-z0-9._:/-]{1,128}")) && name.length in 1..128)
                // Only verified text/stream baseline; no capability guesses from the model name.
                LlmModel(slug,name,"provider-catalog",capabilities,ModelAvailability.UNKNOWN)
            }
            require(result.map { it.id }.distinct().size==result.size)
            trace.parsed(result.size)
            return result
        } catch(_:Exception) { throw CatalogParseFailure(CatalogProtocol.PROTOCOL_MODEL_CATALOG) }
    }
    internal fun payload(request:LlmRequest):String {
        require(request.stream && !request.store && request.messages.isNotEmpty() && request.messages.size<=200)
        require(request.temperature==null && request.topP==null && request.maxOutputTokens==null && request.responseFormat==null)
        require(request.messages.sumOf { it.text.length }<=4_194_304)
        return buildJsonObject {
            put("model",request.model); put("stream",true); put("store",false)
            request.instructions?.let { put("instructions",it) }
            putJsonArray("input") { request.messages.forEach { message -> add(buildJsonObject {
                put("role",message.role.name.lowercase()); put("content",message.text)
            }) } }
        }.toString()
    }
    override fun streamResponse(request:LlmRequest):Flow<LlmEvent> = callbackFlow {
        val call=java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>()
        val reader=ResponsesReader()
        val job=launch(Dispatchers.IO) {
            try {
                if(models.none { it.id==request.model }) throw ProviderFailure(LlmError(ErrorKind.MODEL_UNAVAILABLE))
                val body=try { payload(request) } catch(_:Exception) { throw ProviderFailure(LlmError(ErrorKind.UNSUPPORTED_CAPABILITY)) }
                val token=tokens.accessToken()
                val created=network.newCall(Request.Builder().url("$base/responses")
                    .header("Authorization","Bearer ${token.value}").header("Accept","text/event-stream")
                    .post(body.toRequestBody("application/json".toMediaType())).build(),streaming=true)
                call.set(created); currentCoroutineContext().ensureActive()
                created.execute().use { response ->
                    network.diagnostics.record(Operation.RESPONSE,Outcome.HTTP,response.code)
                    if(response.code!=200) {
                        val peek=response.peekBody(8192).string()
                        val code=runCatching { Json.parseToJsonElement(peek).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content }.getOrNull()
                        val error=ProviderErrors.map(response.code,code)
                        if(error.requiresReauth) tokens.requireReauth(if(code=="insufficient_scope") AuthReason.SCOPE_CHANGED else AuthReason.TOKEN_REJECTED)
                        throw ProviderFailure(error)
                    }
                    val sse=SseParser(requireNotNull(response.body).source())
                    while(!reader.terminal) {
                        currentCoroutineContext().ensureActive()
                        val frame=sse.next() ?: break
                        reader.consume(frame).forEach { send(it) }
                    }
                    if(!reader.terminal) send(LlmEvent.Incomplete(LlmError(ErrorKind.STREAM_INTERRUPTED,isRetryable=true,mayHaveProducedOutput=reader.produced)))
                }
            } catch(cancel:CancellationException) { throw cancel }
            catch(failure:Exception) {
                if(isActive) {
                    val error=when(failure) {
                        is ProviderFailure->failure.error
                        is AuthFailure->authError(failure)
                        is java.io.InterruptedIOException->LlmError(ErrorKind.TIMEOUT,isRetryable=true,mayHaveProducedOutput=reader.produced)
                        is java.io.IOException->LlmError(ErrorKind.NETWORK,isRetryable=true,mayHaveProducedOutput=reader.produced)
                        else->LlmError(ErrorKind.PROTOCOL,mayHaveProducedOutput=reader.produced)
                    }
                    network.diagnostics.record(Operation.RESPONSE,Outcome.PROTOCOL)
                    send(if(reader.produced) LlmEvent.Incomplete(error) else LlmEvent.Failed(error))
                }
            } finally { close() }
        }
        awaitClose { call.get()?.cancel(); job.cancel() }
    }
    private fun authError(failure:AuthFailure)=LlmError(if(failure.reason==AuthReason.SCOPE_CHANGED) ErrorKind.AUTHORIZATION else ErrorKind.AUTHENTICATION,
        requiresReauth=failure.reason!=AuthReason.SCOPE_CHANGED)
}
