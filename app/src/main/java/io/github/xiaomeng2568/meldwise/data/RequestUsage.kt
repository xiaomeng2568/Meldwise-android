// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.data

import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

enum class UsageMode(val maximumRequests:Int) { SINGLE(1), COMPARE(2), COLLABORATE(3), DEBATE(5) }
enum class UsageSource { PROVIDER_REPORTED, UNAVAILABLE }
enum class UsageEvidence { DELTA, FINAL, COMPLETED }
enum class RequestOutcome { DISPATCHED, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

/** Semantic roles, never parsed from UI strings or inferred from model identity. */
enum class UsageSlot(val mode:UsageMode,val requestOrdinal:Int) {
    SINGLE(UsageMode.SINGLE,1), COMPARE_A(UsageMode.COMPARE,1), COMPARE_B(UsageMode.COMPARE,2),
    INITIAL(UsageMode.COLLABORATE,1), REVIEW(UsageMode.COLLABORATE,2), SYNTHESIS(UsageMode.COLLABORATE,3),
    INITIAL_A(UsageMode.DEBATE,1), INITIAL_B(UsageMode.DEBATE,2), REVIEW_A_OF_B(UsageMode.DEBATE,3),
    REVIEW_B_OF_A(UsageMode.DEBATE,4), JUDGE(UsageMode.DEBATE,5);
    companion object {
        fun compare(lane:String)=when(lane) {"A"->COMPARE_A;"B"->COMPARE_B;else->error("Invalid accounting lane")}
        fun collaborate(type:CollaborateStageType)=when(type) {
            CollaborateStageType.INITIAL->INITIAL;CollaborateStageType.REVIEW->REVIEW;CollaborateStageType.SYNTHESIS->SYNTHESIS
        }
        fun debate(type:DebateStageType)=when(type) {
            DebateStageType.INITIAL_A->INITIAL_A;DebateStageType.INITIAL_B->INITIAL_B
            DebateStageType.REVIEW_A_OF_B->REVIEW_A_OF_B;DebateStageType.REVIEW_B_OF_A->REVIEW_B_OF_A;DebateStageType.JUDGE->JUDGE
        }
    }
}

/** Existing assistant message / Compare run / orchestration round IDs; runtime only, no serializer. */
data class UsageOperationId(val mode:UsageMode,val operationId:String,val conversationId:String?=null) {
    init {
        require(operationId.length in 1..160 && operationId.none {it.isISOControl()})
        require(conversationId==null || conversationId.length in 1..160 && conversationId.none {it.isISOControl()})
    }
    override fun toString()="UsageOperationId(mode=$mode, ids=[REDACTED])"
}

@ConsistentCopyVisibility
data class RequestUsage internal constructor(val operation:UsageOperationId,val slot:UsageSlot,
    val stageId:String?,val modelRef:ModelRef,val outcome:RequestOutcome,
    val inputTokens:Long?,val outputTokens:Long?,val totalTokens:Long?,val source:UsageSource,val evidence:UsageEvidence?) {
    val providerId get()=modelRef.providerId
    val requestOrdinal get()=slot.requestOrdinal
    override fun toString()="RequestUsage(slot=$slot, outcome=$outcome, source=$source)"
}

class UsageSnapshot internal constructor(val operation:UsageOperationId,records:List<RequestUsage>) {
    val records:List<RequestUsage> = java.util.Collections.unmodifiableList(records.toList())
    val requestCount get()=records.size
    override fun toString()="UsageSnapshot(mode=${operation.mode}, requests=$requestCount)"
}

/** At most one current admitted operation. Old immutable snapshots can be held explicitly by callers.
 * No history ring, analytics stream, persistence, credentials, content or provider payload storage.
 */
class RuntimeUsage {
    @Volatile var current:UsageOperation?=null;private set
    @Synchronized fun begin(id:UsageOperationId):UsageOperation=UsageOperation(id).also {current=it}
    fun snapshot():UsageSnapshot?=current?.snapshot()
}

/** Concurrent siblings merge by fixed slot under one monitor. One dispatch and one outcome per slot. */
class UsageOperation internal constructor(val id:UsageOperationId) {
    private class Entry(val slot:UsageSlot,val stageId:String?,val ref:ModelRef) {
        var outcome=RequestOutcome.DISPATCHED
        var usage:Usage?=null
        var evidence:UsageEvidence?=null
        var finalSeen=false
        var providerTerminal=false
    }
    private val entries=mutableMapOf<UsageSlot,Entry>()

    /**
     * Dispatch boundary = invocation of the admitted provider's streamResponse operation.
     * No record is created by constructing this cold flow. Cancellation before collection is zero.
     * Preflight/input/admission remain outside this observer. Factory throws still count as dispatch.
     * It observes usage only; it neither buffers nor changes provider events or execution terminals.
     * The execution owner calls finish with its accepted outcome, including protocol/storage failure.
     */
    fun stream(slot:UsageSlot,ref:ModelRef,stageId:String?=null,invoke:()->Flow<LlmEvent>):Flow<LlmEvent> = flow {
        currentCoroutineContext().ensureActive()
        dispatched(slot,ref,stageId)
        invoke().collect {event ->observe(slot,event);emit(event)}
    }

    @Synchronized private fun dispatched(slot:UsageSlot,ref:ModelRef,stageId:String?) {
        require(slot.mode==id.mode && entries.size<id.mode.maximumRequests && slot !in entries)
        require(ref.providerId.length in 1..128 && ref.modelId.length in 1..128)
        require(stageId==null || stageId.length in 1..160)
        require(slot.mode !in setOf(UsageMode.COLLABORATE,UsageMode.DEBATE) || stageId!=null)
        require(stageId==null || entries.values.none {it.stageId==stageId})
        entries[slot]=Entry(slot,stageId,ref.copy())
    }

    @Synchronized private fun observe(slot:UsageSlot,event:LlmEvent) {
        val entry=entries.getValue(slot)
        if(entry.outcome!=RequestOutcome.DISPATCHED || entry.providerTerminal) return
        fun usage(value:Usage,evidence:UsageEvidence) {
            entry.usage=UsageNormalization.normalize(value)
            entry.evidence=if(entry.usage==null) null else evidence
        }
        when(event) {
            is LlmEvent.UsageDelta->if(!entry.finalSeen) usage(event.usage,UsageEvidence.DELTA)
            is LlmEvent.UsageFinal->if(!entry.finalSeen) {
                entry.finalSeen=true;usage(event.usage,UsageEvidence.FINAL)
            }
            is LlmEvent.Completed->{
                // Completed's non-null snapshot wins conflicts, even when invalid/unavailable.
                // Completed(null) retains earlier Final (or latest Delta with its explicit evidence).
                event.usage?.let {usage(it,UsageEvidence.COMPLETED)}
                entry.providerTerminal=true
            }
            is LlmEvent.Failed,is LlmEvent.Incomplete,LlmEvent.Cancelled->entry.providerTerminal=true
            else->Unit // Never retain prompt, answer, reasoning, IDs returned by providers or errors.
        }
    }

    @Synchronized fun finish(slot:UsageSlot,outcome:RequestOutcome) {
        require(outcome!=RequestOutcome.DISPATCHED)
        val entry=entries[slot] ?: return // No dispatch => no synthetic usage record for NotRun/preflight.
        if(entry.outcome==RequestOutcome.DISPATCHED) {entry.outcome=outcome;entry.providerTerminal=true}
    }

    @Synchronized fun snapshot():UsageSnapshot=UsageSnapshot(id,entries.values.sortedBy {it.slot.requestOrdinal}.map {
        RequestUsage(id,it.slot,it.stageId,it.ref,it.outcome,it.usage?.inputTokens,it.usage?.outputTokens,it.usage?.totalTokens,
            if(it.usage==null) UsageSource.UNAVAILABLE else UsageSource.PROVIDER_REPORTED,it.evidence)
    })
}
