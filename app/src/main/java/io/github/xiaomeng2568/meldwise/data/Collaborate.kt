// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.data

import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable enum class CollaborateStageType { INITIAL, REVIEW, SYNTHESIS }
/** Meldwise's review instruction policy, independent from provider reasoning effort. */
@Serializable enum class ReviewIntensity { CONCISE, STANDARD, STRICT }
@Serializable enum class SynthesisRole { PRIMARY, REVIEWER }
@Serializable enum class CollaborateStageState { Pending, Running, Complete, Failed, Cancelled, Interrupted, NotRun }
@Serializable enum class CollaborateRoundState { Pending, Running, Complete, Failed, Cancelled, Interrupted;
    val active get()=this==Pending || this==Running
}
@Serializable data class CollaborateModel(val ref:ModelRef,val displayName:String?=null,
    val preference:ReasoningPreference=ReasoningPreference.Auto,
    val providerDisplayName:String=when(ref.providerId) {ProviderIds.CHATGPT->"ChatGPT";ProviderIds.DEEPSEEK->"DeepSeek";else->"未知提供方"})
@Serializable data class CollaborateConfig(val primary:CollaborateModel,val reviewer:CollaborateModel,
    val reviewIntensity:ReviewIntensity=ReviewIntensity.STANDARD,val synthesisRole:SynthesisRole=SynthesisRole.PRIMARY) {
    val providers get()=setOf(primary.ref.providerId,reviewer.ref.providerId)
    val providerSetKey get()=providers.sorted().joinToString("|")
    fun modelFor(type:CollaborateStageType)=when(type) {
        CollaborateStageType.INITIAL->primary
        CollaborateStageType.REVIEW->reviewer
        CollaborateStageType.SYNTHESIS->if(synthesisRole==SynthesisRole.PRIMARY) primary else reviewer
    }
}
@Serializable data class FrozenVisibleInput(val role:MessageRole,val text:String) {
    override fun toString()="FrozenVisibleInput([REDACTED])"
}
@Serializable data class CollaborateStage(val stageId:String,val type:CollaborateStageType,val order:Int,
    val model:CollaborateModel,val output:String="",val reasoning:ReasoningRecord=ReasoningRecord(),
    val state:CollaborateStageState=CollaborateStageState.Pending,val error:ErrorKind?=null,
    val processingDuration:Long?=null,val startedAt:Long?=null,val endedAt:Long?=null) {
    override fun toString()="CollaborateStage(type=$type, state=$state, content=[REDACTED])"
}
@Serializable data class CollaborateRound(val roundId:String,val userMessageId:String,val stages:List<CollaborateStage>,
    val frozenInput:List<FrozenVisibleInput>,val inputRevision:Long,val createdAt:Long,val updatedAt:Long,
    val lifecycle:CollaborateRoundState=CollaborateRoundState.Running,val retryOf:String?=null,
    val reviewIntensity:ReviewIntensity=ReviewIntensity.STANDARD,val synthesisRole:SynthesisRole=SynthesisRole.PRIMARY) {
    // Additive schema-3 defaults preserve the original A -> B -> A strategy of old rounds.
    val config get()=CollaborateConfig(stages.first().model,stages[1].model,reviewIntensity,synthesisRole)
    override fun toString()="CollaborateRound(lifecycle=$lifecycle, content=[REDACTED])"
}
class PreparedCollaborate internal constructor(val conversationId:String,val previousId:String?,val text:String,
    val config:CollaborateConfig,val context:VisibleContext,val requiresSharing:Boolean,
    internal val revision:Long,val retryOf:String?=null) {
    override fun toString()="PreparedCollaborate([REDACTED])"
}

internal fun validCollaborateConfig(config:CollaborateConfig) {
    listOf(config.primary,config.reviewer).forEach {model ->
        require(model.ref.providerId in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK))
        require(model.ref.modelId.matches(Regex("[A-Za-z0-9._:/-]{1,128}")) && model.ref.modelId!="UNKNOWN")
        require(model.providerDisplayName==if(model.ref.providerId==ProviderIds.CHATGPT) "ChatGPT" else "DeepSeek")
        require(model.displayName==null || model.displayName.isNotBlank() && model.displayName.length<=256 && model.displayName.none {it.isISOControl()})
        require(ReasoningPolicy.supported(model.ref,model.preference))
    }
    require(config.primary.ref!=config.reviewer.ref)
}
internal fun roundLifecycle(stages:List<CollaborateStage>):CollaborateRoundState = when {
    stages.all {it.state==CollaborateStageState.Complete}->CollaborateRoundState.Complete
    stages.any {it.state==CollaborateStageState.Failed}->CollaborateRoundState.Failed
    stages.any {it.state==CollaborateStageState.Cancelled}->CollaborateRoundState.Cancelled
    stages.any {it.state==CollaborateStageState.Interrupted}->CollaborateRoundState.Interrupted
    else->CollaborateRoundState.Running
}
internal fun settleRound(round:CollaborateRound,now:Long):CollaborateRound {
    val state=roundLifecycle(round.stages)
    return round.copy(lifecycle=state,updatedAt=maxOf(now,round.createdAt),stages=round.stages.map {
        if(!state.active && it.state==CollaborateStageState.Pending) it.copy(state=CollaborateStageState.NotRun) else it
    })
}
internal fun interruptReasoning(r:ReasoningRecord)=if(r.text.isEmpty() || r.phase==ReasoningPhase.Completed) r else
    ReasoningRecord(r.text,r.kind,ReasoningPhase.Interrupted)
internal fun restoreCollaborate(round:CollaborateRound):CollaborateRound {
    if(!round.lifecycle.active) return round
    val active=round.stages.indexOfFirst {it.state==CollaborateStageState.Running || it.state==CollaborateStageState.Pending}
    return settleRound(round.copy(stages=round.stages.mapIndexed {index,s ->when {
        index==active->s.copy(state=CollaborateStageState.Interrupted,reasoning=interruptReasoning(s.reasoning))
        s.state==CollaborateStageState.Pending->s.copy(state=CollaborateStageState.NotRun)
        else->s
    }}),round.updatedAt)
}

/** One sequential attempt per stage. A verified terminal is required before the next request.
 * Consent/admission/persistence are owned by ChatRepository. There is no resume or retry loop.
 */
class CollaborateExecutor(private val registry:ProviderRegistry,private val repository:ChatRepository,
    private val contextBuilder:ConversationContextBuilder=ConversationContextBuilder(),
    private val monotonic:()->Long={System.nanoTime()/1_000_000},private val wallClock:()->Long=System::currentTimeMillis,
    val usage:RuntimeUsage=RuntimeUsage()) {
    private class StageFinished:RuntimeException()
    suspend fun execute(plan:PreparedCollaborate,allowSharing:Boolean=false,onUpdate:(Conversation)->Unit={}):Conversation = coroutineScope {
        currentCoroutineContext().ensureActive()
        // Capture the atomic admission result even if cancellation arrives during its disk write.
        // The outer same-dispatcher NonCancellable block avoids losing the newly persisted round ID on return from IO.
        var conversation=withContext(NonCancellable) {withContext(Dispatchers.IO) {repository.beginCollaborate(plan,allowSharing)}}
        val roundId=conversation.rounds.last().roundId
        val accounting=usage.begin(UsageOperationId(UsageMode.COLLABORATE,roundId,conversation.conversationId))
        try {
            onUpdate(conversation)
            for(index in 0..2) {
                currentCoroutineContext().ensureActive()
                if(!conversation.rounds.last().lifecycle.active) break
                conversation=withContext(Dispatchers.IO) {repository.startCollaborateStage(roundId,index)}
                onUpdate(conversation)
                val round=conversation.rounds.last()
                var stage=round.stages[index]
                val mutex=Mutex();var lastSaved=monotonic();var httpAt:Long?=null;var firstTextAt:Long?=null
                suspend fun update(force:Boolean=false,transform:(CollaborateStage)->CollaborateStage) {
                    mutex.withLock {
                        if(stage.state!=CollaborateStageState.Running) return@withLock
                        val next=transform(stage)
                        if(force || monotonic()-lastSaved>=200) {
                            try {
                                // A received terminal is durably recorded before returning to cancellable execution.
                                conversation=if(force) withContext(NonCancellable) {withContext(Dispatchers.IO) {repository.updateCollaborateStage(roundId,next)}}
                                    else withContext(Dispatchers.IO) {repository.updateCollaborateStage(roundId,next)}
                            } catch(cancel:CancellationException) {stage=next;throw cancel}
                            catch(_:Exception) {
                                // Keep the last valid partial when a size/disk bound rejects a larger update.
                                stage=repository.activeConversation()!!.rounds.last().stages[index]
                                throw ProviderFailure(LlmError(ErrorKind.STORAGE))
                            }
                            lastSaved=monotonic()
                        } else {
                            conversation=conversation.copy(rounds=conversation.rounds.map {r ->if(r.roundId==roundId)
                                r.copy(stages=r.stages.map {if(it.stageId==next.stageId) next else it}) else r})
                        }
                        stage=next
                        onUpdate(conversation)
                    }
                }
                val ticker=launch {
                    while(isActive) {
                        delay(200)
                        update {s ->s.copy(processingDuration=httpAt?.let {((firstTextAt ?: monotonic())-it).coerceAtLeast(0).div(1000).coerceAtMost(604800)})}
                    }
                }
                try {
                    if(!registry.ready(stage.model.ref)) throw ProviderFailure(LlmError(ErrorKind.AUTHENTICATION))
                    val input=contextBuilder.collaborateStageInput(round,index)
                    val provider=registry.get(stage.model.ref.providerId)
                    val request=LlmRequest(stage.model.ref.modelId,input,reasoning=stage.model.preference,observeHttp=true)
                    accounting.stream(UsageSlot.collaborate(stage.type),stage.model.ref,stage.stageId) {provider.streamResponse(request)}.collect {event ->
                        val terminal=event is LlmEvent.Completed || event is LlmEvent.Incomplete || event is LlmEvent.Failed || event==LlmEvent.Cancelled
                        update(terminal) {s ->when(event) {
                            LlmEvent.HttpReady->{if(httpAt==null) httpAt=monotonic();s}
                            is LlmEvent.TextDelta->{
                                if(s.output.length+event.text.length>524288) throw ProviderFailure(LlmError(ErrorKind.PROTOCOL))
                                if(firstTextAt==null) firstTextAt=monotonic()
                                s.copy(output=s.output+event.text,processingDuration=httpAt?.let {(firstTextAt!!-it).coerceAtLeast(0).div(1000).coerceAtMost(604800)})
                            }
                            is LlmEvent.ReasoningDelta->{
                                if(s.model.ref.providerId!=ProviderIds.DEEPSEEK && event.kind!=ReasoningContent.Summary ||
                                    s.reasoning.text.length+event.text.length>ReasoningReader.MAX_CHARS) throw ProviderFailure(LlmError(ErrorKind.PROTOCOL))
                                s.copy(reasoning=ReasoningRecord(s.reasoning.text+event.text,event.kind,ReasoningPhase.Streaming))
                            }
                            is LlmEvent.ReasoningDone->s.copy(reasoning=ReasoningRecord(s.reasoning.text,s.reasoning.kind,ReasoningPhase.Completed))
                            is LlmEvent.Completed->if(s.output.isBlank()) s.copy(state=CollaborateStageState.Failed,error=ErrorKind.PROTOCOL,endedAt=wallClock())
                                else s.copy(state=CollaborateStageState.Complete,endedAt=wallClock(),reasoning=ReasoningRecord(s.reasoning.text,s.reasoning.kind,
                                    if(s.reasoning.text.isEmpty()) ReasoningPhase.Unavailable else ReasoningPhase.Completed))
                            is LlmEvent.Failed->s.copy(state=CollaborateStageState.Failed,error=event.error.kind,endedAt=wallClock(),reasoning=interruptReasoning(s.reasoning))
                            is LlmEvent.Incomplete->s.copy(state=if(event.error.kind==ErrorKind.STREAM_INTERRUPTED) CollaborateStageState.Interrupted else CollaborateStageState.Failed,
                                error=event.error.kind,endedAt=wallClock(),reasoning=interruptReasoning(s.reasoning))
                            LlmEvent.Cancelled->s.copy(state=CollaborateStageState.Cancelled,error=ErrorKind.CANCELLED,endedAt=wallClock(),reasoning=interruptReasoning(s.reasoning))
                            else->s
                        }}
                        if(terminal) throw StageFinished()
                    }
                    update(true) {it.copy(state=CollaborateStageState.Interrupted,error=ErrorKind.STREAM_INTERRUPTED,endedAt=wallClock(),reasoning=interruptReasoning(it.reasoning))}
                } catch(_:StageFinished) { /* The first validated provider terminal ends this stage. */ }
                catch(cancel:CancellationException) {
                    withContext(NonCancellable) {update(true) {it.copy(state=CollaborateStageState.Cancelled,error=ErrorKind.CANCELLED,endedAt=wallClock(),reasoning=interruptReasoning(it.reasoning))}}
                    throw cancel
                } catch(f:Exception) {
                    update(true) {it.copy(state=CollaborateStageState.Failed,error=if(f is ProviderFailure) f.error.kind else ErrorKind.UNKNOWN,
                        endedAt=wallClock(),reasoning=interruptReasoning(it.reasoning))}
                } finally {
                    withContext(NonCancellable) {ticker.cancelAndJoin()}
                    accounting.finish(UsageSlot.collaborate(stage.type),when(stage.state) {
                        CollaborateStageState.Complete->RequestOutcome.COMPLETED;CollaborateStageState.Failed->RequestOutcome.FAILED
                        CollaborateStageState.Cancelled->RequestOutcome.CANCELLED;else->RequestOutcome.INTERRUPTED
                    })
                }
            }
        } catch(cancel:CancellationException) {
            withContext(NonCancellable) {conversation=withContext(Dispatchers.IO) {repository.cancelCollaborateRound(roundId)};onUpdate(conversation)}
            throw cancel
        }
        conversation
    }
}
