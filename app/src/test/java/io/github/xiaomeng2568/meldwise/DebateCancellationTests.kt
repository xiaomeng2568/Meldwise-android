// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebateCancellationTests {
    private suspend fun cancelled(h:DebateHarness,task:Job,completed:Set<DebateStageType> = emptySet(),requests:Int) {
        task.cancelAndJoin();h.scope.runCurrent();val r=h.round()
        assertEquals(DebateRoundState.Cancelled,r.lifecycle);assertEquals(requests,h.calls.size)
        assertTrue(h.calls.all {it.closed.isCompleted});assertTrue(DebateDag.readyStages(r).isEmpty())
        completed.forEach {assertEquals(DebateStageState.Complete,r.stage(it).state);assertEquals("visible-$it",r.stage(it).output)}
        assertTrue(r.stages.filter {it.type !in completed}.all {it.state in setOf(DebateStageState.Cancelled,DebateStageState.NotRun)})
    }
    @Test fun cancelBeforeInitialOutputClosesBoth()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();cancelled(h,task,requests=2)}
    @Test fun cancelAStreamingBWaiting()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).emit(LlmEvent.TextDelta("partial"));runCurrent();cancelled(h,task,requests=2);assertEquals("partial",h.round().stage(DebateStageType.INITIAL_A).output)}
    @Test fun cancelBStreamingAWaiting()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_B).emit(LlmEvent.TextDelta("partial"));runCurrent();cancelled(h,task,requests=2);assertEquals("partial",h.round().stage(DebateStageType.INITIAL_B).output)}
    @Test fun cancelACompleteBStreaming()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).complete();h.call(DebateStageType.INITIAL_B).emit(LlmEvent.TextDelta("partial"));runCurrent();cancelled(h,task,setOf(DebateStageType.INITIAL_A),2)}
    @Test fun cancelBCompleteAStreaming()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_B).complete();runCurrent();cancelled(h,task,setOf(DebateStageType.INITIAL_B),2)}
    @Test fun cancelBothReviewsStreaming()=runTest {val h=DebateHarness(this);val task=h.start();h.toReviews();DebateStageType.entries.slice(2..3).forEach {h.call(it).emit(LlmEvent.TextDelta("partial-$it"))};runCurrent();cancelled(h,task,DebateStageType.entries.take(2).toSet(),4)}
    @Test fun cancelReviewACompleteSiblingStreaming()=runTest {val h=DebateHarness(this);val task=h.start();h.toReviews();h.call(DebateStageType.REVIEW_A_OF_B).complete();runCurrent();cancelled(h,task,DebateStageType.entries.take(3).toSet(),4)}
    @Test fun cancelReviewBCompleteSiblingStreaming()=runTest {val h=DebateHarness(this);val task=h.start();h.toReviews();h.call(DebateStageType.REVIEW_B_OF_A).complete();runCurrent();cancelled(h,task,setOf(DebateStageType.INITIAL_A,DebateStageType.INITIAL_B,DebateStageType.REVIEW_B_OF_A),4)}
    @Test fun cancelJudgePartialKeepsFourUpstream()=runTest {val h=DebateHarness(this);val task=h.start();h.toJudge();h.call(DebateStageType.JUDGE).emit(LlmEvent.TextDelta("partial-final"));runCurrent();cancelled(h,task,DebateStageType.entries.take(4).toSet(),5);assertEquals("partial-final",h.round().stage(DebateStageType.JUDGE).output)}
    @Test fun cancelExactlyBetweenInitialWaveAndReviewAdmission()=runTest {
        val h=DebateHarness(this);var task:Deferred<Conversation>?=null
        task=h.start(onUpdate={c->if(c.debateRounds.last().stages.take(2).all {it.state==DebateStageState.Complete}) task!!.cancel()})
        h.toReviews();task.join();assertEquals(2,h.calls.size);assertEquals(DebateRoundState.Cancelled,h.round().lifecycle);assertTrue(h.round().stages.drop(2).all {it.state in setOf(DebateStageState.NotRun,DebateStageState.Cancelled)})
    }
    @Test fun cancelExactlyBetweenReviewWaveAndJudgeAdmission()=runTest {
        val h=DebateHarness(this);var task:Deferred<Conversation>?=null
        task=h.start(onUpdate={c->if(c.debateRounds.last().stages.take(4).all {it.state==DebateStageState.Complete}) task!!.cancel()})
        h.toJudge();task.join();assertEquals(4,h.calls.size);assertEquals(DebateRoundState.Cancelled,h.round().lifecycle);assertEquals(DebateStageState.Cancelled,h.round().stage(DebateStageType.JUDGE).state)
    }
    @Test fun cancellationAfterFinalDurableTerminalDoesNotRevokeSuccess()=runTest {val h=DebateHarness(this);var task:Deferred<Conversation>?=null;task=h.start(onUpdate={if(it.debateRounds.last().lifecycle==DebateRoundState.Complete) task!!.cancel()});h.finishAll();task.join();assertEquals(DebateRoundState.Complete,h.round().lifecycle)}
    @Test fun cancellationBeforeQueuedTerminalWinsNoRevival()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).complete();task.cancel();task.join();assertEquals(DebateStageState.Cancelled,h.round().stage(DebateStageType.INITIAL_A).state);assertEquals(2,h.calls.size)}
    @Test fun providerCancelledEventStopsSiblingAndNoReview()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).emit(LlmEvent.Cancelled);runCurrent();assertEquals(DebateRoundState.Cancelled,task.await().debateRounds.last().lifecycle);assertTrue(h.calls.all {it.closed.isCompleted});assertEquals(2,h.calls.size)}
    @Test fun eofIsInterruptedNotUserCancelled()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_B).eof();runCurrent();val r=task.await().debateRounds.last();assertEquals(DebateRoundState.Interrupted,r.lifecycle);assertEquals(ErrorKind.STREAM_INTERRUPTED,r.stage(DebateStageType.INITIAL_B).error);assertEquals(2,h.calls.size)}
    @Test fun incompleteInterruptedPreservesCompleteSibling()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).complete();runCurrent();h.call(DebateStageType.INITIAL_B).emit(LlmEvent.Incomplete(LlmError(ErrorKind.STREAM_INTERRUPTED)));runCurrent();val r=task.await().debateRounds.last();assertEquals(DebateStageState.Complete,r.stage(DebateStageType.INITIAL_A).state);assertEquals(DebateRoundState.Interrupted,r.lifecycle)}
    @Test fun earlierRoundsUntouchedByCancellation()=runTest {val h=DebateHarness(this);h.automatic={it.complete()};h.executor().execute(h.repository.prepareDebate("previous"),true);val before=Json.encodeToString(h.round());h.automatic=null;val task=h.start();runCurrent();task.cancelAndJoin();assertEquals(before,Json.encodeToString(h.repository.activeConversation()!!.debateRounds.first()))}
    @Test fun navigationDoesNotStealActiveExecutionOrCancelAnotherConversation()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();val cid=h.repository.conversationId();val rid=h.round().roundId;h.repository.newSession(DebateFixtures.a.ref);val draft=h.repository.conversationId();task.cancelAndJoin();assertEquals(draft,h.repository.conversationId());assertEquals(ConversationMode.Single,h.repository.mode());assertEquals(DebateRoundState.Cancelled,h.repository.debateSnapshot(cid,rid).debateRounds.last().lifecycle)}
    @Test fun cancelledReasoningStaysLocalAndInterrupted()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).emit(LlmEvent.ReasoningDelta("local-thought"));runCurrent();task.cancelAndJoin();val s=h.round().stage(DebateStageType.INITIAL_A);assertEquals("local-thought",s.reasoning.text);assertEquals(ReasoningPhase.Interrupted,s.reasoning.phase);assertEquals(2,h.calls.size)}
}
