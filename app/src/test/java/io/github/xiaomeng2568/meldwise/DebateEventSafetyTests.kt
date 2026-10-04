// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.AtomicBlob
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebateEventSafetyTests {
    private fun late(vararg events:LlmEvent)=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).emit(LlmEvent.TextDelta("FIRST"),LlmEvent.Completed(null),*events);runCurrent();val s=h.round().stage(DebateStageType.INITIAL_A);assertEquals(DebateStageState.Complete,s.state);assertEquals("FIRST",s.output);assertNull(s.error);task.cancelAndJoin()}
    @Test fun duplicateCompletedIgnored() {late(LlmEvent.Completed(null))}
    @Test fun failedAfterCompletedIgnored() {late(LlmEvent.Failed(LlmError(ErrorKind.PLAN_USAGE_LIMIT)))}
    @Test fun deltaAfterCompletedIgnored() {late(LlmEvent.TextDelta("LATE"))}
    @Test fun incompleteAfterCompletedIgnored() {late(LlmEvent.Incomplete(LlmError(ErrorKind.STREAM_INTERRUPTED)))}
    @Test fun cancelledAfterCompletedIgnored() {late(LlmEvent.Cancelled)}
    @Test fun reasoningAfterCompletedIgnored() {late(LlmEvent.ReasoningDelta("LATE_REASONING"))}
    @Test fun failedThenCompletedCannotRevive()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).emit(LlmEvent.Failed(LlmError(ErrorKind.NETWORK)),LlmEvent.TextDelta("LATE"),LlmEvent.Completed(null));runCurrent();task.await();assertEquals(DebateStageState.Failed,h.round().stage(DebateStageType.INITIAL_A).state);assertEquals("",h.round().stage(DebateStageType.INITIAL_A).output);assertEquals(2,h.calls.size)}
    @Test fun lateCallbackCannotReviveCancelledRound()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();task.cancelAndJoin();h.call(DebateStageType.INITIAL_A).complete();runCurrent();assertEquals(DebateStageState.Cancelled,h.round().stage(DebateStageType.INITIAL_A).state);assertEquals(2,h.calls.size)}
    @Test fun lateSiblingOutputAfterFailureIgnored()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).fail(ErrorKind.PROTOCOL);runCurrent();task.await();h.call(DebateStageType.INITIAL_B).complete();runCurrent();assertEquals(DebateStageState.Cancelled,h.round().stage(DebateStageType.INITIAL_B).state);assertEquals("",h.round().stage(DebateStageType.INITIAL_B).output)}
    @Test fun noBlankCompletedPermitsReviews()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_B).emit(LlmEvent.TextDelta(" \n"),LlmEvent.Completed(null));runCurrent();task.await();assertEquals(ErrorKind.PROTOCOL,h.round().stage(DebateStageType.INITIAL_B).error);assertEquals(2,h.calls.size)}
    private fun badReasoning(vararg events:LlmEvent)=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_B).emit(*events);runCurrent();task.await();assertEquals(ErrorKind.PROTOCOL,h.round().stage(DebateStageType.INITIAL_B).error);assertEquals(2,h.calls.size)}
    @Test fun chatHiddenReasoningRejected() {badReasoning(LlmEvent.ReasoningDelta("HIDDEN"))}
    @Test fun chatHiddenReasoningDoneRejected() {badReasoning(LlmEvent.ReasoningDone(ReasoningContent.ProviderVisibleReasoning))}
    @Test fun reasoningKindSwitchRejected() {badReasoning(LlmEvent.ReasoningDelta("summary",ReasoningContent.Summary),LlmEvent.ReasoningDelta("hidden"))}
    @Test fun reasoningOverflowRejected() {badReasoning(LlmEvent.ReasoningDelta("r".repeat(262145),ReasoningContent.Summary))}
    @Test fun visibleOutputOverflowRejectedNotTruncatedSuccess()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).emit(LlmEvent.TextDelta("x".repeat(524289)));runCurrent();task.await();assertEquals(ErrorKind.PROTOCOL,h.round().stage(DebateStageType.INITIAL_A).error);assertEquals("",h.round().stage(DebateStageType.INITIAL_A).output)}
    @Test fun exactOutputBoundAllowed()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).complete("x".repeat(524288));runCurrent();assertEquals(DebateStageState.Complete,h.round().stage(DebateStageType.INITIAL_A).state);task.cancelAndJoin()}
    @Test fun stageInputOverflowDispatchesNoReviews()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).complete("x".repeat(300000));h.call(DebateStageType.INITIAL_B).complete("y".repeat(300000));runCurrent();task.await();assertEquals(2,h.calls.size);assertEquals(DebateRoundState.Failed,h.round().lifecycle);assertTrue(h.round().stages.slice(2..3).any {it.error==ErrorKind.CONTEXT_OVERFLOW})}
    @Test fun throwingKnownTransportPreservesCategory()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).throwing(ProviderFailure(LlmError(ErrorKind.NETWORK)));runCurrent();task.await();assertEquals(ErrorKind.NETWORK,h.round().stage(DebateStageType.INITIAL_A).error);assertEquals(2,h.calls.size)}
    @Test fun unexpectedTransportExceptionSanitizedUnknown()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).throwing(IllegalStateException("PRIVATE_RAW_BODY"));runCurrent();task.await();assertEquals(ErrorKind.UNKNOWN,h.round().stage(DebateStageType.INITIAL_A).error);assertFalse(h.round().toString().contains("PRIVATE_RAW_BODY"))}
    @Test fun justInTimeReadinessLostStopsBeforeDispatch()=runTest {val h=DebateHarness(this);h.readiness={ref,n->ref.providerId!="chatgpt" || n==1};val task=h.start();runCurrent();task.await();assertEquals(ErrorKind.AUTHENTICATION,h.round().stage(DebateStageType.INITIAL_B).error);assertTrue(h.calls.none {it.type==DebateStageType.INITIAL_B});assertTrue(h.calls.size<=1)}
    @Test fun interleavedDeltasThrottledAndForcedAtTerminal()=runTest {var writes=0;var bytes:ByteArray?=null;val blob=object:AtomicBlob {override fun read()=bytes;override fun write(value:ByteArray){writes++;bytes=value.copyOf()}};val h=DebateHarness(this,blob=blob);val task=h.start();runCurrent();val before=writes;repeat(100) {h.call(DebateStageType.INITIAL_A).emit(LlmEvent.TextDelta("a"));h.call(DebateStageType.INITIAL_B).emit(LlmEvent.TextDelta("b"))};runCurrent();assertEquals(before,writes);h.call(DebateStageType.INITIAL_A).emit(LlmEvent.Completed(null));h.call(DebateStageType.INITIAL_B).emit(LlmEvent.Completed(null));runCurrent();assertEquals("a".repeat(100),h.round().stage(DebateStageType.INITIAL_A).output);assertEquals("b".repeat(100),h.round().stage(DebateStageType.INITIAL_B).output);assertTrue(writes<=before+3);task.cancelAndJoin()}
    @Test fun intermediateDiskFailureStopsAllRequestsStorage()=runTest {storageFailure(false,false)}
    @Test fun terminalDiskFailureNeverFabricatesComplete()=runTest {storageFailure(true,false)}
    @Test fun permanentDiskFailureLeavesLastValidRecoverableSnapshot()=runTest {storageFailure(true,true)}
    private suspend fun TestScope.storageFailure(terminal:Boolean,permanent:Boolean) {
        var bytes:ByteArray?=null;var fail=0;val blob=object:AtomicBlob {override fun read()=bytes;override fun write(value:ByteArray){if(fail>0){if(!permanent) fail--;throw java.io.IOException("SYNTHETIC_DISK")};bytes=value.copyOf()}}
        val h=DebateHarness(this,blob=blob);supervisorScope {
            val work=async {h.executor().execute(h.repository.prepareDebate("q"),true)};runCurrent()
            val c=h.call(DebateStageType.INITIAL_A);advanceTimeBy(201);c.emit(LlmEvent.TextDelta("DURABLE_PARTIAL"));runCurrent();fail=1
            if(terminal) c.emit(LlmEvent.Completed(null)) else {advanceTimeBy(201);c.emit(LlmEvent.TextDelta("UNSAVED"))}
            runCurrent();val failure=try {work.await();error("EXPECTED_STORAGE")} catch(f:ProviderFailure) {f};assertEquals(ErrorKind.STORAGE,failure.error.kind)
        }
        assertEquals(2,h.calls.size);assertTrue(h.calls.all {it.closed.isCompleted});assertEquals("DURABLE_PARTIAL",h.round().stage(DebateStageType.INITIAL_A).output)
        if(permanent) {val restored=ChatRepository(MemoryBlob().also {it.write(bytes!!)},h.box);restored.load();assertEquals(DebateRoundState.Interrupted,restored.activeConversation()!!.debateRounds.last().lifecycle)}
        else {assertEquals(DebateRoundState.Failed,h.round().lifecycle);assertEquals(ErrorKind.STORAGE,h.round().stage(DebateStageType.INITIAL_A).error)}
    }
}
