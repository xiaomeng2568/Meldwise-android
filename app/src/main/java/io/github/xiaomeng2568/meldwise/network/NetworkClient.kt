package io.github.xiaomeng2568.meldwise.network

import io.github.xiaomeng2568.meldwise.security.utf8
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpResult(val status: Int, internal val body: String) {
    override fun toString() = "HttpResult(status=$status, body=[REDACTED])"
}
class NetworkFault(val outcome: Outcome, val requestMayHaveBeenSent: Boolean,
    val transport: TransportCategory = TransportCategory.NONE,
    val stage: CatalogStage = CatalogStage.REQUEST,
    val protocol: CatalogProtocol = CatalogProtocol.NONE) : Exception(outcome.name)
class RequestRisk {
    val sent = AtomicBoolean(false)
    val exchanges = java.util.concurrent.atomic.AtomicInteger(0)
}
class NetworkClient(val diagnostics: SafeDiagnostics = SafeDiagnostics(), private val allowLocalTestHttp: Boolean = false) {
    val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(10,TimeUnit.SECONDS).writeTimeout(20,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            // OkHttp can otherwise follow up a 503 Retry-After: 0 even with connection retries off.
            val risk=requireNotNull(chain.request().tag(RequestRisk::class.java))
            if(risk.exchanges.incrementAndGet()>1) throw IOException("AUTOMATIC_REPLAY_BLOCKED")
            val response=chain.proceed(chain.request())
            if(response.code==503) response.newBuilder().header("Retry-After","2147483647").build() else response
        }
        .eventListenerFactory { call -> object : EventListener() {
            override fun callStart(call: Call) {
                call.request().tag(ModelCatalogTrace::class.java)?.requestStarted()
                call.request().tag(InferenceTrace::class.java)?.requestStarted()
            }
            override fun dnsStart(call: Call, domainName: String) {
                call.request().tag(ModelCatalogTrace::class.java)?.at(CatalogStage.DNS)
            }
            override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                call.request().tag(ModelCatalogTrace::class.java)?.at(CatalogStage.CONNECT)
            }
            override fun secureConnectStart(call: Call) {
                call.request().tag(ModelCatalogTrace::class.java)?.at(CatalogStage.TLS)
            }
            override fun requestHeadersStart(call: Call) {
                call.request().tag(RequestRisk::class.java)?.sent?.set(true)
                call.request().tag(ModelCatalogTrace::class.java)?.at(CatalogStage.HTTP)
            }
        } }.build()
    fun newCall(request: Request, streaming: Boolean = false): Call {
        val url = request.url
        require(url.isHttps || (allowLocalTestHttp && url.host == "127.0.0.1" && url.scheme == "http")) { "HTTPS_REQUIRED" }
        val selected = if (streaming) client.newBuilder().callTimeout(120,TimeUnit.SECONDS).build() else client
        val builder=request.newBuilder().tag(RequestRisk::class.java,RequestRisk())
        request.body?.let { original ->
            builder.method(request.method,object:RequestBody() {
                override fun contentType()=original.contentType()
                override fun contentLength()=original.contentLength()
                override fun isOneShot()=true
                override fun writeTo(sink:okio.BufferedSink)=original.writeTo(sink)
            })
        }
        return selected.newCall(builder.build())
    }
    suspend fun request(request: Request, operation: Operation = Operation.METADATA,
        catalog: ModelCatalogTrace? = null): HttpResult = suspendCancellableCoroutine { cont ->
        val call = newCall(request.newBuilder().tag(ModelCatalogTrace::class.java,catalog).build())
        cont.invokeOnCancellation { call.cancel(); diagnostics.record(operation,Outcome.CANCELLED) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val category = transportCategory(e,call.isCanceled() && !cont.isActive)
                val outcome = when(category) { TransportCategory.TIMEOUT->Outcome.TIMEOUT
                    TransportCategory.CANCELLED->Outcome.CANCELLED;else->Outcome.NETWORK }
                val stage = when(category) { TransportCategory.NETWORK_DNS->CatalogStage.DNS
                    TransportCategory.NETWORK_CONNECT->CatalogStage.CONNECT;TransportCategory.NETWORK_TLS->CatalogStage.TLS
                    else->catalog?.stage() ?: CatalogStage.REQUEST }
                diagnostics.record(operation,outcome)
                if (cont.isActive) cont.resumeWithException(NetworkFault(outcome,
                    call.request().tag(RequestRisk::class.java)?.sent?.get() == true,category,stage))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        diagnostics.record(operation,Outcome.HTTP,response.code)
                        catalog?.responseReceived(response.code)
                        val input = (response.body ?: throw NetworkFault(Outcome.PROTOCOL,true,
                            stage=CatalogStage.BODY_READ,protocol=CatalogProtocol.BODY_MISSING)).byteStream()
                        val limit = BoundedBodyPolicy.limit(operation,response.code)
                        val bytes = ByteArrayOutputStream(); val block = ByteArray(4096)
                        while (true) {
                            val count = input.read(block)
                            if (count < 0) break
                            if (count > limit-bytes.size()) throw NetworkFault(Outcome.PROTOCOL,true,
                                stage=CatalogStage.BODY_READ,protocol=CatalogProtocol.BODY_LIMIT)
                            bytes.write(block,0,count)
                        }
                        catalog?.bodyRead()
                        val body = try { utf8(bytes.toByteArray()) } catch (_: Exception) {
                            throw NetworkFault(Outcome.PROTOCOL,true,stage=CatalogStage.BODY_READ,protocol=CatalogProtocol.BODY_ENCODING)
                        }
                        val result = HttpResult(response.code,body)
                        if (cont.isActive) cont.resume(result)
                    } catch (failure: Exception) {
                        val fault = when(failure) {
                            is NetworkFault -> failure
                            is IOException -> {
                                val category = transportCategory(failure,call.isCanceled() && !cont.isActive)
                                val outcome = when(category) { TransportCategory.TIMEOUT->Outcome.TIMEOUT
                                    TransportCategory.CANCELLED->Outcome.CANCELLED;else->Outcome.NETWORK }
                                NetworkFault(outcome,true,category,CatalogStage.BODY_READ)
                            }
                            else -> NetworkFault(Outcome.PROTOCOL,true,stage=CatalogStage.BODY_READ,protocol=CatalogProtocol.UNEXPECTED)
                        }
                        diagnostics.record(operation,fault.outcome)
                        if (cont.isActive) cont.resumeWithException(fault)
                    }
                }
            }
        })
    }
}
