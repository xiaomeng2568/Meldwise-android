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
class ChatGptProvider(private val tokens:TokenManager,private val network:NetworkClient,
    private val base:String=Siwc.RESOURCE) : LlmProvider {
    override val id="openai-plan"
    override val displayName="ChatGPT Plan"
    override val capabilities=ProviderCapability(setOf(Capability.STREAMING,Capability.STORE_FALSE,Capability.USAGE,Capability.PLAN_USAGE))
    @Volatile private var models:List<LlmModel> = emptyList()
    override suspend fun validateConnection():ProviderStatus = when(val state=tokens.state.value) {
        is AuthState.Connected->if(state.planEnabled) ProviderStatus.READY else ProviderStatus.IDENTITY_ONLY
        is AuthState.ReauthRequired->ProviderStatus.REAUTH_REQUIRED
        AuthState.StorageUnavailable->ProviderStatus.UNAVAILABLE
        else->ProviderStatus.DISCONNECTED
    }
    override suspend fun listModels():List<LlmModel> {
        try {
            val token=tokens.accessToken()
            val result=network.request(Request.Builder().url("$base/models").header("Authorization","Bearer ${token.value}").get().build(),Operation.MODELS)
            if(result.status!=200) {
                val code=runCatching {Json.parseToJsonElement(result.body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content}.getOrNull()
                val error=ProviderErrors.map(result.status,code)
                if(error.requiresReauth) tokens.requireReauth(if(code=="insufficient_scope") AuthReason.SCOPE_CHANGED else AuthReason.TOKEN_REJECTED)
                throw ProviderFailure(error)
            }
            return parseModels(result.body).also { models=it }
        } catch(cancel:CancellationException) { throw cancel }
        catch(failure:ProviderFailure) { throw failure }
        catch(failure:AuthFailure) { throw ProviderFailure(authError(failure)) }
        catch(_:Exception) { throw ProviderFailure(LlmError(ErrorKind.NETWORK,isRetryable=true)) }
    }
    internal fun parseModels(body:String):List<LlmModel> {
        val catalog=Json.parseToJsonElement(body).jsonObject["models"]?.jsonArray ?: error("MODELS_FORMAT")
        require(catalog.size<=1024)
        val result=catalog.filter { it.jsonObject["visibility"]?.jsonPrimitive?.content=="list" }.map {
            val obj=it.jsonObject; val slug=requireNotNull(obj["slug"]).jsonPrimitive.content
            val name=requireNotNull(obj["display_name"]).jsonPrimitive.content
            require(slug.matches(Regex("[A-Za-z0-9._:/-]{1,128}")) && name.length in 1..128)
            // Only verified text/stream baseline; no capability guesses from the model name.
            LlmModel(slug,name,"provider-catalog",capabilities,ModelAvailability.UNKNOWN)
        }
        require(result.map { it.id }.distinct().size==result.size)
        return result
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
