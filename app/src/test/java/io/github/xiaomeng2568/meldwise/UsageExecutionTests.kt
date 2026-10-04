// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UsageExecutionTests {
    @Test fun debateGlobalReadinessRejectsWithoutDispatch()=runTest {
        val h=DebateHarness(this);h.readiness={_,_->false}
        val ex=h.executor()
        try {ex.execute(h.repository.prepareDebate("q"),true);fail()} catch(_:ProviderFailure) {}
        assertNull(ex.usage.snapshot());assertTrue(h.calls.isEmpty())
    }
    @Test fun debateRetryAttemptAccountingSeparate()=runTest {
        val h=DebateHarness(this);val owner=RuntimeUsage()
        val ex=DebateExecutor(h.registry,h.repository,persistenceDispatcher=StandardTestDispatcher(testScheduler),usage=owner)
        h.automatic={if(it.type==DebateStageType.INITIAL_A) it.fail(ErrorKind.NETWORK) else it.complete()}
        ex.execute(h.repository.prepareDebate("q"),true)
        val first=owner.snapshot()!!;assertEquals(2,first.requestCount)
        val plan=h.repository.prepareDebateRetry(first.operation.operationId)
        h.automatic={it.complete()}
        ex.execute(plan,true)
        val next=owner.snapshot()!!;assertEquals(5,next.requestCount)
        assertNotEquals(first.operation,next.operation);assertEquals(2,first.requestCount)
        assertEquals(first.operation.operationId,h.round().retryOf)
        assertEquals(7,first.requestCount+next.requestCount)
    }
    @Test fun debateParallelRecordsExistBeforeEitherCompletes()=runTest {
        val h=DebateHarness(this);val ex=h.executor()
        val task=async {ex.execute(h.repository.prepareDebate("q"),true)}
        runCurrent();assertEquals(2,ex.usage.snapshot()!!.requestCount)
        assertTrue(ex.usage.snapshot()!!.records.all {it.outcome==RequestOutcome.DISPATCHED})
        h.toReviews();assertEquals(4,ex.usage.snapshot()!!.requestCount)
        h.finishWave(DebateStageType.entries.slice(2..3));assertEquals(5,ex.usage.snapshot()!!.requestCount)
        h.call(DebateStageType.JUDGE).complete();runCurrent();task.await()
    }
    @Test fun debateBThenAUsageDoesNotSwapTargets()=runTest {
        val h=DebateHarness(this);val ex=h.executor();val task=async {ex.execute(h.repository.prepareDebate("q"),true)}
        runCurrent()
        h.call(DebateStageType.INITIAL_B).emit(LlmEvent.Completed(Usage(null,null,22)),LlmEvent.UsageFinal(Usage(null,null,99)))
        // A Complete must include visible text; B's blank terminal is a protocol failure but its actually reported usage is retained.
        h.call(DebateStageType.INITIAL_A).complete();runCurrent();task.await()
        val b=ex.usage.snapshot()!!.records.first {it.slot==UsageSlot.INITIAL_B}
        assertEquals(22L,b.totalTokens);assertEquals(h.config.modelB.ref,b.modelRef);assertEquals(RequestOutcome.FAILED,b.outcome)
    }
    @Test fun debateOrderIndependentFinalSubtotals()=runTest {
        suspend fun run(reverse:Boolean):UsageTotals {
            val h=DebateHarness(this);val ex=h.executor();val task=async {ex.execute(h.repository.prepareDebate("q"),true)}
            runCurrent()
            listOf(DebateStageType.entries.take(2),DebateStageType.entries.slice(2..3),listOf(DebateStageType.JUDGE)).forEach {wave ->
                (if(reverse) wave.reversed() else wave).forEach {type ->
                    h.call(type).emit(LlmEvent.UsageFinal(Usage(null,null,type.ordinal+1L)));h.call(type).complete();runCurrent()
                }
            };task.await();return UsageTotals.from(ex.usage.snapshot()!!)
        }
        assertEquals(run(false),run(true))
    }
    @Test fun debateEofCountsAsInterruptedWithoutEstimate()=runTest {
        val h=DebateHarness(this);h.automatic={it.emit(LlmEvent.TextDelta("x".repeat(1000)));it.eof()}
        val ex=h.executor();ex.execute(h.repository.prepareDebate("q"),true)
        assertEquals(2,ex.usage.snapshot()!!.requestCount)
        assertTrue(ex.usage.snapshot()!!.records.any {it.outcome==RequestOutcome.INTERRUPTED})
        assertTrue(ex.usage.snapshot()!!.records.all {it.outputTokens==null})
    }
    @Test fun singleAdmissionAndObserverUseStableMessageIdentity()=runBlocking {
        val repo=ChatRepository(MemoryBlob(),testBox());val ref=ModelRef("chatgpt","single")
        repo.newSession(ref);val admitted=repo.beginPrepared(repo.prepare("q"));val owner=RuntimeUsage()
        val op=owner.begin(UsageOperationId(UsageMode.SINGLE,admitted.first,repo.conversationId()))
        op.stream(UsageSlot.SINGLE,ref) {flowOf(LlmEvent.TextDelta("answer"),LlmEvent.Completed(Usage(1,2,null)))}.collect()
        op.finish(UsageSlot.SINGLE,RequestOutcome.COMPLETED)
        assertEquals(admitted.first,owner.snapshot()!!.operation.operationId);assertEquals(1,owner.snapshot()!!.requestCount)
        assertNull(owner.snapshot()!!.records.single().totalTokens)
    }
    @Test fun singleMandatoryOverflowBeforeDispatchZero() {
        val repo=ChatRepository(MemoryBlob(),testBox());repo.newSession(ModelRef("chatgpt","single"));val owner=RuntimeUsage()
        try {repo.prepare("x".repeat(1000000));fail()} catch(_:Exception) {}
        assertNull(owner.snapshot());assertTrue(repo.load().isEmpty())
    }
    @Test fun singleRevisionRejectionBeforeDispatchZero() {
        val repo=ChatRepository(MemoryBlob(),testBox());repo.newSession(ModelRef("chatgpt","single"));val plan=repo.prepare("q")
        repo.newSession(ModelRef("chatgpt","different"));val owner=RuntimeUsage()
        try {repo.beginPrepared(plan);fail()} catch(_:IllegalArgumentException) {}
        assertNull(owner.snapshot())
    }
    @Test fun singleCancelledAfterDispatchRemainsOne()=runTest {
        val op=RuntimeUsage().begin(UsageOperationId(UsageMode.SINGLE,"message"))
        val task=launch {try {op.stream(UsageSlot.SINGLE,ModelRef("chatgpt","a")) {flow {emit(LlmEvent.TextDelta("partial"));awaitCancellation()}}.collect()}
            finally {op.finish(UsageSlot.SINGLE,RequestOutcome.CANCELLED)}}
        runCurrent();task.cancelAndJoin()
        assertEquals(1,op.snapshot().requestCount);assertEquals(RequestOutcome.CANCELLED,op.snapshot().records.single().outcome)
        assertNull(op.snapshot().records.single().outputTokens)
    }
    @Test fun singleNetworkAfterDispatchRemainsOne()=runTest {
        val op=RuntimeUsage().begin(UsageOperationId(UsageMode.SINGLE,"message"))
        try {op.stream(UsageSlot.SINGLE,ModelRef("chatgpt","a")) {flow {throw java.io.IOException("transport")}}.collect();fail()}
        catch(_:java.io.IOException) {op.finish(UsageSlot.SINGLE,RequestOutcome.INTERRUPTED)}
        assertEquals(1,op.snapshot().requestCount);assertNull(op.snapshot().records.single().totalTokens)
    }
    @Test fun singleExecutionIntegrationDoesNotChangeSerializationOrUi() {
        val root=File(System.getProperty("projectRoot"),"app/src/main/java/io/github/xiaomeng2568/meldwise")
        val vm=File(root,"ui/MainViewModel.kt").readText()
        assertTrue(vm.contains("accounting.stream(UsageSlot.SINGLE,model) {provider.streamResponse(request)}"))
        assertTrue(vm.indexOf("container.chat.beginPrepared(turn,allowSharing)")<vm.indexOf("UsageOperationId(UsageMode.SINGLE"))
        assertTrue(vm.contains("accounting?.finish(UsageSlot.SINGLE"))
        val repository=File(root,"data/ChatRepository.kt").readText()
        assertFalse(repository.contains("RequestUsage"));assertFalse(repository.contains("RuntimeUsage"))
        val state=File(root,"ui/FoundationState.kt")
        if(state.exists()) assertFalse(state.readText().contains("UsageTotals"))
    }
}
