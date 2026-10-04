// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class UsageContractTests {
    private val a=Usage(1,2,8);private val b=Usage(3,4,20)
    private val ref=ModelRef("chatgpt","exact")
    private fun operation(mode:UsageMode=UsageMode.SINGLE)=RuntimeUsage().begin(UsageOperationId(mode,"attempt","conversation"))
    private fun record(vararg events:LlmEvent):RequestUsage=runBlocking {
        val op=operation();op.stream(UsageSlot.SINGLE,ref) {flowOf(*events)}.collect()
        op.finish(UsageSlot.SINGLE,RequestOutcome.COMPLETED);op.snapshot().records.single()
    }
    @Test fun repeatedDeltaReplacesNotAdds() {assertEquals(1L,record(LlmEvent.UsageDelta(a),LlmEvent.UsageDelta(a)).inputTokens)}
    @Test fun latestDeltaWins() {assertEquals(3L,record(LlmEvent.UsageDelta(a),LlmEvent.UsageDelta(b)).inputTokens)}
    @Test fun firstFinalSupersedesDelta() {assertEquals(3L,record(LlmEvent.UsageDelta(a),LlmEvent.UsageFinal(b)).inputTokens)}
    @Test fun deltaAfterFinalIgnored() {assertEquals(1L,record(LlmEvent.UsageFinal(a),LlmEvent.UsageDelta(b)).inputTokens)}
    @Test fun conflictingRepeatedFinalFirstWins() {assertEquals(1L,record(LlmEvent.UsageFinal(a),LlmEvent.UsageFinal(b)).inputTokens)}
    @Test fun identicalFinalIsHarmless() {assertEquals(8L,record(LlmEvent.UsageFinal(a),LlmEvent.UsageFinal(a)).totalTokens)}
    @Test fun completedSnapshotWinsConflict() {assertEquals(3L,record(LlmEvent.UsageFinal(a),LlmEvent.Completed(b)).inputTokens)}
    @Test fun completedNullRetainsFinal() {assertEquals(UsageEvidence.FINAL,record(LlmEvent.UsageFinal(a),LlmEvent.Completed(null)).evidence)}
    @Test fun completedNullRetainsDeltaWithEvidence() {assertEquals(UsageEvidence.DELTA,record(LlmEvent.UsageDelta(a),LlmEvent.Completed(null)).evidence)}
    @Test fun invalidCompletedDiscardsEarlierUsageOnly() {assertEquals(UsageSource.UNAVAILABLE,record(LlmEvent.UsageFinal(a),LlmEvent.Completed(Usage(-1,2,3))).source)}
    @Test fun terminalOnceFreezesUsage() {assertEquals(1L,record(LlmEvent.Completed(a),LlmEvent.Completed(b),LlmEvent.UsageFinal(b)).inputTokens)}
    @Test fun failedThenLateUsageIgnored() {assertNull(record(LlmEvent.Failed(LlmError(ErrorKind.NETWORK)),LlmEvent.UsageFinal(a)).inputTokens)}
    @Test fun cancelledRetainsActuallyReportedUsage() {assertEquals(1L,record(LlmEvent.UsageFinal(a),LlmEvent.Cancelled,LlmEvent.Completed(b)).inputTokens)}
    @Test fun incompleteRetainsKnownUsage() {assertEquals(1L,record(LlmEvent.UsageDelta(a),LlmEvent.Incomplete(LlmError(ErrorKind.STREAM_INTERRUPTED)),LlmEvent.UsageFinal(b)).inputTokens)}
    @Test fun noCharacterEstimation() {
        val r=record(LlmEvent.TextDelta("a".repeat(1000)),LlmEvent.ReasoningDelta("r".repeat(5000),ReasoningContent.Summary),LlmEvent.Completed(null))
        assertNull(r.inputTokens);assertNull(r.outputTokens);assertNull(r.totalTokens)
    }
    @Test fun constructionIsNotDispatch() {val op=operation();op.stream(UsageSlot.SINGLE,ref) {error("not invoked")};assertEquals(0,op.snapshot().requestCount)}
    @Test fun invocationThrowStillCounts()=runBlocking {
        val op=operation()
        try {op.stream(UsageSlot.SINGLE,ref) {throw IllegalStateException("transport")}.collect();fail()} catch(_:IllegalStateException) {}
        op.finish(UsageSlot.SINGLE,RequestOutcome.FAILED);assertEquals(1,op.snapshot().requestCount)
        assertEquals(RequestOutcome.FAILED,op.snapshot().records.single().outcome)
    }
    @Test fun cancelledBeforeCollectionZeroRequests()=runBlocking {
        val op=operation();val job=launch(start=CoroutineStart.LAZY) {op.stream(UsageSlot.SINGLE,ref) {error("not invoked")}.collect()}
        job.cancel();job.join();assertEquals(0,op.snapshot().requestCount)
    }
    @Test fun finishedOutcomeAndLateCallbackCannotRevive()=runBlocking {
        val op=operation()
        op.stream(UsageSlot.SINGLE,ref) {flow {emit(LlmEvent.UsageDelta(a));op.finish(UsageSlot.SINGLE,RequestOutcome.CANCELLED);emit(LlmEvent.Completed(b))}}.collect()
        op.finish(UsageSlot.SINGLE,RequestOutcome.COMPLETED)
        assertEquals(RequestOutcome.CANCELLED,op.snapshot().records.single().outcome);assertEquals(1L,op.snapshot().records.single().inputTokens)
    }
    @Test fun duplicateDispatchRejectedBeforeInvocation()=runBlocking {
        val op=operation();var calls=0
        op.stream(UsageSlot.SINGLE,ref) {calls++;emptyFlow()}.collect()
        try {op.stream(UsageSlot.SINGLE,ref) {calls++;emptyFlow()}.collect();fail()} catch(_:IllegalArgumentException) {}
        assertEquals(1,calls);assertEquals(1,op.snapshot().requestCount)
    }
    @Test fun wrongModeSlotRejected()=runBlocking {
        val op=operation()
        try {op.stream(UsageSlot.JUDGE,ref,"stage") {error("not invoked")}.collect();fail()} catch(_:IllegalArgumentException) {}
        assertEquals(0,op.snapshot().requestCount)
    }
    @Test fun attemptSnapshotsRemainSeparate()=runBlocking {
        val owner=RuntimeUsage();val old=owner.begin(UsageOperationId(UsageMode.SINGLE,"old"))
        old.stream(UsageSlot.SINGLE,ref) {flowOf(LlmEvent.Completed(a))}.collect();old.finish(UsageSlot.SINGLE,RequestOutcome.COMPLETED)
        val snapshot=old.snapshot();val next=owner.begin(UsageOperationId(UsageMode.SINGLE,"retry"))
        assertEquals(1,snapshot.requestCount);assertEquals(0,next.snapshot().requestCount)
        assertEquals("retry",owner.snapshot()!!.operation.operationId)
    }
    @Test fun immutableSnapshot()=runBlocking {
        val op=operation();op.stream(UsageSlot.SINGLE,ref) {emptyFlow()}.collect();val before=op.snapshot()
        op.finish(UsageSlot.SINGLE,RequestOutcome.FAILED);assertEquals(RequestOutcome.DISPATCHED,before.records.single().outcome)
        try {(before.records as MutableList).clear();fail()} catch(_:UnsupportedOperationException) {}
    }
    @Test fun metadataOnlyNoContentSerialization() {
        val fields=RequestUsage::class.java.declaredFields.map {it.name}
        assertFalse(fields.any {it in setOf("prompt","answer","reasoning","credential","rawError","url","request","payload")})
        assertFalse(RequestUsage::class.java.annotations.any {it.annotationClass.simpleName=="Serializable"})
        val r=record(LlmEvent.TextDelta("PRIVATE_ANSWER"),LlmEvent.Failed(LlmError(ErrorKind.UNKNOWN)))
        assertFalse(r.toString().contains("PRIVATE"));assertFalse(r.operation.toString().contains("conversation"))
    }
    private fun totals(usages:List<Usage?>):UsageTotals=runBlocking {
        val op=operation(UsageMode.COMPARE)
        usages.forEachIndexed {i,u ->val slot=if(i==0) UsageSlot.COMPARE_A else UsageSlot.COMPARE_B
            op.stream(slot,ref) {flowOf(LlmEvent.Completed(u))}.collect();op.finish(slot,RequestOutcome.COMPLETED)}
        UsageTotals.from(op.snapshot())
    }
    @Test fun partialCoverageTravelsWithKnownSum() {
        val t=totals(listOf(a,null));assertEquals(2,t.requestCount);assertEquals(1,t.usageKnownRequests);assertEquals(1,t.usageUnavailableRequests)
        assertEquals(1L,t.knownInputTokens.value);assertEquals(1,t.knownInputTokens.reportedRequests);assertFalse(t.knownInputTokens.allRequestsReported)
    }
    @Test fun perFieldCoverageSeparateFromUsageCoverage() {
        val t=totals(listOf(Usage(1,null,null),Usage(null,2,9)))
        assertEquals(2,t.usageKnownRequests);assertEquals(1,t.knownTotalTokens.reportedRequests);assertEquals(9L,t.knownTotalTokens.value)
    }
    @Test fun noReportedValuesRemainUnknown() {assertNull(totals(listOf(null,null)).knownTotalTokens.value)}
    @Test fun reportedZeroIsNotUnknown() {assertEquals(0L,totals(listOf(Usage(null,null,0),null)).knownTotalTokens.value)}
    @Test fun overflowIsExplicitNotWrappedOrSaturated() {
        val t=totals(listOf(Usage(null,null,Long.MAX_VALUE),Usage(null,null,1)))
        assertTrue(t.knownTotalTokens.overflowed);assertNull(t.knownTotalTokens.value);assertEquals(2,t.knownTotalTokens.reportedRequests)
    }
    @Test fun exactMaxSumValid() {assertEquals(Long.MAX_VALUE,totals(listOf(Usage(null,null,Long.MAX_VALUE-1),Usage(null,null,1))).knownTotalTokens.value)}
    @Test fun unknownTotalNotDerivedFromInputOutput() {assertNull(totals(listOf(Usage(1,2,null),Usage(3,4,null))).knownTotalTokens.value)}
    @Test fun emptyOperationNotCompleteUsage() {val t=UsageTotals.from(operation().snapshot());assertEquals(0,t.requestCount);assertFalse(t.knownTotalTokens.allRequestsReported)}
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun parallelHeavyInterleavingHasNoLostUpdates()=runBlocking {
        val op=operation(UsageMode.DEBATE);val gate=CompletableDeferred<Unit>();val arrivals=AtomicInteger()
        // A suspending barrier allows all five siblings to arrive even on a two-worker CI runner.
        withContext(Dispatchers.Default.limitedParallelism(2)) {
            UsageSlot.entries.filter {it.mode==UsageMode.DEBATE}.map {slot ->async {
                op.stream(slot,ref,"stage-${slot.name}") {flow {
                    if(arrivals.incrementAndGet()==5) gate.complete(Unit)
                    withTimeout(5000) {gate.await()}
                    repeat(1000) {
                        emit(LlmEvent.UsageDelta(Usage(slot.requestOrdinal.toLong(),null,null)))
                        if(it%16==0) yield()
                    }
                    emit(LlmEvent.Completed(Usage(null,null,slot.requestOrdinal.toLong())))
                }}.collect();op.finish(slot,RequestOutcome.COMPLETED)
            }}.awaitAll()
        }
        val s=op.snapshot();assertEquals((1..5).toList(),s.records.map {it.requestOrdinal})
        assertEquals(15L,UsageTotals.from(s).knownTotalTokens.value);assertTrue(s.records.all {it.outcome==RequestOutcome.COMPLETED})
    }
}
