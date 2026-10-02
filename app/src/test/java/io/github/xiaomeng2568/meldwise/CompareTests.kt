package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

class CompareTests {
    private val a=ModelRef("chatgpt","model-a");private val b=ModelRef("deepseek","model-b")
    private fun draft(x:ModelRef=a,y:ModelRef=b)=CompareRun("local-test","PRIVATE_PROMPT",CompareLaneRecord("A",x),CompareLaneRecord("B",y))
    private fun repo(blob:MemoryBlob=MemoryBlob())=CompareRepository(blob,testBox("compare-test"))
    private fun provider(id:String,source:(LlmRequest)->Flow<LlmEvent>)=object:LlmProvider {
        override val id=id;override val displayName=id;override val capabilities=ProviderCapability(emptySet())
        override suspend fun listModels()=emptyList<LlmModel>()
        override suspend fun validateConnection()=ProviderStatus.READY
        override fun streamResponse(request:LlmRequest)=source(request)
    }
    private fun state(a:CompareLaneState,b:CompareLaneState)=compareLifecycle(a,b)
    @Test fun bothCompleted() {assertEquals(CompareRunState.Completed,state(CompareLaneState.Completed,CompareLaneState.Completed))}
    @Test fun completedAndFailed() {assertEquals(CompareRunState.Partial,state(CompareLaneState.Completed,CompareLaneState.Failed))}
    @Test fun failedAndCompleted() {assertEquals(CompareRunState.Partial,state(CompareLaneState.Failed,CompareLaneState.Completed))}
    @Test fun completedAndCancelled() {assertEquals(CompareRunState.Partial,state(CompareLaneState.Completed,CompareLaneState.Cancelled))}
    @Test fun bothCancelled() {assertEquals(CompareRunState.Cancelled,state(CompareLaneState.Cancelled,CompareLaneState.Cancelled))}
    @Test fun bothFailed() {assertEquals(CompareRunState.Failed,state(CompareLaneState.Failed,CompareLaneState.Failed))}
    @Test fun incompleteDoesNotComplete() {assertEquals(CompareRunState.Partial,state(CompareLaneState.Incomplete,CompareLaneState.Incomplete))}
    @Test fun activeLaneKeepsRunning() {CompareLaneState.entries.filter {it.active}.forEach {assertEquals(CompareRunState.Running,state(it,CompareLaneState.Completed))}}
    @Test fun allActiveStatesRestoreIncomplete() {
        CompareLaneState.entries.filter {it.active}.forEach {s ->val r=restoreCompare(draft().withLanes(laneA=draft().laneA.copy(state=s)))
            assertEquals(CompareLaneState.Incomplete,r.laneA.state)}
    }
    @Test fun restorePreservesTerminalStates() {
        CompareLaneState.entries.filterNot {it.active}.forEach {s ->val r=restoreCompare(draft().withLanes(laneA=draft().laneA.copy(state=s)))
            assertEquals(s,r.laneA.state)}
    }
    @Test fun actualConcurrentStartWithOneAttemptEach()=runBlocking {
        val enteredA=CompletableDeferred<Unit>();val enteredB=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val callsA=AtomicInteger();val callsB=AtomicInteger()
        val pa=provider("chatgpt") {flow {callsA.incrementAndGet();enteredA.complete(Unit);release.await();emit(LlmEvent.TextDelta("A"));emit(LlmEvent.Completed(null))}}
        val pb=provider("deepseek") {flow {callsB.incrementAndGet();enteredB.complete(Unit);release.await();emit(LlmEvent.TextDelta("B"));emit(LlmEvent.Completed(null))}}
        val task=async {CompareExecutor(ProviderRegistry(listOf(pa,pb)),repo()).execute(draft()) {}}
        withTimeout(5000) {enteredA.await();enteredB.await()};assertFalse(task.isCompleted);assertEquals(1,callsA.get());assertEquals(1,callsB.get())
        release.complete(Unit);val result=withTimeout(5000) {task.await()}
        assertEquals(CompareRunState.Completed,result.lifecycle);assertEquals("A",result.laneA.output);assertEquals("B",result.laneB.output)
    }
    private fun isolatedFailure(failed:String)=runBlocking {
        val otherEntered=CompletableDeferred<Unit>();val failureObserved=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        fun p(id:String)=provider(id) {flow {
            calls.incrementAndGet()
            if(id==failed) {otherEntered.await();throw ProviderFailure(LlmError(ErrorKind.SERVER))}
            else {otherEntered.complete(Unit);release.await();emit(LlmEvent.TextDelta("kept"));emit(LlmEvent.Completed(null))}
        }}
        val task=async {CompareExecutor(ProviderRegistry(listOf(p("chatgpt"),p("deepseek"))),repo()).execute(draft()) {r ->
            if(r.laneA.state==CompareLaneState.Failed || r.laneB.state==CompareLaneState.Failed) failureObserved.complete(Unit)
        }}
        withTimeout(5000) {failureObserved.await()};assertFalse(task.isCompleted)
        release.complete(Unit);val r=withTimeout(5000) {task.await()};assertEquals(2,calls.get());assertEquals(CompareRunState.Partial,r.lifecycle)
        assertEquals(CompareLaneState.Completed,if(failed=="chatgpt") r.laneB.state else r.laneA.state)
    }
    @Test fun aFailureNeverCancelsB() {isolatedFailure("chatgpt")}
    @Test fun bFailureNeverCancelsA() {isolatedFailure("deepseek")}
    @Test fun noFallbackOrRetryAfterTwoFailures()=runBlocking {
        val requests=mutableListOf<ModelRef>()
        fun p(id:String)=provider(id) {r ->flow {requests+=ModelRef(id,r.model);emit(LlmEvent.Failed(LlmError(ErrorKind.SERVER,isRetryable=true)))}}
        val r=CompareExecutor(ProviderRegistry(listOf(p("chatgpt"),p("deepseek"))),repo()).execute(draft()) {}
        assertEquals(CompareRunState.Failed,r.lifecycle);assertEquals(setOf(a,b),requests.toSet());assertEquals(2,requests.size)
    }
    @Test fun cancelAllKeepsAlreadyCompletedLane()=runBlocking {
        val completed=CompletableDeferred<Unit>();val streaming=CompletableDeferred<Unit>();val store=repo();var last=draft()
        val pa=provider("chatgpt") {flow {emit(LlmEvent.TextDelta("done"));emit(LlmEvent.Completed(null));completed.complete(Unit)}}
        val pb=provider("deepseek") {flow {emit(LlmEvent.TextDelta("partial"));streaming.complete(Unit);awaitCancellation()}}
        val task=launch {CompareExecutor(ProviderRegistry(listOf(pa,pb)),store).execute(draft()) {last=it}}
        withTimeout(5000) {completed.await();streaming.await()};task.cancelAndJoin()
        assertEquals(CompareLaneState.Completed,last.laneA.state);assertEquals(CompareLaneState.Cancelled,last.laneB.state)
        assertEquals("partial",last.laneB.output);assertEquals(CompareRunState.Partial,last.lifecycle)
        assertEquals(CompareLaneState.Completed,store.load().single().laneA.state)
    }
    @Test fun cancelBothActiveLanes()=runBlocking {
        val entered=CompletableDeferred<Unit>();val n=AtomicInteger();var last=draft()
        fun p(id:String)=provider(id) {flow {if(n.incrementAndGet()==2) entered.complete(Unit);awaitCancellation()}}
        val task=launch {CompareExecutor(ProviderRegistry(listOf(p("chatgpt"),p("deepseek"))),repo()).execute(draft()) {last=it}}
        withTimeout(5000) {entered.await()};task.cancelAndJoin();assertEquals(CompareRunState.Cancelled,last.lifecycle)
    }
    @Test fun partialEofIsIncomplete()=runBlocking {
        fun p(id:String)=provider(id) {flow {emit(LlmEvent.TextDelta("partial"))}}
        val r=CompareExecutor(ProviderRegistry(listOf(p("chatgpt"),p("deepseek"))),repo()).execute(draft()) {}
        assertEquals(CompareLaneState.Incomplete,r.laneA.state);assertEquals(CompareRunState.Partial,r.lifecycle)
        assertEquals("partial",r.laneB.output)
    }
    @Test fun duplicateExactRefRejectedBeforeAttempt()=runBlocking {
        val count=AtomicInteger();val p=provider("chatgpt") {flow {count.incrementAndGet()}}
        try {CompareExecutor(ProviderRegistry(listOf(p)),repo()).execute(draft(a,a)) {};fail("expected rejection")}
        catch(_:IllegalArgumentException) {assertEquals(0,count.get())}
    }
    @Test fun sameProviderDifferentModelsAllowed()=runBlocking {
        val models=mutableListOf<String>();val p=provider("chatgpt") {r ->flow {models+=r.model;emit(LlmEvent.TextDelta("ok"));emit(LlmEvent.Completed(null))}}
        val r=CompareExecutor(ProviderRegistry(listOf(p)),repo()).execute(draft(a,ModelRef("chatgpt","other"))) {}
        assertEquals(CompareRunState.Completed,r.lifecycle);assertEquals(setOf("model-a","other"),models.toSet())
    }
    @Test fun encryptedRoundTripAndIdentity() {
        val blob=MemoryBlob();val box=testBox();val repo=CompareRepository(blob,box)
        val r=draft().withLanes(laneA=draft().laneA.copy(state=CompareLaneState.Completed,output="PRIVATE_ANSWER"),laneB=draft().laneB.copy(state=CompareLaneState.Completed),lifecycle=CompareRunState.Completed)
        repo.upsert(r);assertFalse(String(blob.bytes!!).contains("PRIVATE"))
        val loaded=CompareRepository(blob,box).load().single();assertEquals(a,loaded.laneA.modelRef);assertEquals(b,loaded.laneB.modelRef);assertEquals("PRIVATE_ANSWER",loaded.laneA.output)
    }
    @Test fun corruptionFailsClosed() {
        val blob=MemoryBlob();val box=testBox();val repo=CompareRepository(blob,box);repo.upsert(draft());blob.bytes!![20]=(blob.bytes!![20].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {CompareRepository(blob,box).load()}
    }
    @Test fun activeRestoreRetainsPartialTextWithoutProviderCalls() {
        val blob=MemoryBlob();val box=testBox();val repo=CompareRepository(blob,box)
        repo.upsert(draft().withLanes(laneA=draft().laneA.copy(state=CompareLaneState.Streaming,output="kept"),lifecycle=CompareRunState.Running))
        val r=CompareRepository(blob,box).load().single();assertEquals("kept",r.laneA.output);assertEquals(CompareLaneState.Incomplete,r.laneA.state)
    }
    @Test fun countBound() {val r=repo();repeat(16) {r.upsert(draft().copy(id="test-$it"))};assertThrows(IllegalArgumentException::class.java) {r.upsert(draft().copy(id="too-many"))};assertEquals(16,r.load().size)}
    @Test fun promptBound() {assertThrows(IllegalArgumentException::class.java) {repo().upsert(draft().copy(userPrompt=ComparePromptItem("prompt","x".repeat(32769))))}}
    @Test fun outputBound() {assertThrows(IllegalArgumentException::class.java) {repo().upsert(draft().withLanes(laneA=draft().laneA.copy(output="x".repeat(524289))))}}
    @Test fun reasoningBound() {assertThrows(IllegalArgumentException::class.java) {repo().upsert(draft().withLanes(laneB=draft().laneB.copy(reasoning=ReasoningRecord("x".repeat(262145)))))}}
    @Test fun unknownProviderRejected() {assertThrows(IllegalArgumentException::class.java) {repo().upsert(draft(x=ModelRef("other","m")))}}
    @Test fun rawOpenAiReasoningRejectedOnWrite() {assertThrows(IllegalArgumentException::class.java) {repo().upsert(draft().withLanes(laneA=draft().laneA.copy(reasoning=ReasoningRecord("secret",ReasoningContent.ProviderVisibleReasoning))))}}
    @Test fun closedToString() {val r=draft();listOf(r,r.laneA.copy(output="PRIVATE_OUTPUT",reasoning=ReasoningRecord("PRIVATE_REASONING"))).forEach {assertFalse(it.toString().contains("PRIVATE"))}}
    @Test fun interruptedReasoningRestoresTruthfully() {
        val r=restoreCompare(draft().withLanes(laneB=draft().laneB.copy(reasoning=ReasoningRecord("thought",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Streaming))))
        assertEquals(ReasoningPhase.Interrupted,r.laneB.reasoning.phase);assertEquals("thought",r.laneB.reasoning.text)
    }
    @Test fun completedReasoningSurvivesAnswerCancellation()=runBlocking {
        val emitted=CompletableDeferred<Unit>();var latest=draft()
        val pa=provider("chatgpt") {flow {emit(LlmEvent.TextDelta("done"));emit(LlmEvent.Completed(null))}}
        val pb=provider("deepseek") {flow {emit(LlmEvent.ReasoningDelta("visible"));emit(LlmEvent.ReasoningDone(ReasoningContent.ProviderVisibleReasoning));emit(LlmEvent.TextDelta("partial"));emitted.complete(Unit);awaitCancellation()}}
        val task=launch {CompareExecutor(ProviderRegistry(listOf(pa,pb)),repo()).execute(draft()) {latest=it}}
        withTimeout(5000) {emitted.await()};task.cancelAndJoin()
        assertEquals(CompareLaneState.Cancelled,latest.laneB.state);assertEquals(ReasoningPhase.Completed,latest.laneB.reasoning.phase)
    }
    @Test fun totalByteLimitRetainsLastValidRecord() {
        val r=repo();val completed=draft().withLanes(laneA=draft().laneA.copy(state=CompareLaneState.Completed,output="中".repeat(524288)),
            laneB=draft().laneB.copy(state=CompareLaneState.Completed,output="中".repeat(524288)),lifecycle=CompareRunState.Completed)
        r.upsert(completed.copy(id="first"));r.upsert(completed.copy(id="second"))
        assertThrows(IllegalArgumentException::class.java) {r.upsert(completed.copy(id="third"))};assertEquals(2,r.load().size)
    }
    @Test fun processingTimeStartsAtHttpAndStopsAtFirstAnswer()=runBlocking {
        var now=1000L
        val firstFinished=CompletableDeferred<Unit>()
        val p=provider("chatgpt") {request ->flow {
            if(request.model=="other") firstFinished.await()
            emit(LlmEvent.HttpReady);now+=3000;emit(LlmEvent.TextDelta("answer"));now+=5000;emit(LlmEvent.Completed(null))
            if(request.model=="model-a") firstFinished.complete(Unit)
        }}
        val r=CompareExecutor(ProviderRegistry(listOf(p)),repo()) {now}.execute(draft(a,ModelRef("chatgpt","other"))) {}
        assertEquals(3L,r.laneA.processingDuration);assertEquals(3L,r.laneB.processingDuration)
    }
    @Test fun noHttpMeansNoInventedProcessingTime()=runBlocking {
        fun p(id:String)=provider(id) {flow {emit(LlmEvent.TextDelta("text"));emit(LlmEvent.Completed(null))}}
        val r=CompareExecutor(ProviderRegistry(listOf(p("chatgpt"),p("deepseek"))),repo()).execute(draft()) {}
        assertNull(r.laneA.processingDuration);assertNull(r.laneB.processingDuration)
    }
    @Test fun storageFailureIsOwnedByExecutorAndDoesNotStartProvider()=runBlocking {
        val calls=AtomicInteger();val blob=object:io.github.xiaomeng2568.meldwise.security.AtomicBlob {
            override fun read():ByteArray?=null
            override fun write(value:ByteArray) {throw java.io.IOException("SYNTHETIC_WRITE_FAILURE")}
        }
        fun p(id:String)=provider(id) {flow {calls.incrementAndGet()}}
        try {CompareExecutor(ProviderRegistry(listOf(p("chatgpt"),p("deepseek"))),CompareRepository(blob,testBox())).execute(draft()) {};fail("expected failure")}
        catch(_:java.io.IOException) {assertEquals(0,calls.get())}
    }
    @Test fun negativeProcessingTimeRejected() {assertThrows(IllegalArgumentException::class.java) {repo().upsert(draft().withLanes(laneA=draft().laneA.copy(processingDuration=-1)))}}
}
