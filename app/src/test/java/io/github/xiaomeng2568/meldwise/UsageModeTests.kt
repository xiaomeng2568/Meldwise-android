// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.atomic.AtomicInteger

/** Real executors/repositories; synthetic transports only. No duplicate execution implementation. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UsageModeTests(private val mode:String,private val scenario:String,private val index:Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}: {1} {2}") fun cases():List<Array<Any>> = buildList {
            listOf("compare","collaborate","debate").forEach {mode ->
                add(arrayOf(mode,"success",-1));add(arrayOf(mode,"unavailable",-1));add(arrayOf(mode,"refused",-1))
                val stages=when(mode) {"compare"->2;"collaborate"->3;else->5}
                repeat(stages) {add(arrayOf(mode,"failed",it));add(arrayOf(mode,"cancelled",it))}
            }
        }
    }
    private fun provider(id:String,source:(LlmRequest)->Flow<LlmEvent>)=object:LlmProvider {
        override val id=id;override val displayName=id;override val capabilities=ProviderCapability(emptySet())
        override suspend fun validateConnection()=ProviderStatus.READY
        override suspend fun listModels()=emptyList<LlmModel>()
        override fun streamResponse(request:LlmRequest)=source(request)
    }
    @Test fun requestCountTargetOutcomeAndUsage() {
        when(mode) {"compare"->compare();"collaborate"->collaborate();else->debate()}
    }
    private fun compare()=runBlocking {
        val owner=RuntimeUsage();val reached=CompletableDeferred<Unit>();val completedA=CompletableDeferred<Unit>();val count=AtomicInteger()
        val registry=ProviderRegistry(listOf("chatgpt","deepseek").mapIndexed {lane,id ->provider(id) {flow {
            val dispatched=count.incrementAndGet()
            if(scenario=="cancelled") {
                if(dispatched==2) reached.complete(Unit)
                if(index==0 || lane==1) awaitCancellation()
            }
            emit(LlmEvent.UsageFinal(if(scenario=="unavailable" && lane==1) Usage(null,null,null) else Usage(lane+1L,null,null)))
            if(scenario=="failed" && lane==index) emit(LlmEvent.Failed(LlmError(ErrorKind.PLAN_USAGE_LIMIT)))
            else {emit(LlmEvent.TextDelta("answer"));emit(LlmEvent.Completed(null))}
        }}})
        val a=ModelRef("chatgpt","a");val b=if(scenario=="refused") a else ModelRef("deepseek","b")
        val draft=CompareRun("compare-attempt","prompt",CompareLaneRecord("A",a),CompareLaneRecord("B",b))
        val executor=CompareExecutor(registry,CompareRepository(MemoryBlob(),testBox()),usage=owner)
        if(scenario=="refused") {
            try {executor.execute(draft) {};fail()} catch(_:IllegalArgumentException) {}
            assertNull(owner.snapshot());assertEquals(0,count.get());return@runBlocking
        }
        val task=launch {executor.execute(draft) {if(it.laneA.state==CompareLaneState.Completed) completedA.complete(Unit)}}
        if(scenario=="cancelled") {reached.await();if(index==1) completedA.await();task.cancelAndJoin()} else task.join()
        val records=owner.snapshot()!!.records;assertEquals(2,records.size);assertEquals(2,count.get())
        assertEquals(listOf(a,b),records.map {it.modelRef});assertEquals(listOf(1,2),records.map {it.requestOrdinal})
        if(scenario=="failed") assertEquals(RequestOutcome.FAILED,records[index].outcome)
        if(scenario=="cancelled") {
            assertEquals(RequestOutcome.CANCELLED,records[1].outcome)
            assertEquals(if(index==0) RequestOutcome.CANCELLED else RequestOutcome.COMPLETED,records[0].outcome)
        }
        if(scenario=="unavailable") assertEquals(UsageSource.UNAVAILABLE,records[1].source)
        if(scenario=="success") assertEquals(listOf(1L,2L),records.map {it.inputTokens})
    }
    private fun collaborate()=runBlocking {
        val owner=RuntimeUsage();val reached=CompletableDeferred<Unit>();val count=AtomicInteger()
        val registry=ProviderRegistry(listOf("chatgpt","deepseek").map {id ->provider(id) {flow {
            val n=count.getAndIncrement()
            if(scenario=="cancelled" && n==index) {reached.complete(Unit);awaitCancellation()}
            emit(LlmEvent.UsageFinal(if(scenario=="unavailable") Usage(null,null,null) else Usage(n+1L,null,10+n.toLong())))
            if(scenario=="failed" && n==index) emit(LlmEvent.Failed(LlmError(ErrorKind.PLAN_USAGE_LIMIT)))
            else {emit(LlmEvent.TextDelta("answer"));emit(LlmEvent.Completed(null))}
        }}})
        val config=CollaborateConfig(CollaborateModel(ModelRef("chatgpt","a"),"A"),CollaborateModel(ModelRef("deepseek","b"),"B"))
        val repo=ChatRepository(MemoryBlob(),testBox()).also {it.newCollaborate();it.configureCollaborate(config)}
        val executor=CollaborateExecutor(registry,repo,usage=owner)
        if(scenario=="refused") {
            try {executor.execute(repo.prepareCollaborate("prompt"));fail()} catch(_:ContextSharingRequired) {}
            assertNull(owner.snapshot());assertEquals(0,count.get());assertNull(repo.activeConversation());return@runBlocking
        }
        val task=launch {executor.execute(repo.prepareCollaborate("prompt"),true)}
        if(scenario=="cancelled") {reached.await();task.cancelAndJoin()} else task.join()
        val snapshot=owner.snapshot()!!;val records=snapshot.records
        val expected=if(index>=0) index+1 else 3
        assertEquals(expected,count.get());assertEquals(expected,records.size)
        val round=repo.activeConversation()!!.rounds.single()
        assertEquals(round.roundId,snapshot.operation.operationId)
        records.forEachIndexed {i,r ->assertEquals(round.stages[i].stageId,r.stageId);assertEquals(round.stages[i].model.ref,r.modelRef)}
        if(scenario=="failed") assertEquals(RequestOutcome.FAILED,records.last().outcome)
        if(scenario=="cancelled") assertEquals(RequestOutcome.CANCELLED,records.last().outcome)
        if(scenario=="unavailable") assertTrue(records.all {it.source==UsageSource.UNAVAILABLE})
    }
    private fun debate()=runTest {
        val owner=RuntimeUsage();val h=DebateHarness(this);val target=DebateStageType.entries.getOrNull(index)
        h.automatic={call ->
            if(!(scenario=="cancelled" && call.type==target)) {
                call.emit(LlmEvent.UsageFinal(if(scenario=="unavailable") Usage(null,null,null) else Usage(call.type.ordinal+1L,null,null)))
                if(scenario=="failed" && call.type==target) call.fail(ErrorKind.PLAN_USAGE_LIMIT) else call.complete()
            }
        }
        val executor=DebateExecutor(h.registry,h.repository,monotonic={testScheduler.currentTime},
            persistenceDispatcher=StandardTestDispatcher(testScheduler),usage=owner)
        if(scenario=="refused") {
            try {executor.execute(h.repository.prepareDebate("prompt"));fail()} catch(_:ContextSharingRequired) {}
            assertNull(owner.snapshot());assertEquals(0,h.calls.size);assertNull(h.repository.activeConversation());return@runTest
        }
        val task=async {executor.execute(h.repository.prepareDebate("prompt"),true)}
        runCurrent()
        if(scenario=="cancelled") {task.cancel();runCurrent();task.join()} else task.await()
        val expected=if(index<0) 5 else when(index) {0,1->2;2,3->4;else->5}
        val snapshot=owner.snapshot()!!;val round=h.round()
        assertEquals(expected,snapshot.requestCount);assertEquals(expected,h.calls.size)
        assertEquals(round.roundId,snapshot.operation.operationId)
        snapshot.records.forEach {r ->val stage=round.stages.first {it.stageId==r.stageId};assertEquals(stage.model.ref,r.modelRef)}
        if(scenario=="failed") assertEquals(RequestOutcome.FAILED,snapshot.records.first {it.slot==UsageSlot.debate(target!!)}.outcome)
        if(scenario=="cancelled") assertEquals(RequestOutcome.CANCELLED,snapshot.records.first {it.slot==UsageSlot.debate(target!!)}.outcome)
        if(scenario=="success") assertTrue(snapshot.records.all {it.outcome==RequestOutcome.COMPLETED})
        if(scenario=="unavailable") assertTrue(snapshot.records.all {it.source==UsageSource.UNAVAILABLE})
    }
}
