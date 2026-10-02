package io.github.xiaomeng2568.meldwise.provider

import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.security.DeepSeekCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** API-key provider. No TokenManager, OAuth, refresh or reference to ChatGptProvider. */
class DeepSeekProvider(private val credentials:DeepSeekCredentials,private val network:NetworkClient,
    private val base:String="https://api.deepseek.com"):LlmProvider {
    override val id=ProviderIds.DEEPSEEK
    override val displayName="DeepSeek"
    override val capabilities=ProviderCapability(setOf(Capability.STREAMING,Capability.USAGE,Capability.API_KEY,Capability.REASONING))
    @Volatile private var models:List<LlmModel> = emptyList()
    @Volatile var catalogDiagnostic:ModelCatalogDiagnostic?=null; private set
    private val mutableInference=MutableStateFlow<InferenceDiagnostic?>(null)
    val inferenceDiagnostic:StateFlow<InferenceDiagnostic?> = mutableInference.asStateFlow()
    fun invalidateCatalog() { models=emptyList();catalogDiagnostic=null;mutableInference.value=null }
    override suspend fun validateConnection():ProviderStatus = when(credentials.state()) {
        io.github.xiaomeng2568.meldwise.security.ApiKeyState.CONFIGURED->ProviderStatus.READY
        io.github.xiaomeng2568.meldwise.security.ApiKeyState.MISSING->ProviderStatus.DISCONNECTED
        else->ProviderStatus.UNAVAILABLE
    }
    private fun key()=credentials.read() ?: throw ProviderFailure(LlmError(ErrorKind.AUTHENTICATION))
    override suspend fun listModels():List<LlmModel> = withContext(Dispatchers.IO) {
        val trace=ModelCatalogTrace()
        try {
            val key=key();trace.tokenAvailable()
            val request=Request.Builder().url("$base/models").header("Authorization","Bearer ${key.value}").get().build()
            trace.requestCreated()
            val result=network.request(request,Operation.MODELS,trace)
            if(result.status!=200) {
                val error=DeepSeekErrors.map(result.status,DeepSeekErrors.code(result.body),false)
                trace.finish(CatalogStage.HTTP,when(error.kind) {
                    ErrorKind.AUTHENTICATION->CatalogFailure.AUTHENTICATION
                    ErrorKind.AUTHORIZATION->CatalogFailure.AUTHORIZATION
                    else->CatalogFailure.HTTP_ERROR
                })
                throw ProviderFailure(error)
            }
            parseModels(result.body,trace).also { models=it;trace.finish() }
        } catch(cancel:CancellationException) { trace.finish(CatalogStage.CANCELLED,CatalogFailure.CANCELLED,TransportCategory.CANCELLED);throw cancel }
        catch(f:NetworkFault) {
            val error=catalogNetworkError(f)
            trace.finish(f.stage,when(error.kind) { ErrorKind.NETWORK->CatalogFailure.NETWORK_IO
                ErrorKind.TIMEOUT->CatalogFailure.TIMEOUT;else->CatalogFailure.PROTOCOL },f.transport,f.protocol)
            throw ProviderFailure(error)
        } catch(f:CatalogParseFailure) {
            trace.finish(if(f.protocol==CatalogProtocol.PROTOCOL_JSON) CatalogStage.JSON else CatalogStage.MODEL_CATALOG,
                CatalogFailure.PROTOCOL,protocol=f.protocol)
            throw ProviderFailure(LlmError(ErrorKind.PROTOCOL))
        } catch(f:ProviderFailure) {
            if(!trace.snapshot().httpResponseReceived) trace.finish(CatalogStage.CREDENTIAL,
                if(f.error.kind==ErrorKind.AUTHENTICATION) CatalogFailure.AUTHENTICATION else CatalogFailure.PROTOCOL)
            throw f
        } catch(_:Exception) {
            trace.finish(CatalogStage.REQUEST,CatalogFailure.PROTOCOL,protocol=CatalogProtocol.UNEXPECTED)
            throw ProviderFailure(LlmError(ErrorKind.PROTOCOL))
        } finally { catalogDiagnostic=trace.snapshot() }
    }
    internal fun parseModels(body:String,trace:ModelCatalogTrace=ModelCatalogTrace()):List<LlmModel> {
        val root=try { Json.parseToJsonElement(body) } catch(_:Exception) { throw CatalogParseFailure(CatalogProtocol.PROTOCOL_JSON) }
        trace.jsonParsed()
        try {
            val obj=root as? JsonObject ?: error("FORMAT")
            require(obj["object"]?.jsonPrimitive?.content=="list")
            val data=obj["data"] as? JsonArray ?: error("FORMAT")
            trace.modelsFound();require(data.size<=1024)
            fun string(o:JsonObject,k:String)= (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val parsed=data.map { item ->
                val o=item as? JsonObject ?: error("FORMAT")
                require(string(o,"object")=="model")
                val id=requireNotNull(string(o,"id"));require(id.matches(Regex("[A-Za-z0-9._:/-]{1,128}")))
                val name=if(!o.containsKey("name")) id else requireNotNull(string(o,"name"))
                require(name.isNotBlank() && name.length<=128 && name.none { it.isISOControl() })
                fun modalities(k:String):List<String>? {
                    if(!o.containsKey(k)) return null
                    val values=o[k] as? JsonArray ?: error("FORMAT");require(values.size<=16)
                    return values.map { v -> requireNotNull((v as? JsonPrimitive)?.takeIf { it.isString }?.content).also { require(it.length in 1..32) } }
                }
                val input=modalities("input_modalities");val output=modalities("output_modalities")
                val text=(input==null || "text" in input) && (output==null || "text" in output)
                LlmModel(id,name,"provider-catalog",capabilities,if(text) ModelAvailability.AVAILABLE else ModelAvailability.UNSUPPORTED_FOR_AUTH_PATH)
            }
            require(parsed.map { it.id }.distinct().size==parsed.size)
            return parsed.filter { it.availability==ModelAvailability.AVAILABLE }.also { trace.visible(it.size);trace.parsed(it.size) }
        } catch(_:Exception) { throw CatalogParseFailure(CatalogProtocol.PROTOCOL_MODEL_CATALOG) }
    }
    internal fun payload(r:LlmRequest):String {
        require(r.stream && !r.store && r.messages.isNotEmpty() && r.messages.size<=200)
        require(r.temperature==null && r.topP==null && r.maxOutputTokens==null && r.responseFormat==null)
        require(r.instructions==null) // Sprint 2 is text Single Chat; no unsupported role translation.
        require(r.messages.all { it.role in setOf(MessageRole.USER,MessageRole.ASSISTANT) })
        require(r.messages.sumOf { it.text.length }<=4_194_304)
        return buildJsonObject {
            put("model",r.model);put("stream",true)
            ReasoningPolicy.effort(id,r.reasoning)?.let {value -> putJsonObject("reasoning") {put("effort",value)} }
            putJsonArray("input") { r.messages.forEach { m -> add(buildJsonObject { put("role",m.role.name.lowercase());put("content",m.text) }) } }
        }.toString()
    }
    override fun streamResponse(request:LlmRequest):Flow<LlmEvent> = callbackFlow {
        val call=java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>()
        val trace=InferenceTrace();mutableInference.value=null
        val reader=ResponsesReader(trace,DeepSeekErrors::map,explicitIncomplete=true,codeCategory=DeepSeekErrors::category,
            reasoningReader=ReasoningReader(if(request.reasoning in setOf(ReasoningPreference.Low,ReasoningPreference.High,ReasoningPreference.Max))
                ReasoningReadMode.DeepSeekVisible else ReasoningReadMode.None))
        var stage=InferenceStage.CREDENTIAL
        val job=launch(Dispatchers.IO) {
            try {
                val key=key();trace.update { it.copy(credentialAvailable=true) }
                if(models.none { it.id==request.model }) throw ProviderFailure(LlmError(ErrorKind.MODEL_UNAVAILABLE))
                stage=InferenceStage.REQUEST_BUILD
                val body=try { payload(request) } catch(_:Exception) { throw ProviderFailure(LlmError(ErrorKind.UNSUPPORTED_CAPABILITY)) }
                val created=network.newCall(Request.Builder().url("$base/responses")
                    .header("Authorization","Bearer ${key.value}").header("Accept","text/event-stream")
                    .tag(InferenceTrace::class.java,trace).post(body.toRequestBody("application/json".toMediaType())).build(),streaming=true)
                trace.update { it.copy(requestBuilt=true) };call.set(created);currentCoroutineContext().ensureActive()
                stage=InferenceStage.HTTP
                created.execute().use { response ->
                    trace.update { it.copy(httpReceived=true,httpStatus=response.code) }
                    network.diagnostics.record(Operation.RESPONSE,Outcome.HTTP,response.code)
                    if(response.code!=200) {
                        val code=try { DeepSeekErrors.code(response.peekBody(8192).string()) } catch(_:java.io.IOException) { null }
                        throw ProviderFailure(DeepSeekErrors.map(response.code,code,false))
                    }
                    stage=InferenceStage.STREAM_OPEN
                    if(request.observeHttp) send(LlmEvent.HttpReady)
                    val parser=SseParser(requireNotNull(response.body).source())
                    trace.update { it.copy(streamBodyOpened=true,sseParserStarted=true) }
                    while(!reader.terminal) {
                        currentCoroutineContext().ensureActive();stage=InferenceStage.SSE_FRAME
                        val frame=try { parser.next() } catch(f:java.io.IOException) { throw f }
                            catch(_:Exception) { throw ResponseProtocolFailure(InferenceStage.SSE_FRAME,InferenceProtocol.SSE_INVALID) }
                        if(frame==null) break
                        reader.consume(frame).forEach { send(it) }
                    }
                    if(!reader.terminal) {
                        trace.finish(InferenceStage.EOF_BEFORE_TERMINAL,result=ErrorKind.STREAM_INTERRUPTED)
                        send(LlmEvent.Incomplete(LlmError(ErrorKind.STREAM_INTERRUPTED,mayHaveProducedOutput=reader.produced)))
                    }
                }
            } catch(cancel:CancellationException) {
                trace.finish(InferenceStage.CANCELLED,result=ErrorKind.CANCELLED,transport=TransportCategory.CANCELLED);throw cancel
            } catch(f:Exception) {
                if(isActive) {
                    val error=when(f) {
                        is ProviderFailure->f.error
                        is java.io.InterruptedIOException->LlmError(ErrorKind.TIMEOUT,mayHaveProducedOutput=reader.produced)
                        is java.io.IOException->LlmError(ErrorKind.NETWORK,mayHaveProducedOutput=reader.produced)
                        else->LlmError(ErrorKind.PROTOCOL,mayHaveProducedOutput=reader.produced)
                    }
                    if(f is ResponseProtocolFailure) trace.finish(f.stage,f.protocol,error.kind)
                    else trace.finish(stage,result=error.kind,transport=if(f is java.io.IOException) transportCategory(f) else TransportCategory.NONE)
                    send(if(reader.produced) LlmEvent.Incomplete(error) else LlmEvent.Failed(error))
                }
            } finally { mutableInference.value=trace.snapshot();close() }
        }
        awaitClose {
            trace.finish(InferenceStage.CANCELLED,result=ErrorKind.CANCELLED,transport=TransportCategory.CANCELLED)
            mutableInference.value=trace.snapshot();call.get()?.cancel();job.cancel()
        }
    }
}

/** Closed adapter. Unknown body codes never become arbitrary diagnostic strings. */
object DeepSeekErrors {
    internal fun category(code:String?):ProviderCode = when(code) {
        null->ProviderCode.NONE
        "invalid_api_key","authentication_error"->ProviderCode.AUTHENTICATION
        "insufficient_quota"->ProviderCode.BILLING;"rate_limit_exceeded"->ProviderCode.RATE_LIMIT
        "model_not_found"->ProviderCode.MODEL_UNAVAILABLE;"context_length_exceeded"->ProviderCode.CONTEXT_OVERFLOW
        "server_error"->ProviderCode.SERVER;else->ProviderCode.UNKNOWN
    }
    internal fun code(body:String):String? = try {
        val raw=Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
        raw?.takeIf { it in setOf("invalid_api_key","authentication_error","insufficient_quota","rate_limit_exceeded",
            "model_not_found","context_length_exceeded","server_error") }
    } catch(_:Exception) { null }
    fun map(status:Int,code:String?,produced:Boolean):LlmError {
        val kind=when(status) {
            401->ErrorKind.AUTHENTICATION;403->ErrorKind.AUTHORIZATION;402->ErrorKind.BILLING
            429->ErrorKind.RATE_LIMIT;404->ErrorKind.MODEL_UNAVAILABLE
            in 500..599->ErrorKind.SERVER
            else->when(code) {
                "invalid_api_key","authentication_error"->ErrorKind.AUTHENTICATION
                "insufficient_quota"->ErrorKind.BILLING;"rate_limit_exceeded"->ErrorKind.RATE_LIMIT
                "model_not_found"->ErrorKind.MODEL_UNAVAILABLE;"context_length_exceeded"->ErrorKind.CONTEXT_OVERFLOW
                "server_error"->ErrorKind.SERVER
                else->if(status==200) ErrorKind.PROTOCOL else ErrorKind.UNKNOWN
            }
        }
        return LlmError(kind,mayHaveProducedOutput=produced)
    }
}
