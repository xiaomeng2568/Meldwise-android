package io.github.xiaomeng2568.meldwise.network

import io.github.xiaomeng2568.meldwise.security.utf8
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpResult(val status: Int, internal val body: String) {
    override fun toString() = "HttpResult(status=$status, body=[REDACTED])"
}
class NetworkFault(val outcome: Outcome, val requestMayHaveBeenSent: Boolean) : Exception(outcome.name)
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
            override fun requestHeadersStart(call: Call) { call.request().tag(RequestRisk::class.java)?.sent?.set(true) }
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
    suspend fun request(request: Request, operation: Operation = Operation.METADATA): HttpResult = suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel(); diagnostics.record(operation,Outcome.CANCELLED) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val outcome = if (e is InterruptedIOException) Outcome.TIMEOUT else Outcome.NETWORK
                diagnostics.record(operation,outcome)
                if (cont.isActive) cont.resumeWithException(NetworkFault(outcome,call.request().tag(RequestRisk::class.java)?.sent?.get() == true))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        diagnostics.record(operation,Outcome.HTTP,response.code)
                        val input = requireNotNull(response.body).byteStream()
                        val bytes = ByteArrayOutputStream(); val block = ByteArray(4096)
                        while (true) {
                            val count = input.read(block)
                            if (count < 0) break
                            if (bytes.size()+count > 262144) throw NetworkFault(Outcome.PROTOCOL,true)
                            bytes.write(block,0,count)
                        }
                        val result = HttpResult(response.code,utf8(bytes.toByteArray()))
                        if (cont.isActive) cont.resume(result)
                    } catch (_: Exception) {
                        diagnostics.record(operation,Outcome.PROTOCOL)
                        if (cont.isActive) cont.resumeWithException(NetworkFault(Outcome.PROTOCOL,true))
                    }
                }
            }
        })
    }
}
