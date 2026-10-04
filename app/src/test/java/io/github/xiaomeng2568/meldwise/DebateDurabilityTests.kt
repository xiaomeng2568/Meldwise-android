// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.AtomicBlob
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebateDurabilityTests {
    private class HookBlob:AtomicBlob {
        var bytes:ByteArray?=null;var hook:(()->Unit)?=null
        override fun read()=bytes?.copyOf()
        override fun write(value:ByteArray) {hook?.invoke();bytes=value.copyOf()}
    }
    @Test fun cancellationDuringAtomicAdmissionStillSettlesAdmittedId()=runTest {
        val blob=HookBlob();val h=DebateHarness(this,blob=blob);var task:Deferred<Conversation>?=null
        blob.hook={blob.hook=null;task!!.cancel()};task=h.start();runCurrent();task.join()
        assertEquals(0,h.calls.size);assertEquals(1,h.repository.sessions().size);assertEquals(DebateRoundState.Cancelled,h.round().lifecycle)
        assertTrue(DebateDag.readyStages(h.round()).isEmpty())
    }
    @Test fun cancellationDuringInitialTerminalWritePreservesAcceptedSuccess()=runTest {
        val blob=HookBlob();val h=DebateHarness(this,blob=blob);val task=h.start();runCurrent()
        blob.hook={blob.hook=null;task.cancel()};h.call(DebateStageType.INITIAL_A).complete();runCurrent();task.join()
        assertEquals(DebateStageState.Complete,h.round().stage(DebateStageType.INITIAL_A).state);assertEquals(DebateRoundState.Cancelled,h.round().lifecycle);assertEquals(2,h.calls.size)
    }
    @Test fun everyRequestSeesDurableOwnRunningAndRequiredTerminals()=runTest {
        val h=DebateHarness(this);h.automatic={call->
            val plain=h.box.open(h.blob.read()!!)
            val round=try {Json.parseToJsonElement(String(plain,Charsets.UTF_8)).jsonObject["conversations"]!!.jsonArray.last().jsonObject["debateRounds"]!!.jsonArray.last().jsonObject} finally {plain.fill(0)}
            val stages=round["stages"]!!.jsonArray.associate {it.jsonObject["type"]!!.jsonPrimitive.content to it.jsonObject["state"]!!.jsonPrimitive.content}
            assertEquals("Running",stages.getValue(call.type.name))
            DebateDag.dependencies(call.type).forEach {assertEquals("Complete",stages.getValue(it.name))}
            call.complete()
        };h.executor().execute(h.repository.prepareDebate("q"),true);assertEquals(5,h.calls.size)
    }
    @Test fun competingRunningUpdateStopsAttemptWithoutOverwriteOrOrphan()=runTest {
        val h=DebateHarness(this);val task=h.start();runCurrent();val c=h.repository.activeConversation()!!;val s=c.debateRounds.last().stage(DebateStageType.INITIAL_A)
        h.repository.commitDebateStages(c.conversationId,c.debateRounds.last().roundId,listOf(DebateStageChange(s,s.copy(output="OTHER_DURABLE_WRITER"))))
        h.call(s.type).complete("STALE_OUTPUT");runCurrent();val result=task.await().debateRounds.last()
        assertEquals(DebateRoundState.Interrupted,result.lifecycle);assertEquals("OTHER_DURABLE_WRITER",result.stage(s.type).output);assertTrue(h.calls.all {it.closed.isCompleted});assertEquals(2,h.calls.size)
    }
    @Test fun externallyCancelledTerminalNotRevivedByExecutor()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();val c=h.repository.activeConversation()!!;h.repository.cancelDebateExecution(c.conversationId,c.debateRounds.last().roundId);h.call(DebateStageType.INITIAL_A).complete();runCurrent();assertEquals(DebateRoundState.Cancelled,task.await().debateRounds.last().lifecycle);assertEquals(2,h.calls.size)}
    @Test fun failurePersistenceWaitsUntilBothCollectorsHaveClosed()=runTest {
        val blob=HookBlob();val h=DebateHarness(this,blob=blob);val task=h.start();runCurrent()
        blob.hook={assertTrue(h.calls.all {it.closed.isCompleted})};h.call(DebateStageType.INITIAL_A).fail(ErrorKind.NETWORK);runCurrent();task.await();blob.hook=null
    }
    @Test fun simultaneousFailuresBothRetained()=runTest {val h=DebateHarness(this);val task=h.start();runCurrent();h.call(DebateStageType.INITIAL_A).fail(ErrorKind.PLAN_USAGE_LIMIT);h.call(DebateStageType.INITIAL_B).fail(ErrorKind.PROTOCOL);runCurrent();task.await();assertEquals(ErrorKind.PLAN_USAGE_LIMIT,h.round().stage(DebateStageType.INITIAL_A).error);assertEquals(ErrorKind.PROTOCOL,h.round().stage(DebateStageType.INITIAL_B).error);assertEquals(2,h.calls.size)}
    @Test fun crashBetweenWavesDoesNotStartNextWaveOnLoad()=runTest {
        val h=DebateHarness(this);var snapshot:ByteArray?=null
        val task=h.start(onUpdate={c->if(c.debateRounds.last().stages.take(2).all {it.state==DebateStageState.Complete} && c.debateRounds.last().stages[2].state==DebateStageState.Pending) snapshot=h.blob.read()})
        h.toReviews();val copy=MemoryBlob().also {it.write(requireNotNull(snapshot))};val restored=ChatRepository(copy,h.box);restored.load();assertEquals(DebateRoundState.Interrupted,restored.activeConversation()!!.debateRounds.last().lifecycle);assertTrue(restored.activeConversation()!!.debateRounds.last().stages.drop(2).all {it.state==DebateStageState.NotRun});task.cancelAndJoin()
    }
}
