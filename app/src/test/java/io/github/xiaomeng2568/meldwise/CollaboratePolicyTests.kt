// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CollaboratePolicyTests {
    private val box=testBox()
    private val a=CollaborateModel(ModelRef("chatgpt","a"),"Original A")
    private val b=CollaborateModel(ModelRef("deepseek","b"),"Original B",ReasoningPreference.High)
    private fun repo(config:CollaborateConfig=CollaborateConfig(a,b),blob:MemoryBlob=MemoryBlob())=ChatRepository(blob,box).also {it.newCollaborate();it.configureCollaborate(config)}
    private fun provider(id:String,requests:MutableList<LlmRequest>,failAt:Int?=null)=object:LlmProvider {
        override val id=id;override val displayName=id;override val capabilities=ProviderCapability(emptySet())
        override suspend fun listModels()=emptyList<LlmModel>()
        override suspend fun validateConnection()=ProviderStatus.READY
        override fun streamResponse(request:LlmRequest)=flow {
            requests+=request
            emit(LlmEvent.ReasoningDelta("NEVER_DOWNSTREAM_REASONING",if(id=="chatgpt") ReasoningContent.Summary else ReasoningContent.ProviderVisibleReasoning))
            if(requests.size==failAt) emit(LlmEvent.Failed(LlmError(ErrorKind.PLAN_USAGE_LIMIT)))
            else {emit(LlmEvent.TextDelta("VISIBLE_${requests.size}"));emit(LlmEvent.Completed(null))}
        }
    }
    private suspend fun run(r:ChatRepository,requests:MutableList<LlmRequest> = mutableListOf(),failAt:Int?=null,retry:String?=null):Conversation {
        val registry=ProviderRegistry(listOf(provider("chatgpt",requests,failAt),provider("deepseek",requests,failAt)))
        return CollaborateExecutor(registry,r).execute(if(retry==null) r.prepareCollaborate("question") else r.prepareCollaborateRetry(retry),true)
    }
    @Test fun absentStrategyDefaultsToStandardAndPrimary() {val c=Json.decodeFromString<CollaborateConfig>("""{"primary":{"ref":{"providerId":"chatgpt","modelId":"a"}},"reviewer":{"ref":{"providerId":"deepseek","modelId":"b"}}}""");assertEquals(ReviewIntensity.STANDARD,c.reviewIntensity);assertEquals(SynthesisRole.PRIMARY,c.synthesisRole)}
    @Test fun reviewPoliciesAreThreeDistinctInstructions() {assertEquals(3,ReviewIntensity.entries.map {ConversationContextBuilder.reviewInstruction(it)}.distinct().size)}
    @Test fun standardKeepsOriginalInstruction() {assertEquals(ConversationContextBuilder.REVIEW_INSTRUCTION,ConversationContextBuilder.reviewInstruction(ReviewIntensity.STANDARD))}
    @Test fun conciseAvoidsCosmeticReview() {val t=ConversationContextBuilder.reviewInstruction(ReviewIntensity.CONCISE);assertTrue(t.contains("关键遗漏"));assertTrue(t.contains("不做纯风格"))}
    @Test fun strictChecksMathAndUncertainty() {val t=ConversationContextBuilder.reviewInstruction(ReviewIntensity.STRICT);listOf("事实","逻辑","前提","假设","数学","推导","边界","反例","不确定").forEach {assertTrue(t.contains(it))}}
    @Test fun primaryRoutingRemainsABA()=runBlocking {val requests=mutableListOf<LlmRequest>();run(repo(),requests);assertEquals(listOf("a","b","a"),requests.map {it.model})}
    @Test fun reviewerRoutingIsABBExactlyThreeCalls()=runBlocking {val requests=mutableListOf<LlmRequest>();run(repo(CollaborateConfig(a,b,synthesisRole=SynthesisRole.REVIEWER)),requests);assertEquals(listOf("a","b","b"),requests.map {it.model})}
    @Test fun synthesisReviewerUsesReviewerReasoningPreference()=runBlocking {val requests=mutableListOf<LlmRequest>();run(repo(CollaborateConfig(a,b,synthesisRole=SynthesisRole.REVIEWER)),requests);assertEquals(requests[1].reasoning,requests[2].reasoning)}
    @Test fun reviewIntensityDoesNotChangeProviderEffort()=runBlocking {ReviewIntensity.entries.forEach {intensity->val c=run(repo(CollaborateConfig(a,b,intensity)));assertEquals(ReasoningPreference.High,c.rounds.single().stages[1].model.preference)}}
    @Test fun reviewInstructionOnlyReachesReviewStage()=runBlocking {ReviewIntensity.entries.forEach {intensity->val requests=mutableListOf<LlmRequest>();run(repo(CollaborateConfig(a,b,intensity)),requests);val instruction=ConversationContextBuilder.reviewInstruction(intensity);assertFalse(requests[0].messages.any {it.text==instruction});assertTrue(requests[1].messages.any {it.text==instruction});assertFalse(requests[2].messages.any {it.text==instruction})}}
    @Test fun synthesisStillIndependentlyChecksReview()=runBlocking {val requests=mutableListOf<LlmRequest>();run(repo(CollaborateConfig(a,b,synthesisRole=SynthesisRole.REVIEWER)),requests);assertEquals(ConversationContextBuilder.SYNTHESIS_INSTRUCTION,requests[2].messages.last().text);assertTrue(requests[2].messages.last().text.contains("独立核对"))}
    @Test fun reviewerSynthesisReceivesVisibleUpstreamOnly()=runBlocking {val requests=mutableListOf<LlmRequest>();run(repo(CollaborateConfig(a,b,ReviewIntensity.STRICT,SynthesisRole.REVIEWER)),requests);assertTrue(requests[2].messages.any {it.text=="VISIBLE_1"});assertTrue(requests[2].messages.any {it.text=="VISIBLE_2"});assertFalse(requests.any {it.messages.any {m->m.text.contains("NEVER_DOWNSTREAM")}})}
    @Test fun finalReviewerIsFutureContextSnapshot()=runBlocking {val r=repo(CollaborateConfig(a,b,synthesisRole=SynthesisRole.REVIEWER));run(r);val p=r.prepareCollaborate("follow up");assertEquals("VISIBLE_3",p.context.messages[1].text);assertEquals(setOf("chatgpt","deepseek"),p.context.sourceProviders);assertFalse(p.context.messages.any {it.text=="VISIBLE_1" || it.text=="VISIBLE_2"})}
    @Test fun strategyChangesOnlyFutureRound()=runBlocking {val r=repo();run(r);r.configureCollaborate(CollaborateConfig(a,b,ReviewIntensity.STRICT,SynthesisRole.REVIEWER));run(r);val rounds=r.activeConversation()!!.rounds;assertEquals(ReviewIntensity.STANDARD,rounds[0].reviewIntensity);assertEquals(a,rounds[0].stages.last().model);assertEquals(ReviewIntensity.STRICT,rounds[1].reviewIntensity);assertEquals(b,rounds[1].stages.last().model);assertEquals(1,r.sessions().size)}
    @Test fun changedStrategyDoesNotRequireRepeatedProviderConsent()=runBlocking {val r=repo();run(r);r.configureCollaborate(CollaborateConfig(a,b,ReviewIntensity.STRICT,SynthesisRole.REVIEWER));assertFalse(r.prepareCollaborate("next").requiresSharing)}
    @Test fun sameProviderStrategyNeverFakesCrossProviderConsent() {val same=b.copy(ref=ModelRef("chatgpt","b"),preference=ReasoningPreference.Auto,providerDisplayName="ChatGPT");assertFalse(repo(CollaborateConfig(a,same,synthesisRole=SynthesisRole.REVIEWER)).prepareCollaborate("q").requiresSharing)}
    @Test fun crossProviderReviewerStrategyStillNeedsConsent() {val r=repo(CollaborateConfig(a,b,synthesisRole=SynthesisRole.REVIEWER));val plan=r.prepareCollaborate("q");assertTrue(plan.requiresSharing);assertThrows(ContextSharingRequired::class.java) {r.beginCollaborate(plan)};assertTrue(r.sessions().isEmpty())}
    @Test fun stageFailureRetainsConfiguredStrategyWithoutFallback()=runBlocking {val requests=mutableListOf<LlmRequest>();val c=run(repo(CollaborateConfig(a,b,ReviewIntensity.STRICT,SynthesisRole.REVIEWER)),requests,2);assertEquals(2,requests.size);assertEquals(ErrorKind.PLAN_USAGE_LIMIT,c.rounds.single().stages[1].error);assertEquals(CollaborateStageState.NotRun,c.rounds.single().stages[2].state)}
    @Test fun retryFreezesStrategyModelsAndInputs()=runBlocking {val r=repo(CollaborateConfig(a,b,ReviewIntensity.CONCISE,SynthesisRole.REVIEWER));run(r,failAt=2);val old=r.activeConversation()!!.rounds.single();r.configureCollaborate(CollaborateConfig(a.copy(ref=ModelRef("chatgpt","new")),b,ReviewIntensity.STRICT,SynthesisRole.PRIMARY));val plan=r.prepareCollaborateRetry(old.roundId);assertEquals(old.config,plan.config);assertEquals(old.frozenInput.map {it.text},plan.context.messages.map {it.text});val requests=mutableListOf<LlmRequest>();val c=run(r,requests,retry=old.roundId);assertEquals(listOf("a","b","b"),requests.map {it.model});assertEquals(old.frozenInput,c.rounds.last().frozenInput)}
    @Test fun encryptedPersistenceRetainsNewSettingsAndSnapshots()=runBlocking {val blob=MemoryBlob();val c=run(repo(CollaborateConfig(a,b,ReviewIntensity.STRICT,SynthesisRole.REVIEWER),blob));val reopened=ChatRepository(blob,box);reopened.load();assertEquals(Json.encodeToString(c.rounds),Json.encodeToString(reopened.activeConversation()!!.rounds));assertEquals(c.collaborate,reopened.collaborateConfig());assertFalse(String(blob.read()!!).contains("VISIBLE_"))}
    @Test fun oldSchemaThreeRoundDefaultsAreReadable()=runBlocking {val blob=MemoryBlob();run(repo(blob=blob));val root=Json.parseToJsonElement(String(box.open(blob.read()!!))).jsonObject
        fun removeStrategy(value:JsonElement):JsonElement=when(value) {is JsonObject->JsonObject(value.filterKeys {it !in setOf("reviewIntensity","synthesisRole")}.mapValues {removeStrategy(it.value)});is JsonArray->JsonArray(value.map {removeStrategy(it)});else->value}
        blob.write(box.seal(removeStrategy(root).toString().toByteArray()));val reopened=ChatRepository(blob,box);reopened.load();val round=reopened.activeConversation()!!.rounds.single();assertEquals(ReviewIntensity.STANDARD,round.reviewIntensity);assertEquals(SynthesisRole.PRIMARY,round.synthesisRole);assertEquals(3,reopened.activeConversation()!!.schemaVersion)}
    @Test fun interruptedReviewerSynthesisRecoversWithoutReplay() {val blob=MemoryBlob();val r=repo(CollaborateConfig(a,b,ReviewIntensity.STRICT,SynthesisRole.REVIEWER),blob);val round=r.beginCollaborate(r.prepareCollaborate("q"),true).rounds.single();repeat(2) {i->val s=r.startCollaborateStage(round.roundId,i).rounds.single().stages[i];r.updateCollaborateStage(round.roundId,s.copy(state=CollaborateStageState.Complete,output="visible$i"))};r.startCollaborateStage(round.roundId,2);val restored=ChatRepository(blob,box);restored.load();val saved=restored.activeConversation()!!.rounds.single();assertEquals(CollaborateStageState.Interrupted,saved.stages[2].state);assertEquals(b,saved.stages[2].model);assertEquals(ReviewIntensity.STRICT,saved.reviewIntensity)}
    @Test fun cancelledReviewerSynthesisKeepsPriorStages() {val r=repo(CollaborateConfig(a,b,synthesisRole=SynthesisRole.REVIEWER));val round=r.beginCollaborate(r.prepareCollaborate("q"),true).rounds.single();repeat(2) {i->val s=r.startCollaborateStage(round.roundId,i).rounds.single().stages[i];r.updateCollaborateStage(round.roundId,s.copy(state=CollaborateStageState.Complete,output="v$i"))};r.startCollaborateStage(round.roundId,2);val stages=r.cancelCollaborateRound(round.roundId).rounds.single().stages;assertEquals(listOf(CollaborateStageState.Complete,CollaborateStageState.Complete,CollaborateStageState.Cancelled),stages.map {it.state})}
    @Test fun stageIdentityCannotOverrideConfiguredSynthesisRole() {val r=repo(CollaborateConfig(a,b,synthesisRole=SynthesisRole.REVIEWER));val round=r.beginCollaborate(r.prepareCollaborate("q"),true).rounds.single();val s=r.startCollaborateStage(round.roundId,0).rounds.single().stages[0];assertThrows(IllegalArgumentException::class.java) {r.updateCollaborateStage(round.roundId,s.copy(model=b,state=CollaborateStageState.Complete,output="v"))}}
    @Test fun countsRemainUserPlusThreeStages() {val r=repo(CollaborateConfig(a,b,synthesisRole=SynthesisRole.REVIEWER));repeat(50) {val round=r.beginCollaborate(r.prepareCollaborate("q"),true).rounds.last();r.cancelCollaborateRound(round.roundId)};assertThrows(IllegalArgumentException::class.java) {r.beginCollaborate(r.prepareCollaborate("full"),true)};assertEquals(50,r.activeConversation()!!.rounds.size)}
}
