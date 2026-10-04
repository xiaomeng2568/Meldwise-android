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
class DebateExecutorTests {
    @Test fun successExactlyFiveRequests()=runTest {val h=DebateHarness(this);val task=h.start();h.finishAll();assertEquals(DebateRoundState.Complete,task.await().debateRounds.single().lifecycle);assertEquals(5,h.calls.size)}
    @Test fun bothInitialsDispatchBeforeEitherCompletes()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();assertEquals(DebateStageType.entries.take(2).toSet(),h.calls.map {it.type}.toSet());assertEquals(2,h.round().stages.count {it.state==DebateStageState.Running});task.cancelAndJoin()}
    @Test fun bothReviewsDispatchBeforeEitherCompletes()=runTest {val h=DebateHarness(this);val task=h.start();h.toReviews();assertEquals(4,h.calls.size);assertTrue(h.round().stages.slice(2..3).all {it.state==DebateStageState.Running});task.cancelAndJoin()}
    @Test fun oneInitialCompleteNeverStartsReviews()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).complete();runCurrent();assertEquals(2,h.calls.size);assertEquals(DebateStageState.Complete,h.round().stage(DebateStageType.INITIAL_A).state);task.cancelAndJoin()}
    @Test fun oneReviewCompleteNeverStartsJudge()=runTest {val h=DebateHarness(this);val task=h.start();h.toReviews();h.call(DebateStageType.REVIEW_B_OF_A).complete();runCurrent();assertEquals(4,h.calls.size);task.cancelAndJoin()}
    @Test fun judgeRunsAloneAfterDurableReviews()=runTest {val h=DebateHarness(this);val task=h.start();h.toJudge();assertEquals(1,h.round().stages.count {it.state==DebateStageState.Running});assertTrue(h.calls.take(4).all {it.closed.isCompleted});task.cancelAndJoin()}
    private fun completionOrder(initial:List<DebateStageType>,review:List<DebateStageType>)=runTest {
        val h=DebateHarness(this);val task=h.start();runCurrent()
        initial.forEach {h.call(it).complete();runCurrent()};review.forEach {h.call(it).complete();runCurrent()}
        h.call(DebateStageType.JUDGE).complete();runCurrent()
        assertEquals(DebateRoundState.Complete,task.await().debateRounds.last().lifecycle)
        assertEquals(DebateStageType.entries.map {"visible-$it"},h.round().stages.map {it.output})
    }
    @Test fun completionOrderAThenB() {completionOrder(DebateStageType.entries.take(2),DebateStageType.entries.slice(2..3))}
    @Test fun completionOrderBThenA() {completionOrder(DebateStageType.entries.take(2).reversed(),DebateStageType.entries.slice(2..3))}
    @Test fun completionOrderReviewBThenA() {completionOrder(DebateStageType.entries.take(2),DebateStageType.entries.slice(2..3).reversed())}
    @Test fun completionOrderBothReversed() {completionOrder(DebateStageType.entries.take(2).reversed(),DebateStageType.entries.slice(2..3).reversed())}
    @Test fun frozenModelsAndPreferencesUsedInEveryRequest()=runTest {
        val h=DebateHarness(this);val task=h.start();h.finishAll();task.await()
        h.calls.forEach {val m=h.config.modelFor(it.type);assertEquals(m.ref.modelId,it.request.model);assertEquals(m.ref.providerId,it.provider);assertEquals(m.preference,it.request.reasoning);assertTrue(it.request.observeHttp);assertFalse(it.request.store)}
    }
    @Test fun visibleCandidatesReachBothReviews()=runTest {val h=DebateHarness(this);val task=h.start();h.toReviews();h.calls.drop(2).forEach {val text=it.request.messages.joinToString {m->m.text};assertTrue(text.contains("Answer A:\nvisible-INITIAL_A"));assertTrue(text.contains("Answer B:\nvisible-INITIAL_B"))};task.cancelAndJoin()}
    @Test fun visibleBothReviewsReachJudgeWithNeutralLabels()=runTest {val h=DebateHarness(this);val task=h.start();h.toJudge();val text=h.call(DebateStageType.JUDGE).request.messages.joinToString {it.text};assertTrue(text.contains("Review of Answer A:\nvisible-REVIEW_B_OF_A"));assertTrue(text.contains("Review of Answer B:\nvisible-REVIEW_A_OF_B"));task.cancelAndJoin()}
    @Test fun reasoningAndDiagnosticsNeverCrossStages()=runTest {
        val h=DebateHarness(this);h.automatic={c->c.emit(LlmEvent.HttpReady,LlmEvent.ReasoningDelta("PRIVATE_REASONING",if(c.provider=="chatgpt") ReasoningContent.Summary else ReasoningContent.ProviderVisibleReasoning),LlmEvent.RequestId("PRIVATE_REQUEST"),LlmEvent.ProviderMetadata("PRIVATE_BODY"));c.complete()}
        h.executor().execute(h.repository.prepareDebate("q"),true)
        h.calls.forEach {c->val text=c.request.messages.joinToString {it.text};listOf("PRIVATE_REASONING","PRIVATE_REQUEST","PRIVATE_BODY","PRIVATE_MODEL","PRIVATE_JUDGE","deepseek","chatgpt","processingDuration").forEach {assertFalse(text.contains(it))}}
        assertTrue(h.round().stages.all {it.reasoning.text=="PRIVATE_REASONING"})
    }
    @Test fun futureContextOnlySuccessfulJudge()=runTest {val h=DebateHarness(this);val task=h.start();h.finishAll();task.await();val input=h.repository.prepareDebate("follow-up").context.messages;assertEquals(listOf("CURRENT_QUESTION","visible-JUDGE","follow-up"),input.map {it.text})}
    @Test fun secondTurnOneConversationNewRound()=runTest {val h=DebateHarness(this);h.automatic={it.complete()};repeat(2) {h.executor().execute(h.repository.prepareDebate("q$it"),true)};assertEquals(1,h.repository.sessions().size);assertEquals(2,h.repository.activeConversation()!!.debateRounds.size);assertEquals(10,h.calls.size)}
    @Test fun reasoningOnlyDoesNotFreezeProcessingTime()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();val c=h.call(DebateStageType.INITIAL_A);c.emit(LlmEvent.HttpReady);runCurrent();testScheduler.advanceTimeBy(2000);c.emit(LlmEvent.ReasoningDelta("local"));runCurrent();testScheduler.advanceTimeBy(3000);c.complete();runCurrent();assertEquals(5L,h.round().stage(c.type).processingDuration);task.cancelAndJoin()}
    @Test fun emptyDeltaDoesNotCountAsFirstAnswer()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();val c=h.call(DebateStageType.INITIAL_A);c.emit(LlmEvent.HttpReady,LlmEvent.TextDelta(""));runCurrent();testScheduler.advanceTimeBy(2000);c.complete();runCurrent();assertEquals(2L,h.round().stage(c.type).processingDuration);task.cancelAndJoin()}
    @Test fun firstVisibleAnswerFreezesProcessingTime()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();val c=h.call(DebateStageType.INITIAL_A);c.emit(LlmEvent.HttpReady);runCurrent();testScheduler.advanceTimeBy(3000);c.emit(LlmEvent.TextDelta("first"));runCurrent();testScheduler.advanceTimeBy(9000);c.emit(LlmEvent.Completed(null));runCurrent();assertEquals(3L,h.round().stage(c.type).processingDuration);task.cancelAndJoin()}
    @Test fun noHttpReadyNoInventedDuration()=runTest {val h=DebateHarness(this);val task=h.start();h.finishAll();task.await();assertTrue(h.round().stages.all {it.processingDuration==null})}
    @Test fun timingIsBounded()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();val c=h.call(DebateStageType.INITIAL_A);c.emit(LlmEvent.HttpReady);runCurrent();testScheduler.advanceTimeBy(999999999);c.complete();runCurrent();assertEquals(604800L,h.round().stage(c.type).processingDuration);task.cancelAndJoin()}
    @Test fun completedRoundReloadNoWritesNoCalls()=runTest {val h=DebateHarness(this);val task=h.start();h.finishAll();task.await();val bytes=h.blob.read();val count=h.calls.size;val restored=ChatRepository(h.blob,h.box);restored.load();assertArrayEquals(bytes,h.blob.read());assertEquals(DebateRoundState.Complete,restored.activeConversation()!!.debateRounds.last().lifecycle);assertEquals(count,h.calls.size)}
    @Test fun interruptedRecoveryNeverStartsExecutor()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();val copy=MemoryBlob().also {it.write(h.blob.read()!!)};val restored=ChatRepository(copy,h.box);restored.load();assertEquals(DebateRoundState.Interrupted,restored.activeConversation()!!.debateRounds.last().lifecycle);assertEquals(2,h.calls.size);task.cancelAndJoin()}
    @Test fun wholeRoundRetryFiveNewRequestsOriginalSnapshots()=runTest {
        val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).fail(ErrorKind.NETWORK);runCurrent();task.await()
        val old=h.round();val bytes=Json.encodeToString(old)
        h.repository.configureDebate(DebateFixtures.same);val p=h.repository.prepareDebateRetry(old.roundId)
        h.automatic={it.complete()};h.executor().execute(p)
        val retry=h.round();assertEquals(old.roundId,retry.retryOf);assertEquals(old.config,retry.config);assertEquals(old.frozenInput,retry.frozenInput);assertEquals(old.inputRevision,retry.inputRevision);assertEquals(old.sourceProviders,retry.sourceProviders)
        assertEquals(7,h.calls.size);assertEquals(bytes,Json.encodeToString(h.repository.activeConversation()!!.debateRounds.first()))
    }
    @Test fun retryUsesExistingGrantNoNewConfirmation()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();task.cancelAndJoin();val p=h.repository.prepareDebateRetry(h.round().roundId);assertFalse(p.requiresSharing);h.automatic={it.complete()};h.executor().execute(p);assertEquals(DebateRoundState.Complete,h.round().lifecycle)}
    @Test fun restoredInterruptedRoundRetryIsNewAttempt()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();val copy=MemoryBlob().also {it.write(h.blob.read()!!)};task.cancelAndJoin();val r=ChatRepository(copy,h.box);r.load();val old=r.activeConversation()!!.debateRounds.last();h.automatic={it.complete()};DebateExecutor(h.registry,r,persistenceDispatcher=StandardTestDispatcher(testScheduler)).execute(r.prepareDebateRetry(old.roundId));assertEquals(2,r.activeConversation()!!.debateRounds.size);assertEquals(DebateRoundState.Interrupted,r.activeConversation()!!.debateRounds.first().lifecycle);assertEquals(7,h.calls.size)}
}
