package io.github.xiaomeng2568.meldwise.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

enum class CatalogStage { NONE, CREDENTIAL, REQUEST, DNS, CONNECT, TLS, HTTP, BODY_READ, JSON, MODEL_CATALOG, CANCELLED }
enum class TransportCategory { NONE, NETWORK_DNS, NETWORK_CONNECT, NETWORK_TLS, NETWORK_IO, TIMEOUT, CANCELLED }
enum class CatalogProtocol { NONE, PROTOCOL_JSON, PROTOCOL_MODEL_CATALOG, BODY_LIMIT, BODY_ENCODING, BODY_MISSING, UNEXPECTED }
enum class CatalogFailure { NONE, NETWORK_DNS, NETWORK_CONNECT, NETWORK_TLS, NETWORK_IO, TIMEOUT, CANCELLED,
    HTTP_ERROR, AUTHENTICATION, AUTHORIZATION, PROTOCOL_JSON, PROTOCOL_MODEL_CATALOG, PROTOCOL }

/** Only fixed categories, booleans and bounded counts. No string/body/header/identity fields. */
@ConsistentCopyVisibility
data class ModelCatalogDiagnostic internal constructor(
    val tokenAvailable: Boolean = false,
    val requestCreated: Boolean = false,
    val requestStarted: Boolean = false,
    val httpResponseReceived: Boolean = false,
    val httpStatus: Int? = null,
    val bodyReadCompleted: Boolean = false,
    val jsonParsed: Boolean = false,
    val modelsArrayFound: Boolean = false,
    val visibleModelCount: Int = 0,
    val parsedModelCount: Int = 0,
    val failureStage: CatalogStage = CatalogStage.NONE,
    val transportCategory: TransportCategory = TransportCategory.NONE,
    val protocolCategory: CatalogProtocol = CatalogProtocol.NONE,
    val failureCategory: CatalogFailure = CatalogFailure.NONE
) {
    fun summary(): String = listOf(
        "模型目录诊断（仅脱敏状态）",
        "令牌可用：$tokenAvailable；请求已创建：$requestCreated",
        "请求已启动：$requestStarted；收到 HTTP 响应：$httpResponseReceived",
        "HTTP 状态：${httpStatus ?: "无"}；正文读取完成：$bodyReadCompleted",
        "JSON 解析完成：$jsonParsed；找到 models 数组：$modelsArrayFound",
        "可见模型数：$visibleModelCount；成功解析数：$parsedModelCount",
        "失败阶段：$failureStage；网络类别：$transportCategory",
        "协议类别：$protocolCategory；结果类别：$failureCategory",
        "true = 是；false = 否；NONE = 无"
    ).joinToString("\n")
}

/** Per-operation trace; terminal snapshots cannot be overwritten by late cancellation callbacks. */
class ModelCatalogTrace {
    private var value = ModelCatalogDiagnostic()
    private var finished = false
    private var networkStage = CatalogStage.REQUEST
    @Synchronized internal fun stage(): CatalogStage = networkStage
    @Synchronized internal fun at(stage: CatalogStage) { if (!finished) networkStage = stage }
    @Synchronized fun snapshot(): ModelCatalogDiagnostic = value
    @Synchronized private fun update(change: (ModelCatalogDiagnostic) -> ModelCatalogDiagnostic) {
        if (!finished) value = change(value)
    }
    internal fun tokenAvailable() = update { it.copy(tokenAvailable = true) }
    internal fun requestCreated() = update { it.copy(requestCreated = true) }
    internal fun requestStarted() = update { it.copy(requestStarted = true) }
    internal fun responseReceived(status: Int) {
        require(status in 100..599)
        update { it.copy(httpResponseReceived = true, httpStatus = status) }
    }
    internal fun bodyRead() = update { it.copy(bodyReadCompleted = true) }
    internal fun jsonParsed() = update { it.copy(jsonParsed = true) }
    internal fun modelsFound() = update { it.copy(modelsArrayFound = true) }
    internal fun visible(count: Int) { require(count in 0..1024); update { it.copy(visibleModelCount = count) } }
    internal fun parsed(count: Int) { require(count in 0..1024); update { it.copy(parsedModelCount = count) } }
    @Synchronized internal fun finish(stage: CatalogStage = CatalogStage.NONE,
        failure: CatalogFailure = CatalogFailure.NONE, transport: TransportCategory = TransportCategory.NONE,
        protocol: CatalogProtocol = CatalogProtocol.NONE) {
        if (finished) return
        value = value.copy(failureStage = stage, failureCategory = failure,
            transportCategory = transport, protocolCategory = protocol)
        finished = true
    }
}

/** Inspect types only; never retain the original exception or export its message/cause. */
internal fun transportCategory(error: IOException, cancelled: Boolean = false): TransportCategory {
    if (cancelled) return TransportCategory.CANCELLED
    val causes = mutableListOf<Throwable>()
    var current: Throwable? = error
    repeat(8) {
        val next = current ?: return@repeat
        if (causes.any { it === next }) return@repeat
        causes += next
        current = next.cause
    }
    return when {
        causes.any { it is SSLException || it is CertificateException } -> TransportCategory.NETWORK_TLS
        causes.any { it is UnknownHostException } -> TransportCategory.NETWORK_DNS
        causes.any { it is InterruptedIOException } -> TransportCategory.TIMEOUT
        causes.any { it is ConnectException || it is NoRouteToHostException } -> TransportCategory.NETWORK_CONNECT
        else -> TransportCategory.NETWORK_IO
    }
}
