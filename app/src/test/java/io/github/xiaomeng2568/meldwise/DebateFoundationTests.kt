// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DebateFoundationTests {
    private val f=DebateFixtures
    private val initials=listOf(DebateStageType.INITIAL_A,DebateStageType.INITIAL_B)
    private val reviews=listOf(DebateStageType.REVIEW_A_OF_B,DebateStageType.REVIEW_B_OF_A)
    private fun bad(r:DebateRound)=assertThrows(IllegalArgumentException::class.java) {validateDebate(f.conversation(r))}
    @Test fun configValid() {validDebateConfig(f.config)}
    @Test fun identicalParticipantsRejected() {assertThrows(IllegalArgumentException::class.java) {validDebateConfig(f.config.copy(modelB=f.a))}}
    @Test fun judgeMayEqualA() {validDebateConfig(f.config.copy(judge=f.a))}
    @Test fun judgeMayEqualB() {validDebateConfig(f.config.copy(judge=f.b))}
    @Test fun thirdJudgeAllowed() {validDebateConfig(f.config);assertNotEquals(f.a.ref,f.judge.ref)}
    @Test fun sameProviderDifferentModelsAllowed() {validDebateConfig(f.same)}
    @Test fun invalidProviderRejected() {assertThrows(IllegalArgumentException::class.java) {validDebateConfig(f.config.copy(judge=f.judge.copy(ref=ModelRef("other","j"))))}}
    @Test fun unknownModelRejected() {assertThrows(IllegalArgumentException::class.java) {validDebateConfig(f.config.copy(judge=f.judge.copy(ref=ModelRef("deepseek","UNKNOWN"))))}}
    @Test fun invalidModelRefRejected() {assertThrows(IllegalArgumentException::class.java) {validDebateConfig(f.config.copy(judge=f.judge.copy(ref=ModelRef("deepseek","bad model"))))}}
    @Test fun unsupportedPreferenceRejected() {assertThrows(IllegalArgumentException::class.java) {validDebateConfig(f.config.copy(modelB=f.b.copy(preference=ReasoningPreference.High)))}}
    @Test fun displayNameBounded() {assertThrows(IllegalArgumentException::class.java) {validDebateConfig(f.config.copy(modelA=f.a.copy(displayName="x".repeat(257))))}}
    @Test fun providerDisplaySnapshotValidated() {assertThrows(IllegalArgumentException::class.java) {validDebateConfig(f.config.copy(modelA=f.a.copy(providerDisplayName="wrong")))}}
    @Test fun providerSetSortedUnique() {assertEquals("chatgpt|deepseek",f.config.providerSetKey)}
    @Test fun swappedRolesSameProviderSet() {assertEquals(f.config.providerSetKey,f.config.copy(modelA=f.b,modelB=f.a,judge=f.b).providerSetKey)}
    @Test fun judgeAddsProviderToUnion() {assertEquals(setOf("deepseek","chatgpt"),f.same.copy(judge=f.b).providers)}
    @Test fun sameProviderNoWarning() {assertFalse(debateNeedsSharing(f.same,emptySet(),emptySet()))}
    @Test fun crossProviderNeedsWarning() {assertTrue(debateNeedsSharing(f.config,emptySet(),emptySet()))}
    @Test fun exactSetGrantSuffices() {assertFalse(debateNeedsSharing(f.config,emptySet(),setOf(f.config.providerSetKey)))}
    @Test fun providerSetChangeNeedsNewGrant() {assertTrue(debateNeedsSharing(f.config,emptySet(),setOf("deepseek")))}
    @Test fun foreignPriorProviderNeedsWarning() {assertTrue(debateNeedsSharing(f.same,setOf("chatgpt"),emptySet()))}
    @Test fun unknownPriorProviderNeedsWarning() {assertTrue(debateNeedsSharing(f.same,setOf("UNKNOWN"),emptySet()))}
    @Test fun malformedGrantRejected() {assertThrows(IllegalArgumentException::class.java) {debateNeedsSharing(f.config,emptySet(),setOf("deepseek|chatgpt"))}}
    @Test fun fiveUniqueStages() {val r=f.round();assertEquals(5,r.stages.size);assertEquals(5,r.stages.map {it.stageId}.distinct().size);validateDebate(f.conversation(r))}
    @Test fun modelSnapshotsMatchRoles() {val r=f.round();assertEquals(listOf(f.a,f.b,f.a,f.b,f.judge),r.stages.map {it.model})}
    @Test fun configCopyDoesNotRelabelOldRound() {val old=f.round();val new=old.config.copy(judge=f.a);assertEquals(f.judge,old.stage(DebateStageType.JUDGE).model);assertNotEquals(new,old.config)}
    @Test fun roundSerializationPreservesPreferencesAndRetry() {val old=f.complete();val r=f.round().copy(roundId="retry",retryOf=old.roundId);val decoded=Json.decodeFromString<DebateRound>(Json.encodeToString(r));assertEquals(r.config,decoded.config);assertEquals(r.retryOf,decoded.retryOf);assertEquals(r.frozenInput,decoded.frozenInput);assertEquals(7,decoded.inputRevision.toInt())}
    @Test fun privateTextRedacted() {assertFalse(f.round().toString().contains("CURRENT_QUESTION"));assertFalse(f.complete().stages.any {it.toString().contains("visible-")})}
    @Test fun initialsHaveNoDependency() {initials.forEach {assertTrue(DebateDag.dependencies(it).isEmpty())}}
    @Test fun reviewsDependOnBothInitials() {reviews.forEach {assertEquals(initials.toSet(),DebateDag.dependencies(it))}}
    @Test fun judgeDependsOnBothReviews() {assertEquals(reviews.toSet(),DebateDag.dependencies(DebateStageType.JUDGE))}
    @Test fun initialPairReadyTogether() {assertEquals(initials,DebateDag.readyStages(f.round()))}
    @Test fun firstInitialCompleteDoesNotUnlockReview() {assertEquals(listOf(DebateStageType.INITIAL_B),DebateDag.readyStages(f.complete(types=listOf(initials[0]))))}
    @Test fun runningAStillAllowsB() {assertEquals(listOf(initials[1]),DebateDag.readyStages(f.change(f.round(),initials[0],DebateStageState.Running)))}
    @Test fun bothInitialsUnlockParallelReviews() {assertEquals(reviews,DebateDag.readyStages(f.complete(types=initials)))}
    @Test fun oneReviewCompleteDoesNotUnlockJudge() {assertEquals(listOf(reviews[1]),DebateDag.readyStages(f.complete(types=initials+reviews[0])))}
    @Test fun runningReviewAllowsSibling() {val r=f.change(f.complete(types=initials),reviews[0],DebateStageState.Running);assertEquals(listOf(reviews[1]),DebateDag.readyStages(r))}
    @Test fun bothReviewsUnlockJudge() {assertEquals(listOf(DebateStageType.JUDGE),DebateDag.readyStages(f.complete(types=initials+reviews)))}
    @Test fun completeRoundRequiresAllFive() {val r=f.complete();validateDebate(f.conversation(r));assertEquals(DebateRoundState.Complete,r.lifecycle);assertTrue(DebateDag.readyStages(r).isEmpty())}
    @Test fun initialFailurePreservesCompletedSibling() {val r=settleDebateRound(f.change(f.complete(types=listOf(initials[1])),initials[0],DebateStageState.Failed,error=ErrorKind.PLAN_USAGE_LIMIT),11);validateDebate(f.conversation(r));assertEquals(DebateRoundState.Failed,r.lifecycle);assertEquals(DebateStageState.Complete,r.stage(initials[1]).state);assertTrue((reviews+DebateStageType.JUDGE).all {r.stage(it).state==DebateStageState.NotRun})}
    @Test fun reviewFailurePreservesCompletedSibling() {val r=settleDebateRound(f.change(f.complete(types=initials+reviews[1]),reviews[0],DebateStageState.Failed,error=ErrorKind.PROTOCOL),11);assertEquals(DebateStageState.Complete,r.stage(reviews[1]).state);assertEquals(DebateStageState.NotRun,r.stage(DebateStageType.JUDGE).state);validateDebate(f.conversation(r))}
    @Test fun judgeFailurePreservesUpstream() {val r=settleDebateRound(f.change(f.complete(types=initials+reviews),DebateStageType.JUDGE,DebateStageState.Failed),11);assertEquals(4,r.stages.count {it.state==DebateStageState.Complete});validateDebate(f.conversation(r))}
    @Test fun runningSiblingCancelledOnTerminalFailure() {val r=f.change(f.change(f.round(),initials[1],DebateStageState.Running),initials[0],DebateStageState.Failed);assertEquals(DebateStageState.Cancelled,settleDebateRound(r,11).stage(initials[1]).state)}
    @Test fun interruptedInitialAndRunningSiblingSettleAsInterruptedNotCancelled() {val r=settleDebateRound(f.change(f.change(f.round(),initials[1],DebateStageState.Running),initials[0],DebateStageState.Interrupted,error=ErrorKind.STREAM_INTERRUPTED),11);assertEquals(DebateRoundState.Interrupted,r.lifecycle);assertTrue(initials.all {r.stage(it).state==DebateStageState.Interrupted});validateDebate(f.conversation(r))}
    @Test fun interruptedReviewAndRunningSiblingSettleAsInterruptedNotCancelled() {val r=settleDebateRound(f.change(f.change(f.complete(types=initials),reviews[1],DebateStageState.Running),reviews[0],DebateStageState.Interrupted,error=ErrorKind.STREAM_INTERRUPTED),11);assertEquals(DebateRoundState.Interrupted,r.lifecycle);assertTrue(reviews.all {r.stage(it).state==DebateStageState.Interrupted});validateDebate(f.conversation(r))}
    @Test fun cancellationBlocksDownstream() {val r=settleDebateRound(f.change(f.round(),initials[0],DebateStageState.Cancelled),11);assertEquals(DebateRoundState.Cancelled,r.lifecycle);assertTrue(DebateDag.readyStages(r).isEmpty())}
    @Test fun pendingRoundRestoresWithoutRunnableWork() {val r=restoreDebate(f.round());assertEquals(DebateRoundState.Interrupted,r.lifecycle);assertTrue(r.stages.all {it.state==DebateStageState.NotRun});validateDebate(f.conversation(r))}
    @Test fun bothInitialsRunningRestoreInterrupted() {val r=restoreDebate(initials.fold(f.round()) {v,t->f.change(v,t,DebateStageState.Running)});assertTrue(initials.all {r.stage(it).state==DebateStageState.Interrupted});validateDebate(f.conversation(r))}
    @Test fun oneInitialRunningOtherPendingRestores() {val r=restoreDebate(f.change(f.round(),initials[0],DebateStageState.Running));assertEquals(DebateStageState.NotRun,r.stage(initials[1]).state)}
    @Test fun completedInitialSurvivesRestore() {val r=restoreDebate(f.change(f.complete(types=listOf(initials[0])),initials[1],DebateStageState.Running));assertEquals("visible-${initials[0]}",r.stage(initials[0]).output);validateDebate(f.conversation(r))}
    @Test fun bothReviewsRunningRestoreInterrupted() {val r=restoreDebate(reviews.fold(f.complete(types=initials)) {v,t->f.change(v,t,DebateStageState.Running)});assertTrue(reviews.all {r.stage(it).state==DebateStageState.Interrupted});validateDebate(f.conversation(r))}
    @Test fun completedReviewSurvivesRestore() {val r=restoreDebate(f.change(f.complete(types=initials+reviews[0]),reviews[1],DebateStageState.Running));assertEquals(DebateStageState.Complete,r.stage(reviews[0]).state)}
    @Test fun activeJudgeRestoresInterrupted() {val r=restoreDebate(f.change(f.complete(types=initials+reviews),DebateStageType.JUDGE,DebateStageState.Running));assertEquals(DebateStageState.Interrupted,r.stage(DebateStageType.JUDGE).state);validateDebate(f.conversation(r))}
    @Test fun reasoningStreamingBecomesInterrupted() {val r=restoreDebate(f.change(f.round(),initials[0],DebateStageState.Running,reasoning=ReasoningRecord("local",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Streaming)));assertEquals(ReasoningPhase.Interrupted,r.stage(initials[0]).reasoning.phase)}
    @Test fun emptyStreamingReasoningAlsoBecomesInterrupted() {val r=restoreDebate(f.change(f.round(),initials[0],DebateStageState.Running,reasoning=ReasoningRecord("",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Streaming)));assertEquals(ReasoningPhase.Interrupted,r.stage(initials[0]).reasoning.phase)}
    @Test fun completedReasoningUnchanged() {val done=f.complete();assertSame(done,restoreDebate(done))}
    @Test fun restoreIdempotent() {val r=restoreDebate(f.change(f.round(),initials[0],DebateStageState.Running));assertSame(r,restoreDebate(r))}
    @Test fun shuffledStorageIsNotSequentialDependency() {val r=f.complete(types=initials).let {it.copy(stages=it.stages.reversed())};assertEquals(reviews,DebateDag.readyStages(r));validateDebate(f.conversation(r))}
    @Test fun duplicateStageTypeRejected() {bad(f.round().let {it.copy(stages=it.stages.mapIndexed {i,s->if(i==1) s.copy(type=initials[0]) else s})})}
    @Test fun duplicateStageIdRejected() {bad(f.round().let {it.copy(stages=it.stages.map {s->s.copy(stageId="duplicate")})})}
    @Test fun missingStageRejected() {bad(f.round().let {it.copy(stages=it.stages.dropLast(1))})}
    @Test fun changedSnapshotRejected() {bad(f.round().let {it.copy(stages=it.stages.map {s->s.copy(model=f.judge)})})}
    @Test fun prematureReviewRejected() {bad(f.change(f.round(),reviews[0],DebateStageState.Running))}
    @Test fun prematureJudgeRejected() {bad(f.change(f.complete(types=initials),DebateStageType.JUDGE,DebateStageState.Running))}
    @Test fun emptyCompleteOutputRejected() {bad(f.change(f.round(),initials[0],DebateStageState.Complete,output=" "))}
    @Test fun wrongLifecycleRejected() {bad(f.round().copy(lifecycle=DebateRoundState.Complete))}
    @Test fun negativeTimestampRejected() {bad(f.round().copy(createdAt=-1))}
    @Test fun oversizedOutputRejected() {bad(f.change(f.round(),initials[0],DebateStageState.Running,output="x".repeat(524289)))}
    @Test fun oversizedReasoningRejected() {bad(f.change(f.round(),initials[0],DebateStageState.Running,reasoning=ReasoningRecord("x".repeat(262145))))}
    @Test fun chatgptHiddenReasoningKindRejected() {bad(f.change(f.round(),initials[1],DebateStageState.Running,reasoning=ReasoningRecord("local",ReasoningContent.ProviderVisibleReasoning)))}
    @Test fun evenFrozenInputRejected() {bad(f.round().copy(frozenInput=listOf(FrozenVisibleInput(MessageRole.USER,"prior"),FrozenVisibleInput(MessageRole.ASSISTANT,"CURRENT_QUESTION"))))}
    @Test fun pendingStageCannotHaveOutput() {bad(f.round().let {it.copy(stages=it.stages.map {s->s.copy(output="ghost")})})}
    @Test fun invalidSourceProviderRejected() {bad(f.round().copy(sourceProviders=setOf("other")))}
    @Test fun orchestrationModesCannotMix() {assertThrows(IllegalArgumentException::class.java) {validateDebate(f.conversation().copy(collaborate=CollaborateConfig(CollaborateModel(f.a.ref),CollaborateModel(f.b.ref))))}}
}
