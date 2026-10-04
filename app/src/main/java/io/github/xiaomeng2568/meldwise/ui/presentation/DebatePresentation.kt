// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.presentation

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*

enum class DebateRole(val label:String) { A("模型 A"), B("模型 B"), JUDGE("Judge") }

/** An incomplete setup form is UI state, never a second persistence store or execution config. */
data class DebateSelection(val a:DebateModel?=null,val b:DebateModel?=null,val judge:DebateModel?=null) {
    fun model(role:DebateRole)=when(role) {DebateRole.A->a;DebateRole.B->b;DebateRole.JUDGE->judge}
    fun with(role:DebateRole,model:DebateModel)=when(role) {
        DebateRole.A->copy(a=model);DebateRole.B->copy(b=model);DebateRole.JUDGE->copy(judge=model)
    }
    fun config():DebateConfig? {
        val c=DebateConfig(a ?: return null,b ?: return null,judge ?: return null)
        return c.takeIf {runCatching {validDebateConfig(it)}.isSuccess}
    }
    override fun toString()="DebateSelection([REDACTED])"
    companion object {
        fun from(config:DebateConfig?)=config?.let {DebateSelection(it.modelA,it.modelB,it.judge)} ?: DebateSelection()
    }
}
data class DebateUiState(val selection:DebateSelection=DebateSelection(),val configured:DebateConfig?=null,
    val unavailable:Set<DebateRole> = DebateRole.entries.toSet(),val sharingProviders:Set<String>?=null) {
    val sendReady get()=selection.config()!=null && selection.config()==configured && unavailable.isEmpty()
    val validation get()=debateSetupIssue(selection,unavailable)
    override fun toString()="DebateUiState([REDACTED])"
}
fun debateSetupIssue(s:DebateSelection,unavailable:Set<DebateRole> = emptySet()):String?=when {
    s.a==null->"请选择模型 A。";s.b==null->"请选择模型 B。";s.judge==null->"请选择 Judge。"
    s.a.ref==s.b.ref->"模型 A 和模型 B 必须不同；Judge 可以与 A 或 B 相同。"
    s.config()==null->"所选模型或思考设置不受支持。"
    unavailable.isNotEmpty()->"${DebateRole.entries.filter {it in unavailable}.joinToString("、") {it.label}} 尚不可用，请配置账号并更新模型。"
    else->null
}
const val DEBATE_EXPLANATION="两个模型先独立回答，再交叉审阅，最后由 Judge 整理结果。"
const val DEBATE_FLOW="A + B 独立回答 → 交叉审阅 → Judge 综合"
const val DEBATE_USAGE="完整辩论最多发起 5 次模型请求，费用和额度按各自服务计算。"
fun debateSharingProviders(plan:PreparedDebate)=plan.config.providers+plan.context.sourceProviders
fun debateConsentCopy(providers:Set<String>):String {
    val names=providers.sorted().joinToString("、") {if(it=="UNKNOWN") "历史内容来源服务商（未知）" else providerLabel(it)}
    return "这次辩论需要在 $names 之间共享当前对话中选入上下文的可见消息和回答，用于审阅和整理。思考内容、凭据和诊断信息不会共享。此授权只用于当前对话与这组服务商。"
}
fun debateStageLabel(type:DebateStageType)=when(type) {
    DebateStageType.INITIAL_A->"模型 A · 初答";DebateStageType.INITIAL_B->"模型 B · 初答"
    DebateStageType.REVIEW_A_OF_B->"模型 A · 审阅 B";DebateStageType.REVIEW_B_OF_A->"模型 B · 审阅 A"
    DebateStageType.JUDGE->"Judge · 最终回答"
}
fun debateStageStatus(stage:DebateStage):String?=when(stage.state) {
    DebateStageState.Pending->"等待";DebateStageState.NotRun->"未执行"
    DebateStageState.Running->when(stage.type) {
        DebateStageType.INITIAL_A,DebateStageType.INITIAL_B->"正在回答…"
        DebateStageType.REVIEW_A_OF_B,DebateStageType.REVIEW_B_OF_A->"正在审阅…"
        DebateStageType.JUDGE->"正在整理…"
    }
    DebateStageState.Complete->null;DebateStageState.Failed->"失败"
    DebateStageState.Cancelled->"已取消";DebateStageState.Interrupted->"已中断"
}
fun debateProcessingCaption(stage:DebateStage):String?=stage.processingDuration?.takeIf {
    stage.output.isNotEmpty() && stage.state !in setOf(DebateStageState.Pending,DebateStageState.NotRun)
}?.let {"已处理 $it 秒"}
fun debateAnswerRole(type:DebateStageType)=if(type==DebateStageType.JUDGE) AnswerRole.Synthesis else
    if(type in setOf(DebateStageType.INITIAL_A,DebateStageType.INITIAL_B)) AnswerRole.Initial else AnswerRole.Review
fun debateRetryEligible(c:Conversation,round:DebateRound)=c.mode==ConversationMode.Debate &&
    c.debateRounds.lastOrNull()?.roundId==round.roundId && round.lifecycle in
    setOf(DebateRoundState.Failed,DebateRoundState.Cancelled,DebateRoundState.Interrupted)

sealed class DebateMessageItem(val key:String) {
    class Prompt(val message:ChatMessage):DebateMessageItem(message.id)
    class Stage(val roundId:String,val stage:DebateStage):DebateMessageItem(stage.stageId)
    override fun toString()="DebateMessageItem([REDACTED])"
}
/** Display order, not scheduling order. No dependency or provider-input logic belongs here. */
fun debateMessageItems(c:Conversation):List<DebateMessageItem> {
    require(c.mode==ConversationMode.Debate)
    return c.messages.flatMap {user ->
        val round=c.debateRounds.single {it.userMessageId==user.id}
        listOf(DebateMessageItem.Prompt(user))+DebateStageType.entries.map {DebateMessageItem.Stage(round.roundId,round.stage(it))}
    }
}
