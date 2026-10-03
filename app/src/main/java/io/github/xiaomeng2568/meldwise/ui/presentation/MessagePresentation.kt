package io.github.xiaomeng2568.meldwise.ui.presentation

import io.github.xiaomeng2568.meldwise.data.ChatMessage
import io.github.xiaomeng2568.meldwise.data.MessageState
import io.github.xiaomeng2568.meldwise.provider.MessageRole
import io.github.xiaomeng2568.meldwise.provider.ModelRef
import io.github.xiaomeng2568.meldwise.provider.ProviderIds
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.data.CompareLaneRecord
import io.github.xiaomeng2568.meldwise.data.CompareRun
import io.github.xiaomeng2568.meldwise.data.ComparePromptItem
import io.github.xiaomeng2568.meldwise.ui.modelLabel
import io.github.xiaomeng2568.meldwise.ui.content.*

enum class LaneState { Pending, Waiting, Thinking, Streaming, Completed, Cancelled, Incomplete, Failed, Interrupted }
fun laneState(state: MessageState): LaneState = when(state) {
    MessageState.PENDING -> LaneState.Pending; MessageState.STREAMING -> LaneState.Streaming
    MessageState.COMPLETED -> LaneState.Completed; MessageState.CANCELLED -> LaneState.Cancelled
    MessageState.INCOMPLETE -> LaneState.Incomplete;MessageState.INTERRUPTED -> LaneState.Interrupted; MessageState.FAILED -> LaneState.Failed
}
fun laneLabel(state: LaneState): String = when(state) {
    LaneState.Pending -> "等待回答"; LaneState.Waiting -> "正在处理…"; LaneState.Thinking -> "正在思考…"; LaneState.Streaming -> "正在回答…"
    LaneState.Completed -> "已完成"; LaneState.Cancelled -> "已取消"; LaneState.Incomplete -> "回答未完成"; LaneState.Failed -> "请求失败";LaneState.Interrupted -> "上次回答已中断"
}
fun messageStateCaption(state: MessageState): String? = if(state==MessageState.INTERRUPTED) "上次回答已中断" else if (state == MessageState.COMPLETED) null else laneLabel(laneState(state))
fun providerLabel(id: String): String = when(id) {
    ProviderIds.CHATGPT -> "ChatGPT"; ProviderIds.DEEPSEEK -> "DeepSeek"; else -> "未知提供方"
}
class MessagePresentation(val user: Boolean, val metadata: String?, val stateCaption: String?,
    val answer: ParsedContent, val reasoning: ReasoningSummary) {
    override fun toString() = "MessagePresentation(user=$user, content=[REDACTED])"
}
fun presentMessage(message: ChatMessage, ref: ModelRef, name: String,
    reasoning: ReasoningSummary = ReasoningSummary()): MessagePresentation = MessagePresentation(
    message.role == MessageRole.USER,
    if (message.role == MessageRole.USER) null else modelLabel(message.modelRef ?: ref, message.modelDisplayName ?: message.modelRef?.modelId ?: name), messageStateCaption(message.state),
    if (message.role == MessageRole.USER) ParsedContent(listOf(PlainTextBlock(message.text)), false)
        else ContentParser.parse(message.text), reasoning)
class CompareLane(val ref: ModelRef, val displayName: String, val state: LaneState,
    val answer: String, val reasoning: ReasoningSummary = ReasoningSummary(),val seconds:Long?=null) {
    override fun toString() = "CompareLane(state=$state, content=[REDACTED])"
}
fun reasoningPresentation(record:ReasoningRecord)=ReasoningSummary(when(record.phase) {
    ReasoningPhase.Unavailable->ReasoningState.Unavailable;ReasoningPhase.Waiting->ReasoningState.Waiting
    ReasoningPhase.Streaming->ReasoningState.Streaming;ReasoningPhase.Completed->ReasoningState.Completed
    ReasoningPhase.Interrupted->ReasoningState.Interrupted
},record.text,if(record.kind==ReasoningContent.Summary) ReasoningKind.Summary else ReasoningKind.UserVisibleContent)
fun comparePresentation(lane:CompareLaneRecord,name:String)=CompareLane(lane.modelRef,name,LaneState.valueOf(lane.state.name),
    lane.output,reasoningPresentation(lane.reasoning),lane.processingDuration)
/** Renderable siblings in a single message list; identity never comes from a display label. */
sealed class CompareMessageItem(val key:String) {
    class Prompt(key:String,val item:ComparePromptItem):CompareMessageItem(key)
    class Output(key:String,val record:CompareLaneRecord,val presentation:CompareLane):CompareMessageItem(key)
    override fun toString()="CompareMessageItem(content=[REDACTED])"
}
fun compareMessageItems(run:CompareRun,catalogs:Map<String,List<LlmModel>> = emptyMap()):List<CompareMessageItem> =
    listOf(CompareMessageItem.Prompt("${run.id}/prompt/${run.userPrompt.id}",run.userPrompt)) + run.orderedOutputs.map {output ->
        val name=output.modelDisplayName ?: catalogs[output.modelRef.providerId]?.firstOrNull {it.id==output.modelRef.modelId}?.displayName ?: output.modelRef.modelId
        CompareMessageItem.Output("${run.id}/output/${output.laneId}",output,comparePresentation(output,name))
    }
fun assistantStatus(state:LaneState,seconds:Long?):String? = listOfNotNull(
    laneLabel(state).takeUnless {state==LaneState.Completed},seconds?.let {"已处理 $it 秒"}).joinToString(" · ").ifBlank {null}
fun selectedModel(current: ModelRef?, candidate: ModelRef) = current == candidate
/** Monotonic, sampled UI wait duration. Never identifies actual model thinking boundaries. */
class ResponseWaitTimer {
    private var started: Long? = null; private var ended: Long? = null
    fun observe(now: Long, httpReceived: Boolean, answerStarted: Boolean, terminal: Boolean) {
        if (started == null && httpReceived) started = now
        if (started != null && ended == null && (answerStarted || terminal)) ended = now
    }
    fun seconds(now: Long): Long? = started?.let { ((ended ?: now) - it).coerceAtLeast(0) / 1000 }
    val running get() = started != null && ended == null
}
