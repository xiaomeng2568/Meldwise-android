// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.data

import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.serialization.Serializable

@Serializable enum class DebateStageType { INITIAL_A, INITIAL_B, REVIEW_A_OF_B, REVIEW_B_OF_A, JUDGE }
@Serializable enum class DebateStageState { Pending, Running, Complete, Failed, Cancelled, Interrupted, NotRun }
@Serializable enum class DebateRoundState { Pending, Running, Complete, Failed, Cancelled, Interrupted;
    val active get()=this==Pending || this==Running
}
/** Intentionally separate from CollaborateModel: no Alpha-wide serialization refactor. */
@Serializable data class DebateModel(val ref:ModelRef,val displayName:String?=null,
    val preference:ReasoningPreference=ReasoningPreference.Auto,
    val providerDisplayName:String=when(ref.providerId) {
        ProviderIds.CHATGPT->"ChatGPT";ProviderIds.DEEPSEEK->"DeepSeek";else->"未知提供方"
    })
@Serializable data class DebateConfig(val modelA:DebateModel,val modelB:DebateModel,val judge:DebateModel) {
    val providers get()=setOf(modelA.ref.providerId,modelB.ref.providerId,judge.ref.providerId)
    val providerSetKey get()=providers.sorted().joinToString("|")
    fun modelFor(type:DebateStageType)=when(type) {
        DebateStageType.INITIAL_A,DebateStageType.REVIEW_A_OF_B->modelA
        DebateStageType.INITIAL_B,DebateStageType.REVIEW_B_OF_A->modelB
        DebateStageType.JUDGE->judge
    }
}
@Serializable data class DebateStage(val stageId:String,val type:DebateStageType,val model:DebateModel,
    val output:String="",val reasoning:ReasoningRecord=ReasoningRecord(),
    val state:DebateStageState=DebateStageState.Pending,val error:ErrorKind?=null,
    val processingDuration:Long?=null,val startedAt:Long?=null,val endedAt:Long?=null) {
    override fun toString()="DebateStage(type=$type, state=$state, content=[REDACTED])"
}
@Serializable data class DebateRound(val roundId:String,val userMessageId:String,val config:DebateConfig,
    val stages:List<DebateStage>,val frozenInput:List<FrozenVisibleInput>,val inputRevision:Long,
    val createdAt:Long,val updatedAt:Long,val lifecycle:DebateRoundState=DebateRoundState.Pending,
    val retryOf:String?=null,val sourceProviders:Set<String> = emptySet()) {
    fun stage(type:DebateStageType)=stages.single {it.type==type}
    override fun toString()="DebateRound(lifecycle=$lifecycle, content=[REDACTED])"
}
class PreparedDebate internal constructor(val conversationId:String,val previousId:String?,val text:String,
    val config:DebateConfig,val context:VisibleContext,val requiresSharing:Boolean,
    internal val revision:Long,val retryOf:String?=null,val inputRevision:Long=revision) {
    override fun toString()="PreparedDebate([REDACTED])"
}

/** Fixed DAG, not a sequential index pipeline. Ready nodes can be executed in parallel in Phase 7B. */
object DebateDag {
    fun dependencies(type:DebateStageType):Set<DebateStageType> = when(type) {
        DebateStageType.INITIAL_A,DebateStageType.INITIAL_B->emptySet()
        DebateStageType.REVIEW_A_OF_B,DebateStageType.REVIEW_B_OF_A->setOf(DebateStageType.INITIAL_A,DebateStageType.INITIAL_B)
        DebateStageType.JUDGE->setOf(DebateStageType.REVIEW_A_OF_B,DebateStageType.REVIEW_B_OF_A)
    }
    fun inputsReady(round:DebateRound,type:DebateStageType)=dependencies(type).all {
        val s=round.stage(it);s.state==DebateStageState.Complete && s.output.isNotBlank()
    }
    fun readyStages(round:DebateRound):List<DebateStageType> = if(!round.lifecycle.active) emptyList() else
        DebateStageType.entries.filter {round.stage(it).state==DebateStageState.Pending && inputsReady(round,it)}
}

internal val debateProviderKeys=setOf("chatgpt","deepseek","chatgpt|deepseek")
internal fun validDebateConfig(config:DebateConfig) {
    listOf(config.modelA,config.modelB,config.judge).forEach {m ->
        require(m.ref.providerId in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK))
        require(m.ref.modelId.matches(Regex("[A-Za-z0-9._:/-]{1,128}")) && m.ref.modelId!="UNKNOWN")
        require(m.providerDisplayName==if(m.ref.providerId==ProviderIds.CHATGPT) "ChatGPT" else "DeepSeek")
        require(m.displayName==null || m.displayName.isNotBlank() && m.displayName.length<=256 && m.displayName.none {it.isISOControl()})
        require(ReasoningPolicy.supported(m.ref,m.preference))
    }
    require(config.modelA.ref!=config.modelB.ref)
}
fun debateNeedsSharing(config:DebateConfig,sourceProviders:Set<String>,grants:Set<String>):Boolean {
    validDebateConfig(config);require(grants.all {it in debateProviderKeys})
    return (config.providers.size>1 || sourceProviders.any {it !in config.providers}) && config.providerSetKey !in grants
}
fun debateRoundLifecycle(stages:List<DebateStage>):DebateRoundState = when {
    stages.all {it.state==DebateStageState.Complete}->DebateRoundState.Complete
    stages.any {it.state==DebateStageState.Failed}->DebateRoundState.Failed
    stages.any {it.state==DebateStageState.Cancelled}->DebateRoundState.Cancelled
    stages.any {it.state==DebateStageState.Interrupted || it.state==DebateStageState.NotRun}->DebateRoundState.Interrupted
    stages.all {it.state==DebateStageState.Pending}->DebateRoundState.Pending
    else->DebateRoundState.Running
}
internal fun interruptedDebateReasoning(r:ReasoningRecord)=if(r.phase in setOf(ReasoningPhase.Waiting,ReasoningPhase.Streaming))
    ReasoningRecord(r.text,r.kind,ReasoningPhase.Interrupted) else interruptReasoning(r)
/** A terminal round never leaves runnable work. Completed siblings are untouched.
 * Phase 7B must cancel the underlying request before persisting a cancelled running sibling.
 */
fun settleDebateRound(round:DebateRound,now:Long):DebateRound {
    val state=debateRoundLifecycle(round.stages)
    return round.copy(lifecycle=state,updatedAt=maxOf(now,round.updatedAt),stages=round.stages.map {s ->
        when {
            state.active->s
            s.state==DebateStageState.Pending->s.copy(state=DebateStageState.NotRun)
            // Interrupted wave cleanup must not acquire the stronger user-Cancelled lifecycle.
            // 7B joins sibling transports first; their stop is interruption, not a user action.
            s.state==DebateStageState.Running->s.copy(state=if(state==DebateRoundState.Interrupted) DebateStageState.Interrupted else DebateStageState.Cancelled,
                error=if(state==DebateRoundState.Interrupted) ErrorKind.STREAM_INTERRUPTED else ErrorKind.CANCELLED,
                endedAt=maxOf(now,s.startedAt ?: 0),reasoning=interruptedDebateReasoning(s.reasoning))
            else->s
        }
    })
}
fun restoreDebate(round:DebateRound):DebateRound {
    if(!round.lifecycle.active) return round
    // All active parallel nodes are interrupted, not only the first stage in display order.
    val restored=round.stages.map {s ->when(s.state) {
        DebateStageState.Running->s.copy(state=DebateStageState.Interrupted,reasoning=interruptedDebateReasoning(s.reasoning))
        DebateStageState.Pending->s.copy(state=DebateStageState.NotRun)
        else->s
    }}
    return round.copy(stages=restored,lifecycle=debateRoundLifecycle(restored))
}

internal fun validateDebate(c:Conversation) {
    validDebateConfig(requireNotNull(c.debate))
    require(c.collaborate==null && c.rounds.isEmpty() && c.collaborateGrants.isEmpty() && c.contextGrants.isEmpty())
    require(c.debateGrants.all {it in debateProviderKeys})
    require(c.messages.all {it.role==MessageRole.USER && it.state==MessageState.COMPLETED && it.reasoning.text.isEmpty()})
    require(c.debateRounds.size==c.messages.size && c.debateRounds.map {it.userMessageId}==c.messages.map {it.id})
    require(c.debateRounds.map {it.roundId}.distinct().size==c.debateRounds.size)
    val ids=c.debateRounds.flatMap {it.stages}.map {it.stageId}
    val allIds=ids+c.messages.map {it.id}+c.debateRounds.map {it.roundId}
    require(allIds.distinct().size==allIds.size)
    require(c.debateRounds.count {it.lifecycle.active}<=1 && c.debateRounds.dropLast(1).none {it.lifecycle.active})
    c.debateRounds.forEachIndexed {index,r ->
        validDebateConfig(r.config)
        require(r.roundId.length in 1..128 && r.inputRevision>=0 && r.createdAt>=0 && r.updatedAt>=r.createdAt)
        if(r.retryOf!=null) {
            val original=c.debateRounds.take(index).single {it.roundId==r.retryOf}
            require(!original.lifecycle.active && original.lifecycle!=DebateRoundState.Complete)
            require(r.config==original.config && r.frozenInput==original.frozenInput && r.sourceProviders==original.sourceProviders && r.inputRevision==original.inputRevision)
        }
        require(r.stages.size==5 && r.stages.map {it.type}.toSet()==DebateStageType.entries.toSet())
        require(r.frozenInput.size in 1..41 && r.frozenInput.size%2==1 && r.frozenInput.last().text==c.messages[index].text)
        require(r.frozenInput.withIndex().all {(i,m)->m.text.isNotBlank() && m.role==if(i%2==0) MessageRole.USER else MessageRole.ASSISTANT})
        require(r.frozenInput.sumOf {utf8ContentSize(it.text).toLong()}<=131072)
        require(r.sourceProviders.all {it in setOf("chatgpt","deepseek","UNKNOWN")})
        require(r.lifecycle==debateRoundLifecycle(r.stages))
        r.stages.forEach {s ->
            require(s.model==r.config.modelFor(s.type) && s.stageId.length in 1..128)
            require(s.output.length<=524288 && s.reasoning.text.length<=ReasoningReader.MAX_CHARS)
            require(s.model.ref.providerId!=ProviderIds.CHATGPT || s.reasoning.kind==ReasoningContent.Summary)
            require(s.processingDuration==null || s.processingDuration in 0..604800)
            require(s.startedAt==null || s.startedAt>=0);require(s.endedAt==null || s.endedAt>=0)
            require(s.startedAt==null || s.endedAt==null || s.endedAt>=s.startedAt)
            if(s.state==DebateStageState.Complete) require(s.output.isNotBlank() && s.error==null)
            if(s.state in setOf(DebateStageState.Pending,DebateStageState.NotRun)) require(s.output.isEmpty() && s.reasoning.text.isEmpty() && s.error==null && s.startedAt==null && s.endedAt==null)
            if(s.state in setOf(DebateStageState.Running,DebateStageState.Complete,DebateStageState.Failed)) require(DebateDag.inputsReady(r,s.type))
            if(s.startedAt!=null) require(DebateDag.inputsReady(r,s.type))
            if(!r.lifecycle.active) require(s.state !in setOf(DebateStageState.Running,DebateStageState.Pending))
        }
    }
}
