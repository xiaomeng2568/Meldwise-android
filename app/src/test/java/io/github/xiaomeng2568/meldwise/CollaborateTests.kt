// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CollaborateTests {
    private val a=ModelRef("chatgpt","a");private val b=ModelRef("deepseek","b")
    private val config=CollaborateConfig(CollaborateModel(a,"Original A"),CollaborateModel(b,"Original B"))
    private val hidden="SYNTHETIC_REASONING_NOT_CONTEXT"
    private fun repository(blob:MemoryBlob=MemoryBlob(),box:AesGcmBox=testBox()):ChatRepository=ChatRepository(blob,box).also {
        it.newCollaborate();it.configureCollaborate(config)
    }
    private fun provider(id:String,ready:Boolean=true,source:(LlmRequest)->Flow<LlmEvent>)=object:LlmProvider {
        override val id=id;override val displayName=id;override val capabilities=ProviderCapability(emptySet())
        override suspend fun listModels()=emptyList<LlmModel>()
        override suspend fun validateConnection()=if(ready) ProviderStatus.READY else ProviderStatus.DISCONNECTED
        override fun streamResponse(request:LlmRequest)=source(request)
    }
    private fun registry(source:(String,LlmRequest)->Flow<LlmEvent>)=ProviderRegistry(listOf(provider("chatgpt") {source("chatgpt",it)},provider("deepseek") {source("deepseek",it)}))
    private fun success(requests:MutableList<Pair<String,LlmRequest>> = mutableListOf())=registry {id,request ->flow {
        requests+=id to request
        emit(LlmEvent.ReasoningDelta(hidden,if(id=="chatgpt") ReasoningContent.Summary else ReasoningContent.ProviderVisibleReasoning))
        emit(LlmEvent.TextDelta(when(requests.size%3) {1->"INITIAL_VISIBLE";2->"REVIEW_VISIBLE";else->"FINAL_VISIBLE"}))
        emit(LlmEvent.Completed(null))
    }}
    private suspend fun run(r:ChatRepository,registry:ProviderRegistry=success(),text:String="question")=
        CollaborateExecutor(registry,r).execute(r.prepareCollaborate(text),true)
    private fun complete(r:ChatRepository,index:Int,text:String="answer$index",reasoning:String=hidden):Conversation {
        val round=r.activeConversation()!!.rounds.last()
        val s=r.startCollaborateStage(round.roundId,index).rounds.last().stages[index]
        val kind=if(s.model.ref.providerId=="chatgpt") ReasoningContent.Summary else ReasoningContent.ProviderVisibleReasoning
        return r.updateCollaborateStage(round.roundId,s.copy(output=text,state=CollaborateStageState.Complete,
            reasoning=ReasoningRecord(reasoning,kind,ReasoningPhase.Completed)))
    }
    @Test fun exactThreeRequestsInAtoBtoAOrder()=runBlocking {
        val requests=mutableListOf<Pair<String,LlmRequest>>();val r=repository();val c=run(r,success(requests))
        assertEquals(listOf(a,b,a),requests.map {ModelRef(it.first,it.second.model)})
        assertEquals(CollaborateRoundState.Complete,c.rounds.single().lifecycle)
        assertEquals(listOf(CollaborateStageType.INITIAL,CollaborateStageType.REVIEW,CollaborateStageType.SYNTHESIS),c.rounds.single().stages.map {it.type})
    }
    @Test fun stageTwoWaitsForVerifiedStageOneTerminal()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger();val r=repository()
        val reg=registry {_,_->flow {val count=calls.incrementAndGet();emit(LlmEvent.TextDelta("visible"));if(count==1) {entered.complete(Unit);release.await()};emit(LlmEvent.Completed(null))}}
        val task=async {run(r,reg)};withTimeout(5000) {entered.await()};assertEquals(1,calls.get());release.complete(Unit);task.await();assertEquals(3,calls.get())
    }
    @Test fun stageThreeWaitsForVerifiedReviewTerminal()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger();val r=repository()
        val reg=registry {_,_->flow {val count=calls.incrementAndGet();emit(LlmEvent.TextDelta("visible"));if(count==2) {entered.complete(Unit);release.await()};emit(LlmEvent.Completed(null))}}
        val task=async {run(r,reg)};withTimeout(5000) {entered.await()};assertEquals(2,calls.get());release.complete(Unit);task.await();assertEquals(3,calls.get())
    }
    @Test fun reviewReceivesInitialVisibleAnswer()=runBlocking {val requests=mutableListOf<Pair<String,LlmRequest>>();run(repository(),success(requests));assertTrue(requests[1].second.messages.any {it.text=="INITIAL_VISIBLE"})}
    @Test fun synthesisReceivesInitialAndReviewVisibleAnswers()=runBlocking {val requests=mutableListOf<Pair<String,LlmRequest>>();run(repository(),success(requests));assertTrue(requests[2].second.messages.any {it.text=="INITIAL_VISIBLE"});assertTrue(requests[2].second.messages.any {it.text=="REVIEW_VISIBLE"})}
    @Test fun reviewNeverReceivesReasoning()=runBlocking {val requests=mutableListOf<Pair<String,LlmRequest>>();run(repository(),success(requests));assertFalse(requests[1].second.messages.any {it.text.contains(hidden)})}
    @Test fun synthesisNeverReceivesReasoning()=runBlocking {val requests=mutableListOf<Pair<String,LlmRequest>>();run(repository(),success(requests));assertFalse(requests[2].second.messages.any {it.text.contains(hidden)})}
    @Test fun stagePromptsUseOnlyAcceptedRolesAndNoNewAdapterFields()=runBlocking {val requests=mutableListOf<Pair<String,LlmRequest>>();run(repository(),success(requests));requests.forEach {(_,q)->assertNull(q.instructions);assertTrue(q.messages.all {it.role in setOf(MessageRole.USER,MessageRole.ASSISTANT)});assertNull(q.temperature);assertNull(q.maxOutputTokens)}}
    @Test fun deterministicRolePromptsDoNotAskForHiddenReasoning() {assertFalse(ConversationContextBuilder.REVIEW_INSTRUCTION.contains("思维链"));assertTrue(ConversationContextBuilder.SYNTHESIS_INSTRUCTION.contains("独立核对"))}
    @Test fun stageSnapshotsSurviveFutureConfigurationChange()=runBlocking {val r=repository();run(r);r.configureCollaborate(CollaborateConfig(CollaborateModel(ModelRef("deepseek","next")),CollaborateModel(ModelRef("chatgpt","other"))));assertEquals(listOf(a,b,a),r.activeConversation()!!.rounds.single().stages.map {it.model.ref});assertEquals("Original A",r.activeConversation()!!.rounds.single().stages.first().model.displayName)}
    @Test fun sameProviderNeedsNoCrossProviderConsent() {val r=repository();r.configureCollaborate(CollaborateConfig(CollaborateModel(a),CollaborateModel(ModelRef("chatgpt","other"))));assertFalse(r.prepareCollaborate("q").requiresSharing)}
    @Test fun crossProviderRequiresExplicitConsent() {assertTrue(repository().prepareCollaborate("q").requiresSharing)}
    @Test fun refusedConfirmationHasZeroRequestsAndNoFakeRound()=runBlocking {val r=repository();val calls=AtomicInteger();val reg=registry {_,_->flow {calls.incrementAndGet()}};try {CollaborateExecutor(reg,r).execute(r.prepareCollaborate("q"));fail("required consent")} catch(_:ContextSharingRequired) {assertEquals(0,calls.get());assertNull(r.activeConversation());assertTrue(r.sessions().isEmpty())}}
    @Test fun consentStoredOnlyForExactConversationProviderSet()=runBlocking {val r=repository();run(r);assertEquals(setOf("chatgpt|deepseek"),r.activeConversation()!!.collaborateGrants);assertFalse(r.prepareCollaborate("next").requiresSharing);r.newCollaborate();r.configureCollaborate(config);assertTrue(r.prepareCollaborate("another").requiresSharing)}
    @Test fun changedProviderSetWithForeignPriorContextNeedsNewConsent()=runBlocking {val r=repository();run(r);r.configureCollaborate(CollaborateConfig(CollaborateModel(b),CollaborateModel(ModelRef("deepseek","other"))));assertTrue(r.prepareCollaborate("next").requiresSharing)}
    @Test fun sameProviderModelChangeDoesNotInvalidateProviderSetGrant()=runBlocking {val r=repository();run(r);r.configureCollaborate(config.copy(primary=CollaborateModel(ModelRef("chatgpt","next"))));assertFalse(r.prepareCollaborate("next").requiresSharing)}
    @Test fun changedModelInvalidatesPendingPlan() {val r=repository();val p=r.prepareCollaborate("q");r.configureCollaborate(config.copy(primary=CollaborateModel(ModelRef("chatgpt","next"))));assertThrows(IllegalArgumentException::class.java) {r.beginCollaborate(p,true)}}
    @Test fun consumedPlanCannotReplay() {val r=repository();val p=r.prepareCollaborate("q");r.beginCollaborate(p,true);assertThrows(IllegalArgumentException::class.java) {r.beginCollaborate(p,true)}}
    @Test fun newDraftInvalidatesPendingConsent() {val r=repository();val p=r.prepareCollaborate("q");r.newCollaborate();r.configureCollaborate(config);assertThrows(IllegalArgumentException::class.java) {r.beginCollaborate(p,true)}}
    @Test fun secondRoundIsSameConversationAndOneHistoryItem()=runBlocking {val r=repository();run(r);val id=r.conversationId();run(r,text="follow-up");assertEquals(id,r.conversationId());assertEquals(2,r.activeConversation()!!.rounds.size);assertEquals(1,r.sessions().size);assertEquals(ConversationMode.Collaborate,r.sessions().single().mode)}
    @Test fun futureRoundUsesSynthesisNotIntermediateStages()=runBlocking {val r=repository();run(r);val p=r.prepareCollaborate("next");val texts=p.context.messages.map {it.text};assertTrue(texts.contains("FINAL_VISIBLE"));assertFalse(texts.contains("INITIAL_VISIBLE"));assertFalse(texts.contains("REVIEW_VISIBLE"));assertFalse(texts.any {it.contains(hidden)})}
    @Test fun intermediateStagesRemainInLocalHistory()=runBlocking {val r=repository();val c=run(r);assertEquals("INITIAL_VISIBLE",c.rounds.single().stages.first().output);assertEquals(hidden,c.rounds.single().stages[1].reasoning.text)}
    @Test fun restartRestoresAllCompletedStagesAndAllowsNextRound()=runBlocking {val blob=MemoryBlob();val box=testBox();val r=repository(blob,box);run(r);val restored=ChatRepository(blob,box);restored.load();assertEquals(ConversationMode.Collaborate,restored.mode());assertEquals(3,restored.activeConversation()!!.rounds.single().stages.size);run(restored,text="next");assertEquals(2,restored.activeConversation()!!.rounds.size)}
    @Test fun historyReopenContinuesSameCollaborateConversation()=runBlocking {val r=repository();run(r);val id=r.conversationId();r.newSession(a);val single=r.begin("single").first;r.update(single,"single answer",MessageState.COMPLETED);r.activateSession(id);run(r,text="next");assertEquals(2,r.sessions().size);assertEquals(2,r.activeConversation()!!.rounds.size)}
    @Test fun emptyCollaborateDraftDoesNotPolluteHistory() {val r=repository();repeat(40) {r.newCollaborate();r.configureCollaborate(config)};assertTrue(r.sessions().isEmpty())}
    @Test fun recoverRunningReviewAsInterruptedAndKeepInitial() {val blob=MemoryBlob();val box=testBox();val r=repository(blob,box);val c=r.beginCollaborate(r.prepareCollaborate("q"),true);complete(r,0,"kept");val s=r.startCollaborateStage(c.rounds.single().roundId,1).rounds.last().stages[1];r.updateCollaborateStage(c.rounds.single().roundId,s.copy(output="partial",reasoning=ReasoningRecord(hidden,ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Streaming)));val restored=ChatRepository(blob,box);restored.load();val round=restored.activeConversation()!!.rounds.single();assertEquals(CollaborateRoundState.Interrupted,round.lifecycle);assertEquals("kept",round.stages[0].output);assertEquals("partial",round.stages[1].output);assertEquals(CollaborateStageState.Interrupted,round.stages[1].state);assertEquals(CollaborateStageState.NotRun,round.stages[2].state);assertEquals(ReasoningPhase.Interrupted,round.stages[1].reasoning.phase)}
    @Test fun noAutomaticReplayAfterRecovery() {val blob=MemoryBlob();val box=testBox();val r=repository(blob,box);r.beginCollaborate(r.prepareCollaborate("q"),true);val restored=ChatRepository(blob,box);restored.load();assertEquals(CollaborateRoundState.Interrupted,restored.activeConversation()!!.rounds.single().lifecycle);assertEquals(1,restored.sessions().size)}
    @Test fun interruptedPartialSynthesisNotOrdinaryNextTurnContext() {val blob=MemoryBlob();val box=testBox();val r=repository(blob,box);val round=r.beginCollaborate(r.prepareCollaborate("q"),true).rounds.single();complete(r,0);complete(r,1);val s=r.startCollaborateStage(round.roundId,2).rounds.last().stages[2];r.updateCollaborateStage(round.roundId,s.copy(output="INTERRUPTED_PARTIAL"));val restored=ChatRepository(blob,box);restored.load();assertEquals(listOf("next"),restored.prepareCollaborate("next").context.messages.map {it.text})}
    private fun failureAt(index:Int,kind:ErrorKind=ErrorKind.SERVER,throws:Boolean=false)=runBlocking {
        val calls=AtomicInteger();val r=repository();val reg=registry {_,_->flow {
            val current=calls.getAndIncrement();emit(LlmEvent.TextDelta("visible$current"));
            if(current==index) {if(throws) throw ProviderFailure(LlmError(kind)) else emit(LlmEvent.Failed(LlmError(kind,isRetryable=true)))}
            else emit(LlmEvent.Completed(null))
        }}
        val c=run(r,reg);val round=c.rounds.single();assertEquals(index+1,calls.get());assertEquals(CollaborateRoundState.Failed,round.lifecycle)
        assertEquals(kind,round.stages[index].error);assertEquals("visible$index",round.stages[index].output)
        round.stages.take(index).forEach {assertEquals(CollaborateStageState.Complete,it.state)}
        round.stages.drop(index+1).forEach {assertEquals(CollaborateStageState.NotRun,it.state)}
    }
    @Test fun initialFailureBlocksReview() {failureAt(0)}
    @Test fun reviewFailurePreservesInitial() {failureAt(1)}
    @Test fun synthesisFailurePreservesInitialAndReview() {failureAt(2)}
    @Test fun initialPlanLimitNoFallbackOrRetry() {failureAt(0,ErrorKind.PLAN_USAGE_LIMIT)}
    @Test fun reviewPlanLimitNoFallbackOrRetry() {failureAt(1,ErrorKind.PLAN_USAGE_LIMIT)}
    @Test fun synthesisPlanLimitNoFallbackOrRetry() {failureAt(2,ErrorKind.PLAN_USAGE_LIMIT)}
    @Test fun thrownProviderErrorRetainsFixedCategory() {failureAt(1,ErrorKind.MODEL_UNAVAILABLE,true)}
    @Test fun quotaLabelMentionsAllowanceNotNetworkOrInventedReset() {val s=collaborateErrorLabel(ErrorKind.PLAN_USAGE_LIMIT);assertTrue(s.contains("套餐或所选模型"));assertFalse(s.contains("网络"));assertFalse(s.contains("小时"))}
    private fun cancelAt(index:Int)=runBlocking {
        val entered=CompletableDeferred<Unit>();val calls=AtomicInteger();val r=repository()
        val reg=registry {_,_->flow {val current=calls.getAndIncrement();emit(LlmEvent.TextDelta("partial$current"));if(current==index) {entered.complete(Unit);awaitCancellation()} else emit(LlmEvent.Completed(null))}}
        val task=launch {run(r,reg)};withTimeout(5000) {entered.await()};task.cancelAndJoin()
        val round=r.activeConversation()!!.rounds.single();assertEquals(index+1,calls.get());assertEquals(CollaborateRoundState.Cancelled,round.lifecycle)
        assertEquals(CollaborateStageState.Cancelled,round.stages[index].state);assertEquals("partial$index",round.stages[index].output)
        assertTrue(round.stages.take(index).all {it.state==CollaborateStageState.Complete});assertTrue(round.stages.drop(index+1).all {it.state==CollaborateStageState.NotRun})
    }
    @Test fun cancelInitialBlocksAllDownstream() {cancelAt(0)}
    @Test fun cancelReviewKeepsInitial() {cancelAt(1)}
    @Test fun cancelSynthesisKeepsBothUpstream() {cancelAt(2)}
    @Test fun cancelAfterCompleteNeverRelabelsSuccess()=runBlocking {val r=repository();val c=run(r);assertEquals(CollaborateRoundState.Complete,r.cancelCollaborateRound(c.rounds.single().roundId).rounds.single().lifecycle)}
    @Test fun cancellingNewRoundLeavesPreviousRoundUntouched()=runBlocking {val r=repository();run(r);val old=r.activeConversation()!!.rounds.single();val c=r.beginCollaborate(r.prepareCollaborate("next"));r.cancelCollaborateRound(c.rounds.last().roundId);assertEquals(old,r.activeConversation()!!.rounds.first())}
    @Test fun partialEofDoesNotStartReview()=runBlocking {val calls=AtomicInteger();val r=repository();val c=run(r,registry {_,_->flow {calls.incrementAndGet();emit(LlmEvent.TextDelta("partial"))}});assertEquals(1,calls.get());assertEquals(CollaborateRoundState.Interrupted,c.rounds.single().lifecycle);assertEquals(ErrorKind.STREAM_INTERRUPTED,c.rounds.single().stages.first().error)}
    @Test fun incompleteTerminalDoesNotStartReview()=runBlocking {val calls=AtomicInteger();val c=run(repository(),registry {_,_->flow {calls.incrementAndGet();emit(LlmEvent.Incomplete(LlmError(ErrorKind.STREAM_INTERRUPTED)))}});assertEquals(1,calls.get());assertEquals(CollaborateRoundState.Interrupted,c.rounds.single().lifecycle)}
    @Test fun partialPlanLimitIsFailedNotNetworkInterruption()=runBlocking {val calls=AtomicInteger();val c=run(repository(),registry {_,_->flow {calls.incrementAndGet();emit(LlmEvent.TextDelta("partial"));emit(LlmEvent.Incomplete(LlmError(ErrorKind.PLAN_USAGE_LIMIT)))}});assertEquals(1,calls.get());assertEquals(CollaborateRoundState.Failed,c.rounds.single().lifecycle);assertEquals(ErrorKind.PLAN_USAGE_LIMIT,c.rounds.single().stages.first().error);assertEquals("partial",c.rounds.single().stages.first().output)}
    @Test fun blankCompletedOutputRejectedBeforeReview()=runBlocking {val calls=AtomicInteger();val c=run(repository(),registry {_,_->flow {calls.incrementAndGet();emit(LlmEvent.Completed(null))}});assertEquals(1,calls.get());assertEquals(ErrorKind.PROTOCOL,c.rounds.single().stages.first().error)}
    @Test fun firstTerminalFreezesStageEvenIfProviderWouldEmitMore()=runBlocking {val r=repository();val c=run(r,registry {_,_->flow {emit(LlmEvent.TextDelta("good"));emit(LlmEvent.Completed(null));emit(LlmEvent.TextDelta("bad"));emit(LlmEvent.Failed(LlmError(ErrorKind.SERVER)))}});assertTrue(c.rounds.single().stages.all {it.output=="good" && it.state==CollaborateStageState.Complete})}
    @Test fun rawChatGptReasoningFailsClosed()=runBlocking {val c=run(repository(),registry {_,_->flow {emit(LlmEvent.ReasoningDelta("raw",ReasoningContent.ProviderVisibleReasoning));emit(LlmEvent.TextDelta("answer"));emit(LlmEvent.Completed(null))}});assertEquals(ErrorKind.PROTOCOL,c.rounds.single().stages.first().error)}
    @Test fun noCredentialMeansNoProviderStreamOrFallback()=runBlocking {val calls=AtomicInteger();val p=provider("chatgpt",false) {flow {calls.incrementAndGet()}};val c=run(repository(),ProviderRegistry(listOf(p)));assertEquals(0,calls.get());assertEquals(ErrorKind.AUTHENTICATION,c.rounds.single().stages.first().error)}
    @Test fun explicitRoundRetryCreatesNewAttemptInSameHistory()=runBlocking {val r=repository();run(r,registry {_,_->flow {emit(LlmEvent.Failed(LlmError(ErrorKind.SERVER)))}});val old=r.activeConversation()!!.rounds.single();val plan=r.prepareCollaborateRetry(old.roundId);val requests=mutableListOf<Pair<String,LlmRequest>>();CollaborateExecutor(success(requests),r).execute(plan,true);val c=r.activeConversation()!!;assertEquals(2,c.rounds.size);assertEquals(old.roundId,c.rounds.last().retryOf);assertEquals(CollaborateRoundState.Failed,c.rounds.first().lifecycle);assertEquals(3,requests.size);assertEquals(1,r.sessions().size)}
    @Test fun retryUsesOriginalRefsNotLaterConfiguration()=runBlocking {val r=repository();run(r,registry {_,_->flow {emit(LlmEvent.Failed(LlmError(ErrorKind.SERVER)))}});val id=r.activeConversation()!!.rounds.single().roundId;r.configureCollaborate(config.copy(primary=CollaborateModel(ModelRef("chatgpt","new"))));assertEquals(a,r.prepareCollaborateRetry(id).config.primary.ref)}
    @Test fun completeRoundCannotBeRetriedAccidentally()=runBlocking<Unit> {val r=repository();run(r);assertThrows(IllegalArgumentException::class.java) {r.prepareCollaborateRetry(r.activeConversation()!!.rounds.single().roundId)}}
    @Test fun retryPlanInvalidatedByConfigurationChange()=runBlocking<Unit> {val r=repository();run(r,registry {_,_->flow {emit(LlmEvent.Failed(LlmError(ErrorKind.SERVER)))}});val p=r.prepareCollaborateRetry(r.activeConversation()!!.rounds.single().roundId);r.configureCollaborate(config.copy(primary=CollaborateModel(ModelRef("chatgpt","new"))));assertThrows(IllegalArgumentException::class.java) {r.beginCollaborate(p,true)}}
    @Test fun encryptedRoundTripNeverStoresPlainReasoningOrPrompt()=runBlocking {val blob=MemoryBlob();val box=testBox();val r=repository(blob,box);run(r,text="SYNTHETIC_PRIVATE_PROMPT");val bytes=String(blob.bytes!!,Charsets.ISO_8859_1);assertFalse(bytes.contains("SYNTHETIC_PRIVATE"));assertFalse(bytes.contains(hidden));val restored=ChatRepository(blob,box);restored.load();assertEquals(hidden,restored.activeConversation()!!.rounds.first().stages[1].reasoning.text)}
    @Test fun corruptCollaborateJournalFailsClosedAndRetainsCiphertext()=runBlocking {val blob=MemoryBlob();val box=testBox();run(repository(blob,box));blob.bytes!![20]=(blob.bytes!![20].toInt() xor 1).toByte();val before=blob.read();assertThrows(Exception::class.java) {ChatRepository(blob,box).load()};assertArrayEquals(before,blob.read())}
    @Test fun failedAdmissionWriteStartsZeroProviderCalls()=runBlocking {val calls=AtomicInteger();val memory=MemoryBlob();var fail=false;val box=testBox();val blob=object:AtomicBlob {override fun read()=memory.read();override fun write(value:ByteArray) {if(fail) error("SYNTHETIC_STORAGE_ERROR");memory.write(value)}};val r=ChatRepository(blob,box);r.newCollaborate();r.configureCollaborate(config);val old=memory.read();fail=true;try {run(r,registry {_,_->flow {calls.incrementAndGet()}});fail("write must fail")} catch(_:Exception) {assertEquals(0,calls.get());assertArrayEquals(old,memory.read())}}
    @Test fun stageCountPolicyHonorsTwoHundredRecordLimit() {val r=repository();repeat(50) {val c=r.beginCollaborate(r.prepareCollaborate("q$it"),true);r.cancelCollaborateRound(c.rounds.last().roundId)};assertThrows(IllegalArgumentException::class.java) {r.beginCollaborate(r.prepareCollaborate("overflow"),true)};assertEquals(50,r.activeConversation()!!.rounds.size)}
    @Test fun mixedSingleAndCollaborateCountSharesSameBound() {val r=ChatRepository(MemoryBlob(),testBox());r.activate(a);repeat(98) {val id=r.begin("q$it").first;r.update(id,"a",MessageState.COMPLETED)};r.newCollaborate();r.configureCollaborate(config);val round=r.beginCollaborate(r.prepareCollaborate("q"),true).rounds.last();r.cancelCollaborateRound(round.roundId);assertThrows(IllegalArgumentException::class.java) {r.beginCollaborate(r.prepareCollaborate("overflow"),true)};assertEquals(2,r.sessions().size)}
    @Test fun collaborateDoesNotBypassConversationCountLimit() {val r=repository();repeat(32) {r.newCollaborate();r.configureCollaborate(config);val c=r.beginCollaborate(r.prepareCollaborate("q$it"),true);r.cancelCollaborateRound(c.rounds.single().roundId)};r.newCollaborate();r.configureCollaborate(config);assertThrows(IllegalArgumentException::class.java) {r.beginCollaborate(r.prepareCollaborate("overflow"),true)};assertEquals(32,r.sessions().size)}
    @Test fun oversizedStageOutputRetainsLastValidRecord() {val r=repository();val c=r.beginCollaborate(r.prepareCollaborate("q"),true);val s=r.startCollaborateStage(c.rounds.last().roundId,0).rounds.last().stages[0];assertThrows(IllegalArgumentException::class.java) {r.updateCollaborateStage(c.rounds.last().roundId,s.copy(output="x".repeat(524289)))};assertEquals("",r.activeConversation()!!.rounds.last().stages[0].output)}
    @Test fun oversizedMandatoryStageInputFailsWithoutSilentTruncation() {val r=repository();r.beginCollaborate(r.prepareCollaborate("q"),true);complete(r,0,"中".repeat(200000));val round=r.activeConversation()!!.rounds.last();assertThrows(ProviderFailure::class.java) {ConversationContextBuilder().collaborateStageInput(round,1)}}
    @Test fun currentUserAndWholeUpstreamOutputAlwaysRetainedInStageInput() {val r=repository();r.beginCollaborate(r.prepareCollaborate("original"),true);complete(r,0,"initial");complete(r,1,"review");val input=ConversationContextBuilder().collaborateStageInput(r.activeConversation()!!.rounds.last(),2).map {it.text};assertTrue(input.containsAll(listOf("original","initial","review")))}
    @Test fun stageModelCannotBeMutatedMidRound() {val r=repository();val round=r.beginCollaborate(r.prepareCollaborate("q"),true).rounds.single();val s=r.startCollaborateStage(round.roundId,0).rounds.last().stages[0];assertThrows(IllegalArgumentException::class.java) {r.updateCollaborateStage(round.roundId,s.copy(model=CollaborateModel(ModelRef("chatgpt","wrong"))))}}
    @Test fun configCannotChangeDuringRunningRound() {val r=repository();r.beginCollaborate(r.prepareCollaborate("q"),true);assertThrows(IllegalArgumentException::class.java) {r.configureCollaborate(config)}}
    @Test fun downstreamCannotStartBeforeUpstreamComplete() {val r=repository();val round=r.beginCollaborate(r.prepareCollaborate("q"),true).rounds.single();assertThrows(IllegalArgumentException::class.java) {r.startCollaborateStage(round.roundId,1)};assertThrows(IllegalArgumentException::class.java) {r.startCollaborateStage(round.roundId,2)}}
    @Test fun completedStageCannotBeOverwritten() {val r=repository();val round=r.beginCollaborate(r.prepareCollaborate("q"),true).rounds.single();val c=complete(r,0);assertThrows(IllegalArgumentException::class.java) {r.updateCollaborateStage(round.roundId,c.rounds.single().stages.first().copy(output="replacement"))}}
    @Test fun manualOrderSurvivesCollaborateContinuation()=runBlocking {val r=repository();run(r,text="first");val first=r.conversationId();r.newCollaborate();r.configureCollaborate(config);run(r,text="second");r.moveSession(first,-1);r.activateSession(first);run(r,text="continued");assertEquals(first,r.sessions().first().id)}
    @Test fun deleteActiveCollaborateLeavesSafeEmptySingleDraft()=runBlocking {val r=repository();run(r);r.deleteSession(r.conversationId());assertTrue(r.load().isEmpty());assertEquals(ConversationMode.Single,r.mode());assertNull(r.activeConversation());assertTrue(r.sessions().isEmpty())}
    @Test fun singleStillHasRealMultiTurnAndNeverReadsCollaborateReasoning()=runBlocking {val r=repository();run(r);r.newSession(a);repeat(3) {val pair=r.begin("single$it");assertFalse(pair.second.any {it.text.contains(hidden)});r.update(pair.first,"visible",MessageState.COMPLETED)};assertEquals(6,r.load().size);assertEquals(2,r.sessions().size)}
    @Test fun collaborateAndCredentialBlobsAreIsolated()=runBlocking {val blob=MemoryBlob();val key=DeepSeekCredentials(blob,testBox("key"));key.replace("synthetic_key");val before=blob.read();run(repository());assertArrayEquals(before,blob.read())}
    @Test fun separateCompareHistorySurvivesCollaborateOperations()=runBlocking {val blob=MemoryBlob();val box=testBox();val store=CompareRepository(blob,box);store.upsert(CompareRun("cmp","q",CompareLaneRecord("A",a,state=CompareLaneState.Completed),CompareLaneRecord("B",b,state=CompareLaneState.Completed),CompareRunState.Completed));val before=blob.read();val r=repository();run(r);r.deleteSession(r.conversationId());assertArrayEquals(before,blob.read());assertEquals(CompareRunState.Completed,CompareRepository(blob,box).load().single().lifecycle)}
    @Test fun flatItemsContainOnePromptAndThreeOrderedStageOutputs()=runBlocking {val c=run(repository());val items=collaborateMessageItems(c);assertEquals(4,items.size);assertTrue(items.first() is CollaborateMessageItem.Prompt);assertEquals(listOf(0,1,2),items.filterIsInstance<CollaborateMessageItem.Stage>().map {it.stage.order});assertEquals(4,items.map {it.key}.distinct().size)}
    @Test fun stagePresentationStateMappingIsTruthful() {val s=CollaborateStage("s",CollaborateStageType.INITIAL,0,config.primary);assertEquals(LaneState.Pending,collaborateLaneState(s));assertEquals(LaneState.Interrupted,collaborateLaneState(s.copy(state=CollaborateStageState.Interrupted)));assertEquals(LaneState.Failed,collaborateLaneState(s.copy(state=CollaborateStageState.Failed)));assertEquals(LaneState.Completed,collaborateLaneState(s.copy(state=CollaborateStageState.Complete)))}
    @Test fun privateContentNotInDataToString()=runBlocking {val c=run(repository(),text="SYNTHETIC_PRIVATE");assertTrue(listOf(c,c.rounds.single(),c.rounds.single().stages.first(),c.rounds.single().frozenInput.first()).all {!it.toString().contains(hidden) && !it.toString().contains("SYNTHETIC_PRIVATE")})}
    @Test fun defaultAdditiveFieldsKeepExistingVersionThreeRecordsReadable() {
        val blob=MemoryBlob();val box=testBox();val single=ChatRepository(blob,box);single.activate(a)
        val id=single.begin("old").first;single.update(id,"old answer",MessageState.COMPLETED)
        val plain=box.open(blob.bytes!!);val root=Json.parseToJsonElement(utf8(plain)).jsonObject;plain.fill(0)
        val stripped=buildJsonObject {
            root.forEach {(k,v)->
                if(k=="conversations") put(k,JsonArray(v.jsonArray.map {row ->
                    JsonObject(row.jsonObject.filterKeys {it !in setOf("collaborate","rounds","collaborateGrants")})
                })) else put(k,v)
            }
        }
        blob.write(box.seal(stripped.toString().toByteArray()))
        val restored=ChatRepository(blob,box);assertEquals(2,restored.load().size)
        assertEquals(3,restored.activeConversation()!!.schemaVersion);assertEquals(ConversationMode.Single,restored.mode())
    }
    @Test fun cancellationDuringAtomicAdmissionProducesCancelledRoundAndZeroRequests()=runBlocking {
        val memory=MemoryBlob();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        var block=false;val blob=object:AtomicBlob {
            override fun read()=memory.read()
            override fun write(value:ByteArray) {if(block) {entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));block=false};memory.write(value)}
        }
        val r=ChatRepository(blob,testBox());r.newCollaborate();r.configureCollaborate(config);val p=r.prepareCollaborate("q");block=true
        val calls=AtomicInteger();val task=launch {CollaborateExecutor(registry {_,_->flow {calls.incrementAndGet()}},r).execute(p,true)}
        assertTrue(withContext(Dispatchers.IO) {entered.await(5,java.util.concurrent.TimeUnit.SECONDS)})
        task.cancel();release.countDown();task.join()
        assertEquals(0,calls.get());assertEquals(CollaborateRoundState.Cancelled,r.activeConversation()!!.rounds.single().lifecycle)
    }
    @Test fun eightMiBJournalBoundRetainsLastCiphertextAndAllCompletedOutputs() {
        val blob=MemoryBlob();val r=repository(blob);r.beginCollaborate(r.prepareCollaborate("first"),true)
        repeat(3) {complete(r,it,"中".repeat(500000),"")}
        r.beginCollaborate(r.prepareCollaborate("second"));complete(r,0,"中".repeat(500000),"");complete(r,1,"中".repeat(500000),"")
        val round=r.activeConversation()!!.rounds.last();val s=r.startCollaborateStage(round.roundId,2).rounds.last().stages[2]
        val before=blob.read();assertThrows(IllegalArgumentException::class.java) {r.updateCollaborateStage(round.roundId,s.copy(state=CollaborateStageState.Complete,output="中".repeat(500000)))}
        assertArrayEquals(before,blob.read());assertEquals(2,r.activeConversation()!!.rounds.size)
        assertEquals(CollaborateRoundState.Complete,r.activeConversation()!!.rounds.first().lifecycle)
        assertEquals(500000,r.activeConversation()!!.rounds.last().stages[1].output.length)
    }
    @Test fun stageBudgetTrimsOldPairsBeforeRequiredCurrentRoundOutputs() {
        val r=repository();r.beginCollaborate(r.prepareCollaborate("old"),true);complete(r,0);complete(r,1);complete(r,2,"OLD_FINAL_"+"x".repeat(100000))
        r.beginCollaborate(r.prepareCollaborate("current"));complete(r,0,"I".repeat(220000));complete(r,1,"R".repeat(220000))
        val input=ConversationContextBuilder().collaborateStageInput(r.activeConversation()!!.rounds.last(),2)
        assertFalse(input.any {it.text.startsWith("OLD_FINAL_")});assertTrue(input.any {it.text=="current"})
        assertEquals(220000,input.single {it.text.startsWith("III")}.text.length);assertEquals(220000,input.single {it.text.startsWith("RRR")}.text.length)
        assertTrue(input.sumOf {it.text.toByteArray(Charsets.UTF_8).size}<=ConversationContextBuilder.STAGE_INPUT_BYTES)
    }
    @Test fun oversizedVisibleInitialStopsBeforeSecondPost()=runBlocking {
        val calls=AtomicInteger();val c=run(repository(),registry {_,_->flow {calls.incrementAndGet();emit(LlmEvent.TextDelta("中".repeat(200000)));emit(LlmEvent.Completed(null))}})
        assertEquals(1,calls.get());assertEquals(CollaborateStageState.Complete,c.rounds.single().stages[0].state)
        assertEquals(ErrorKind.CONTEXT_OVERFLOW,c.rounds.single().stages[1].error);assertEquals(CollaborateStageState.NotRun,c.rounds.single().stages[2].state)
    }
}
