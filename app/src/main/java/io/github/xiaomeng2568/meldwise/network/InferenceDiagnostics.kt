package io.github.xiaomeng2568.meldwise.network

import io.github.xiaomeng2568.meldwise.provider.ErrorKind

enum class InferenceStage { NONE, CREDENTIAL, REQUEST_BUILD, HTTP, STREAM_OPEN, SSE_FRAME, EVENT_JSON,
    EVENT_STRUCTURE, ASSISTANT_ASSOCIATION, TEXT_EXTRACTION, TERMINAL_VALIDATION, PROVIDER_FAILED,
    EOF_BEFORE_TERMINAL, CANCELLED }
enum class InferenceProtocol { NONE, EVENT_TYPE_CONFLICT, EVENT_TYPE_MISSING, INDEX_MISSING,
    CONTENT_INDEX_MISSING, ITEM_MISSING, ITEM_ID_MISSING, ITEM_CONFLICT, ASSISTANT_ITEM_REQUIRED,
    TEXT_CONFLICT, OUTPUT_LIMIT, ASSISTANT_OUTPUT_TEXT_NOT_DETECTED, TERMINAL_STATUS_INVALID,
    TERMINAL_ID_MISSING, JSON_INVALID, SSE_INVALID, UNKNOWN_EVENT_STRUCTURE }
enum class ResponseEvent { NONE, CREATED, IN_PROGRESS, OUTPUT_ITEM_ADDED, OUTPUT_ITEM_DONE,
    CONTENT_PART_ADDED, CONTENT_PART_DONE, OUTPUT_TEXT_DELTA, OUTPUT_TEXT_DONE,
    COMPLETED, FAILED, INCOMPLETE, ERROR, REFUSAL, OTHER }
enum class ProviderBodyShape { NONE, STANDARD_ERROR_OBJECT, DETAIL_OBJECT, OTHER_JSON, NON_JSON }
enum class ProviderCode { NONE, UNKNOWN, AUTHENTICATION, AUTHORIZATION, PLAN_USAGE_LIMIT,
    RATE_LIMIT, CONTEXT_OVERFLOW, MODEL_UNAVAILABLE, UNSUPPORTED_CAPABILITY, SERVER, POLICY_UNAVAILABLE }

/** Closed schema: no strings, identifiers, exception messages, headers or response bodies. */
@ConsistentCopyVisibility
data class InferenceDiagnostic internal constructor(
    val credentialAvailable:Boolean=false, val requestBuilt:Boolean=false, val requestStarted:Boolean=false,
    val httpReceived:Boolean=false, val httpStatus:Int?=null,
    val streamBodyOpened:Boolean=false, val sseParserStarted:Boolean=false,
    val networkExchangeCount:Int=0,
    val parsedEventCount:Int=0, val firstEvent:ResponseEvent=ResponseEvent.NONE, val lastEvent:ResponseEvent=ResponseEvent.NONE,
    val createdSeen:Boolean=false, val assistantOutputItemSeen:Boolean=false,
    val textDeltaSeen:Boolean=false, val textDoneSeen:Boolean=false, val contentPartDoneSeen:Boolean=false,
    val outputItemDoneSeen:Boolean=false, val completedSeen:Boolean=false, val failedSeen:Boolean=false, val incompleteSeen:Boolean=false,
    val assistantTextProduced:Boolean=false, val terminalSuccessValidated:Boolean=false,
    val failureStage:InferenceStage=InferenceStage.NONE, val protocolCategory:InferenceProtocol=InferenceProtocol.NONE,
    val providerBodyShape:ProviderBodyShape=ProviderBodyShape.NONE, val providerCode:ProviderCode=ProviderCode.NONE,
    val resultCategory:ErrorKind?=null, val transportCategory:TransportCategory=TransportCategory.NONE
) {
    fun summary()=listOf(
        "推理链路诊断（仅脱敏状态）",
        "令牌可用：$credentialAvailable；请求已创建：$requestBuilt；已启动：$requestStarted",
        "收到 HTTP：$httpReceived；HTTP 状态：${httpStatus ?: "无"}",
        "流正文已打开：$streamBodyOpened；SSE 解析已启动：$sseParserStarted",
        "本次流请求网络发送次数：$networkExchangeCount",
        "解析事件数：$parsedEventCount；首事件：$firstEvent；末事件：$lastEvent",
        "response.created：$createdSeen；助手输出项：$assistantOutputItemSeen",
        "output_text.delta：$textDeltaSeen；output_text.done：$textDoneSeen",
        "content_part.done：$contentPartDoneSeen；output_item.done：$outputItemDoneSeen",
        "response.completed：$completedSeen；response.failed：$failedSeen；response.incomplete：$incompleteSeen",
        "助手文本已产生：$assistantTextProduced；终态成功已验证：$terminalSuccessValidated",
        "失败阶段：$failureStage；协议类别：$protocolCategory",
        "服务错误结构：$providerBodyShape；服务错误代码类别：$providerCode",
        "结果类别：${resultCategory ?: "NONE"}；网络类别：$transportCategory",
        "true = 是；false = 否；NONE = 无"
    ).joinToString("\n")
}

class InferenceTrace {
    private var value=InferenceDiagnostic()
    private var finished=false
    @Synchronized fun snapshot()=value
    @Synchronized internal fun update(change:(InferenceDiagnostic)->InferenceDiagnostic) {
        if(!finished) value=change(value)
    }
    internal fun requestStarted()=update { it.copy(requestStarted=true) }
    internal fun networkExchange()=update { it.copy(networkExchangeCount=(it.networkExchangeCount+1).coerceAtMost(2)) }
    internal fun event(type:String) {
        val category=when(type) {
            "response.created"->ResponseEvent.CREATED; "response.in_progress"->ResponseEvent.IN_PROGRESS
            "response.output_item.added"->ResponseEvent.OUTPUT_ITEM_ADDED; "response.output_item.done"->ResponseEvent.OUTPUT_ITEM_DONE
            "response.content_part.added"->ResponseEvent.CONTENT_PART_ADDED; "response.content_part.done"->ResponseEvent.CONTENT_PART_DONE
            "response.output_text.delta"->ResponseEvent.OUTPUT_TEXT_DELTA; "response.output_text.done"->ResponseEvent.OUTPUT_TEXT_DONE
            "response.completed"->ResponseEvent.COMPLETED; "response.failed"->ResponseEvent.FAILED
            "response.incomplete"->ResponseEvent.INCOMPLETE; "error"->ResponseEvent.ERROR
            "response.refusal.delta","response.refusal.done"->ResponseEvent.REFUSAL; else->ResponseEvent.OTHER
        }
        update { it.copy(parsedEventCount=(it.parsedEventCount+1).coerceAtMost(1_000_000),
            firstEvent=if(it.parsedEventCount==0) category else it.firstEvent, lastEvent=category,
            createdSeen=it.createdSeen || category==ResponseEvent.CREATED,
            textDeltaSeen=it.textDeltaSeen || category==ResponseEvent.OUTPUT_TEXT_DELTA,
            textDoneSeen=it.textDoneSeen || category==ResponseEvent.OUTPUT_TEXT_DONE,
            contentPartDoneSeen=it.contentPartDoneSeen || category==ResponseEvent.CONTENT_PART_DONE,
            outputItemDoneSeen=it.outputItemDoneSeen || category==ResponseEvent.OUTPUT_ITEM_DONE,
            completedSeen=it.completedSeen || category==ResponseEvent.COMPLETED,
            failedSeen=it.failedSeen || category==ResponseEvent.FAILED,
            incompleteSeen=it.incompleteSeen || category==ResponseEvent.INCOMPLETE) }
    }
    @Synchronized internal fun finish(stage:InferenceStage=InferenceStage.NONE,
        protocol:InferenceProtocol=InferenceProtocol.NONE, result:ErrorKind?=null,
        transport:TransportCategory=TransportCategory.NONE, success:Boolean=false) {
        if(finished) return
        value=value.copy(failureStage=stage,protocolCategory=protocol,resultCategory=result,
            transportCategory=transport,terminalSuccessValidated=success)
        finished=true
    }
}
