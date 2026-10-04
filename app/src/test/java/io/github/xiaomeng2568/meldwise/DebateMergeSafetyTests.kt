// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

class DebateMergeSafetyTests {
    private fun repository()=ChatRepository(MemoryBlob(),testBox(),clock={10}).also {it.newDebate();it.configureDebate(DebateFixtures.same)}
    private fun admitted(r:ChatRepository):Conversation {val c=r.beginDebate(r.prepareDebate("q"));return r.startDebateWave(c.conversationId,c.debateRounds.last().roundId,DebateStageType.entries.take(2))}
    private fun commit(r:ChatRepository,c:Conversation,s:DebateStage,next:DebateStage)=r.commitDebateStages(c.conversationId,c.debateRounds.last().roundId,listOf(DebateStageChange(s,next)))
    @Test fun siblingStaleRoundSnapshotMergesIntoCurrentState() {val r=repository();val c=admitted(r);val a=c.debateRounds.last().stage(DebateStageType.INITIAL_A);val b=c.debateRounds.last().stage(DebateStageType.INITIAL_B);commit(r,c,a,a.copy(output="A"));val after=commit(r,c,b,b.copy(output="B"))!!;assertEquals("A",after.debateRounds.last().stage(a.type).output);assertEquals("B",after.debateRounds.last().stage(b.type).output)}
    @Test fun sameStageOldRunningTokenRejected() {val r=repository();val c=admitted(r);val a=c.debateRounds.last().stages.first();commit(r,c,a,a.copy(output="current"));assertNull(commit(r,c,a,a.copy(output="old")));assertEquals("current",r.activeConversation()!!.debateRounds.last().stages.first().output)}
    @Test fun structurallyEqualCopyIsNotExactSnapshot() {val r=repository();val c=admitted(r);val a=c.debateRounds.last().stages.first();assertNull(commit(r,c,a.copy(),a.copy(output="forged")))}
    @Test fun batchWithOneStaleTokenWritesNothing() {val r=repository();val c=admitted(r);val a=c.debateRounds.last().stages[0];val b=c.debateRounds.last().stages[1];commit(r,c,a,a.copy(output="A"));assertNull(r.commitDebateStages(c.conversationId,c.debateRounds.last().roundId,listOf(DebateStageChange(a,a.copy(output="stale")),DebateStageChange(b,b.copy(output="B")))));assertEquals("",r.activeConversation()!!.debateRounds.last().stages[1].output)}
    @Test fun simultaneousThreadWritesNeverLoseSiblingDeltas()=runBlocking {
        val r=repository();val c=admitted(r);val rid=c.debateRounds.last().roundId;val barrier=CyclicBarrier(2)
        DebateStageType.entries.take(2).map {type->async(Dispatchers.Default) {
            repeat(150) {
                val s=r.debateSnapshot(c.conversationId,rid).debateRounds.last().stage(type)
                barrier.await(5,TimeUnit.SECONDS)
                assertNotNull(commit(r,c,s,s.copy(output=s.output+type.name.last())))
                barrier.await(5,TimeUnit.SECONDS)
            }
        }}.awaitAll()
        val last=r.debateSnapshot(c.conversationId,rid).debateRounds.last()
        assertEquals("A".repeat(150),last.stage(DebateStageType.INITIAL_A).output);assertEquals("B".repeat(150),last.stage(DebateStageType.INITIAL_B).output)
    }
    private fun terminal(state:DebateStageState,nextState:DebateStageState) {
        val r=repository();val c=admitted(r);val a=c.debateRounds.last().stages.first()
        val next=a.copy(output="first",state=state,error=if(state==DebateStageState.Complete) null else ErrorKind.PROTOCOL,endedAt=10)
        assertNotNull(commit(r,c,a,next));assertNull(commit(r,c,a,next.copy(state=nextState,output="late")))
        assertNull(commit(r,c,next,next.copy(state=nextState,output="late")))
        assertEquals(state,r.activeConversation()!!.debateRounds.last().stages.first().state)
    }
    @Test fun completeThenRunningRejected() {terminal(DebateStageState.Complete,DebateStageState.Running)}
    @Test fun completeThenFailedRejected() {terminal(DebateStageState.Complete,DebateStageState.Failed)}
    @Test fun completeThenIncompleteRejected() {terminal(DebateStageState.Complete,DebateStageState.Interrupted)}
    @Test fun failedThenCancelledRejected() {terminal(DebateStageState.Failed,DebateStageState.Cancelled)}
    @Test fun cancelledThenCompleteRejected() {terminal(DebateStageState.Cancelled,DebateStageState.Complete)}
    @Test fun interruptedThenCompleteRejected() {terminal(DebateStageState.Interrupted,DebateStageState.Complete)}
    @Test fun bothCompletedBatchIsAtomic() {val r=repository();val c=admitted(r);val pair=c.debateRounds.last().stages.take(2);val next=r.commitDebateStages(c.conversationId,c.debateRounds.last().roundId,pair.map {DebateStageChange(it,it.copy(state=DebateStageState.Complete,output="visible",endedAt=10))})!!;assertTrue(next.debateRounds.last().stages.take(2).all {it.state==DebateStageState.Complete})}
    @Test fun twoFailuresBatchPreservesBothCategories() {val r=repository();val c=admitted(r);val pair=c.debateRounds.last().stages.take(2);val next=r.commitDebateStages(c.conversationId,c.debateRounds.last().roundId,pair.map {DebateStageChange(it,it.copy(state=DebateStageState.Failed,error=ErrorKind.PLAN_USAGE_LIMIT,endedAt=10))})!!;assertEquals(2,next.debateRounds.last().stages.count {it.state==DebateStageState.Failed})}
    @Test fun interruptedSettlementWithRunningSiblingRemainsValid() {val r=repository();val c=admitted(r);val a=c.debateRounds.last().stages.first();val next=commit(r,c,a,a.copy(state=DebateStageState.Interrupted,error=ErrorKind.STREAM_INTERRUPTED,endedAt=10))!!;validateDebate(next);assertEquals(DebateRoundState.Interrupted,next.debateRounds.last().lifecycle);assertTrue(next.debateRounds.last().stages.take(2).all {it.state==DebateStageState.Interrupted})}
    @Test fun wrongModelCannotRelabelStage() {val r=repository();val c=admitted(r);val a=c.debateRounds.last().stages.first();assertThrows(IllegalArgumentException::class.java) {commit(r,c,a,a.copy(model=DebateFixtures.judge))}}
    @Test fun wrongSemanticStageRejected() {val r=repository();val c=admitted(r);val a=c.debateRounds.last().stages.first();assertThrows(IllegalArgumentException::class.java) {commit(r,c,a,a.copy(type=DebateStageType.JUDGE))}}
    @Test fun wrongStartSnapshotRejected() {val r=repository();val c=admitted(r);val a=c.debateRounds.last().stages.first();assertThrows(IllegalArgumentException::class.java) {commit(r,c,a,a.copy(startedAt=11))}}
    @Test fun outputRegressionRejected() {val r=repository();val c=admitted(r);val a=c.debateRounds.last().stages.first();val next=commit(r,c,a,a.copy(output="prior"))!!;val s=next.debateRounds.last().stage(a.type);assertThrows(IllegalArgumentException::class.java) {commit(r,next,s,s.copy(output="p"))}}
    @Test fun pendingTerminalWriteRejected() {val r=repository();val c=r.beginDebate(r.prepareDebate("q"));val pending=c.debateRounds.last().stages.first();assertNull(commit(r,c,pending,pending.copy(state=DebateStageState.Complete,output="not run")))}
    @Test fun duplicateChangesRejected() {val r=repository();val c=admitted(r);val a=c.debateRounds.last().stages.first();val change=DebateStageChange(a,a.copy(output="x"));assertThrows(IllegalArgumentException::class.java) {r.commitDebateStages(c.conversationId,c.debateRounds.last().roundId,listOf(change,change))}}
    @Test fun cancelledRoundRejectsLateDelta() {val r=repository();val c=admitted(r);val a=c.debateRounds.last().stages.first();r.cancelDebateExecution(c.conversationId,c.debateRounds.last().roundId);assertNull(commit(r,c,a,a.copy(output="late")))}
    @Test fun waveCannotStartFromInMemoryCompletionOnly() {val r=repository();val c=admitted(r);assertThrows(IllegalArgumentException::class.java) {r.startDebateWave(c.conversationId,c.debateRounds.last().roundId,DebateStageType.entries.slice(2..3))}}
    @Test fun waveRejectsNonFixedExtraOrPartialSet() {val r=repository();val c=r.beginDebate(r.prepareDebate("q"));assertThrows(IllegalArgumentException::class.java) {r.startDebateWave(c.conversationId,c.debateRounds.last().roundId,listOf(DebateStageType.INITIAL_A))}}
    @Test fun updateTargetsAdmittedConversationNotCurrentDraft() {val r=repository();val c=admitted(r);val a=c.debateRounds.last().stages.first();r.newSession(DebateFixtures.a.ref);val draft=r.conversationId();commit(r,c,a,a.copy(output="kept"));assertEquals(draft,r.conversationId());assertNull(r.activeConversation())}
}
