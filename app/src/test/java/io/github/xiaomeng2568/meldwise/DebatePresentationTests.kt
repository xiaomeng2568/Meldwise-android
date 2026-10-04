// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import io.github.xiaomeng2568.meldwise.ui.theme.*

class DebatePresentationTests {
    private val config=DebateFixtures.config
    private val selection=DebateSelection.from(config)
    private fun stage(type:DebateStageType=DebateStageType.INITIAL_A,state:DebateStageState=DebateStageState.Running)=
        DebateFixtures.round().stage(type).copy(state=state)
    private fun ui(path:String)=File(System.getProperty("projectRoot"),"app/src/main/java/io/github/xiaomeng2568/meldwise/ui/$path").readText()
    @Test fun missingAHasLocalMessage() {assertEquals("请选择模型 A。",debateSetupIssue(DebateSelection()))}
    @Test fun missingBHasLocalMessage() {assertEquals("请选择模型 B。",debateSetupIssue(DebateSelection(a=config.modelA)))}
    @Test fun missingJudgeHasLocalMessage() {assertEquals("请选择 Judge。",debateSetupIssue(selection.copy(judge=null)))}
    @Test fun equalABIsInvalid() {assertNull(selection.copy(b=config.modelA).config());assertTrue(debateSetupIssue(selection.copy(b=config.modelA))!!.contains("必须不同"))}
    @Test fun judgeMayEqualA() {assertNotNull(selection.copy(judge=config.modelA).config())}
    @Test fun judgeMayEqualB() {assertNotNull(selection.copy(judge=config.modelB).config())}
    @Test fun thirdJudgeIsValid() {assertEquals(config,selection.config())}
    @Test fun unsupportedReasoningIsInvalid() {assertNull(selection.copy(b=config.modelB.copy(preference=ReasoningPreference.Max)).config())}
    @Test fun unknownModelRefCannotEnableSend() {assertNull(selection.copy(judge=config.judge.copy(ref=ModelRef("deepseek","UNKNOWN"))).config())}
    @Test fun missingProviderCannotEnableSend() {assertNull(selection.copy(judge=config.judge.copy(ref=ModelRef("fake","j"))).config())}
    @Test fun unavailableRoleIsNamed() {assertTrue(debateSetupIssue(selection,setOf(DebateRole.JUDGE))!!.startsWith("Judge"))}
    @Test fun validSetupHasNoValidationError() {assertNull(debateSetupIssue(selection))}
    @Test fun validCommittedReadySetupEnablesSend() {assertTrue(DebateUiState(selection,config,emptySet()).sendReady)}
    @Test fun uncommittedFormCannotSendOldConfig() {assertFalse(DebateUiState(selection.copy(judge=config.modelA),config,emptySet()).sendReady)}
    @Test fun unavailableConfigCannotSend() {assertFalse(DebateUiState(selection,config,setOf(DebateRole.A)).sendReady)}
    @Test fun rolesChangeIndependently() {val next=selection.with(DebateRole.B,config.judge);assertEquals(selection.a,next.a);assertEquals(selection.judge,next.judge);assertEquals(config.judge,next.b)}
    @Test fun judgeChangeDoesNotChangeParticipants() {val next=selection.with(DebateRole.JUDGE,config.modelA);assertEquals(selection.a,next.a);assertEquals(selection.b,next.b)}
    @Test fun composerSummaryIsShort() {assertEquals("辩论 · 5 请求",composerSummary(HistoryCategory.Debate,"x".repeat(256),"深入","严格").label)}
    @Test fun composerSummaryUsesExistingSetupRoute() {assertEquals(ChatPanel.DebateSetup,composerSummary(HistoryCategory.Debate,"","","").configuration)}
    @Test fun requestCountAndBillingDisclosureIsVisibleCopy() {assertTrue(DEBATE_USAGE.contains("最多发起 5 次模型请求"));assertTrue(DEBATE_USAGE.contains("各自服务"));assertTrue(ui("DebatePanels.kt").contains("Text(DEBATE_USAGE"))}
    @Test fun explanationDoesNotPromiseGuaranteedQuality() {assertTrue(DEBATE_EXPLANATION.contains("独立回答"));assertTrue(DEBATE_EXPLANATION.contains("交叉审阅"));assertFalse(DEBATE_EXPLANATION.contains("保证"))}
    @Test fun initialALabel() {assertEquals("模型 A · 初答",debateStageLabel(DebateStageType.INITIAL_A))}
    @Test fun initialBLabel() {assertEquals("模型 B · 初答",debateStageLabel(DebateStageType.INITIAL_B))}
    @Test fun reviewALabel() {assertEquals("模型 A · 审阅 B",debateStageLabel(DebateStageType.REVIEW_A_OF_B))}
    @Test fun reviewBLabel() {assertEquals("模型 B · 审阅 A",debateStageLabel(DebateStageType.REVIEW_B_OF_A))}
    @Test fun judgeLabel() {assertEquals("Judge · 最终回答",debateStageLabel(DebateStageType.JUDGE))}
    @Test fun bothInitialRunningLabelsAreConcurrent() {assertEquals("正在回答…",debateStageStatus(stage()));assertEquals("正在回答…",debateStageStatus(stage(DebateStageType.INITIAL_B)))}
    @Test fun bothReviewRunningLabelsAreConcurrent() {assertEquals("正在审阅…",debateStageStatus(stage(DebateStageType.REVIEW_A_OF_B)));assertEquals("正在审阅…",debateStageStatus(stage(DebateStageType.REVIEW_B_OF_A)))}
    @Test fun judgeRunningLabel() {assertEquals("正在整理…",debateStageStatus(stage(DebateStageType.JUDGE)))}
    @Test fun pendingLabel() {assertEquals("等待",debateStageStatus(stage(state=DebateStageState.Pending)))}
    @Test fun notRunLabel() {assertEquals("未执行",debateStageStatus(stage(state=DebateStageState.NotRun)))}
    @Test fun cancelledLabel() {assertEquals("已取消",debateStageStatus(stage(state=DebateStageState.Cancelled)))}
    @Test fun interruptedLabel() {assertEquals("已中断",debateStageStatus(stage(state=DebateStageState.Interrupted)))}
    @Test fun failureLabelIsNotRawEnum() {assertEquals("失败",debateStageStatus(stage(state=DebateStageState.Failed)))}
    @Test fun completedAnswerNeedsNoStatusBadge() {assertNull(debateStageStatus(stage(state=DebateStageState.Complete)))}
    @Test fun timingNeedsFirstVisibleText() {assertNull(debateProcessingCaption(stage().copy(processingDuration=3)))}
    @Test fun realTimingIsSecondary() {assertEquals("已处理 3 秒",debateProcessingCaption(stage().copy(processingDuration=3,output="answer")))}
    @Test fun pendingAndNotRunNeverFabricateTime() {listOf(DebateStageState.Pending,DebateStageState.NotRun).forEach {assertNull(debateProcessingCaption(stage(state=it).copy(processingDuration=3,output="invalid")))}}
    @Test fun reasoningDoesNotCountAsVisibleAnswerTime() {assertNull(debateProcessingCaption(stage().copy(processingDuration=3,reasoning=ReasoningRecord("reasoning"))))}
    @Test fun onlyJudgeHasPrimaryHierarchy() {DebateStageType.entries.forEach {assertEquals(it==DebateStageType.JUDGE,debateAnswerRole(it).primaryHeading())}}
    @Test fun displayOrderIsIndependentOfStageStorageOrder() {val r=DebateFixtures.round();val items=debateMessageItems(DebateFixtures.conversation(r.copy(stages=r.stages.reversed())));assertTrue(items.first() is DebateMessageItem.Prompt);assertEquals(DebateStageType.entries,items.drop(1).map {(it as DebateMessageItem.Stage).stage.type})}
    @Test fun presentationKeysAreStableAcrossStreaming() {val c=DebateFixtures.conversation();val next=c.copy(debateRounds=c.debateRounds.map {r->r.copy(stages=r.stages.map {it.copy(output="stream")})});assertEquals(debateMessageItems(c).map {it.key},debateMessageItems(next).map {it.key})}
    @Test fun allFiveStagesRemainVisibleEvenNotRun() {val r=DebateFixtures.round();assertEquals(6,debateMessageItems(DebateFixtures.conversation(r.copy(stages=r.stages.map {it.copy(state=DebateStageState.NotRun)}))).size)}
    @Test fun mapperRejectsWrongMode() {assertThrows(IllegalArgumentException::class.java) {debateMessageItems(DebateFixtures.conversation().copy(mode=ConversationMode.Single))}}
    @Test fun failedRoundCanRetry() {val r=DebateFixtures.round().copy(lifecycle=DebateRoundState.Failed);assertTrue(debateRetryEligible(DebateFixtures.conversation(r),r))}
    @Test fun cancelledRoundCanRetry() {val r=DebateFixtures.round().copy(lifecycle=DebateRoundState.Cancelled);assertTrue(debateRetryEligible(DebateFixtures.conversation(r),r))}
    @Test fun interruptedRoundCanRetry() {val r=DebateFixtures.round().copy(lifecycle=DebateRoundState.Interrupted);assertTrue(debateRetryEligible(DebateFixtures.conversation(r),r))}
    @Test fun completedRoundCannotRetry() {val r=DebateFixtures.complete();assertFalse(debateRetryEligible(DebateFixtures.conversation(r),r))}
    @Test fun activeRoundCannotRetry() {val r=DebateFixtures.round();assertFalse(debateRetryEligible(DebateFixtures.conversation(r),r))}
    @Test fun onlyLatestAttemptCanRetry() {val r=DebateFixtures.round().copy(lifecycle=DebateRoundState.Failed);val c=DebateFixtures.conversation(r).copy(debateRounds=listOf(r,r.copy(roundId="new")));assertFalse(debateRetryEligible(c,r))}
    @Test fun consentNamesOnlyActualProviders() {val copy=debateConsentCopy(setOf("deepseek"));assertTrue(copy.contains("DeepSeek"));assertFalse(copy.contains("ChatGPT"))}
    @Test fun crossProviderConsentExplainsIsolationAndLocalScope() {val copy=debateConsentCopy(setOf("chatgpt","deepseek"));assertTrue(copy.contains("ChatGPT"));assertTrue(copy.contains("DeepSeek"));assertTrue(copy.contains("凭据和诊断信息不会共享"));assertTrue(copy.contains("当前对话"))}
    @Test fun unknownProvenanceIsNotInventedProvider() {assertTrue(debateConsentCopy(setOf("UNKNOWN","deepseek")).contains("来源服务商（未知）"))}
    @Test fun historyCountsActualDebateConversations() {val sessions=listOf(SingleSessionInfo("d",config.modelA.ref,"q",ConversationMode.Debate),SingleSessionInfo("s",config.modelA.ref,"s"));assertEquals(1,historySummaries(sessions,emptyList()).last().count);assertEquals(listOf("d"),historyEntries(HistoryCategory.Debate,sessions,emptyList()).map {it.id})}
    @Test fun historyCategoryRestoresDebateNotCompare() {assertEquals(ConversationMode.Debate,HistoryCategory.Debate.conversationMode);assertFalse(ChatNavigation(compareMode=true).openHistoryEntry(HistoryEntry("d",HistoryCategory.Debate,"","")).compareMode)}
    @Test fun categoryBackReturnsToRoot() {val n=ChatNavigation().open(ChatPanel.Settings).openHistory(HistoryCategory.Debate);assertEquals(ChatPanel.History,n.back().panel);assertEquals(ChatPanel.Settings,n.back().back().panel)}
    @Test fun outsideDismissPreservesUnderlyingModeNavigation() {assertEquals(ChatPanel.None,ChatNavigation().open(ChatPanel.DebateSetup).open(ChatPanel.Models).dismiss().panel)}
    @Test fun longNamesEllipsizeInSharedControls() {assertTrue(ui("DebatePanels.kt").contains("maxLines=2,overflow=TextOverflow.Ellipsis"));assertTrue(ui("components/ModelTitle.kt").contains("maxLines=1,overflow=TextOverflow.Ellipsis"))}
    @Test fun criticalRoleTargetsReuse48DpPrimitive() {assertTrue(ui("DebatePanels.kt").contains("heightIn(min=Sizes.touch)"));assertTrue(ui("DebatePanels.kt").contains("MeldwiseTextButton"))}
    @Test fun sharedNativeContentAndCollapsedReasoningAreReused() {assertTrue(ui("DebatePanels.kt").contains("AssistantOutput("));assertTrue(ui("DebatePanels.kt").contains("reasoningPresentation(s.reasoning)"));assertFalse(ui("DebatePanels.kt").contains("ContentParser"))}
    @Test fun stageMetadataDoesNotAddCardsOrProviderColor() {val source=ui("DebatePanels.kt");assertFalse(source.contains("Card("));assertFalse(source.contains("Color("));assertFalse(source.contains("DebateExecutor"))}
    @Test fun exactStageErrorIsNotDuplicatedInComposer() {assertFalse(showComposerError("PLAN_USAGE_LIMIT",setOf("PLAN_USAGE_LIMIT")));assertTrue(showComposerError("STORAGE",setOf("NETWORK")))}
    @Test fun pickerDestinationCarriesIndependentJudgeLane() {assertEquals("JUDGE",PanelDestination(ChatPanel.Models,2,"JUDGE",true).modelLane);assertEquals(HistoryCategory.Debate,PanelDestination(ChatPanel.HistoryDebate,2).historyCategory)}
    @Test fun privateContentIsRedactedFromUiStateStrings() {assertFalse(DebateUiState(selection,config).toString().contains("PRIVATE"));assertFalse(debateMessageItems(DebateFixtures.conversation()).toString().contains("CURRENT_QUESTION"))}
    private fun width(value:Int) {val axis=MeldwiseContentMetrics.composerWidth(value.dp);assertTrue(axis>=Sizes.touch*2);assertTrue(ui("components/AdaptiveComposer.kt").contains("Modifier.weight(1f).testTag(\"composerSummary\")"));assertTrue(ui("ChatScreen.kt").contains("testTag(\"debateMessageFlow\")"))}
    @Test fun narrow320AxisRetainsControlSpace() {width(320)}
    @Test fun normal360AxisRetainsControlSpace() {width(360)}
    @Test fun normal412AxisRetainsControlSpace() {width(412)}
    @Test fun wide600AxisRetainsControlSpace() {width(600)}
    @Test fun foldable840AxisRemainsBounded() {width(840);assertTrue(MeldwiseContentMetrics.axisWidth(840.dp)<=MeldwiseContentMetrics.readableMax)}
    private fun contrast(dark:Boolean,accent:Long?=null) {val c=accentColors(dark,accent);listOf(c.onSurface,c.onSurfaceVariant,c.error).forEach {val a=it.luminance();val b=c.background.luminance();assertTrue((maxOf(a,b)+.05f)/(minOf(a,b)+.05f)>=4.5f)}}
    @Test fun lightDebateSemanticTextHasContrast() {contrast(false)}
    @Test fun darkDebateSemanticTextHasContrast() {contrast(true)}
    @Test fun customAccentDoesNotBecomeProviderBrandPalette() {contrast(false,0xB45B7DL);contrast(true,0x366BD5L)}
}
