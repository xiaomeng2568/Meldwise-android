// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xiaomeng2568.meldwise.auth.AuthState
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.ApiKeyState
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic component host only. Gate 7C compiles/packages this suite; it never runs it on a device. */
@RunWith(AndroidJUnit4::class)
class DebateUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val a=DebateModel(ModelRef("deepseek","a"),"Synthetic A",ReasoningPreference.Off)
    private val b=DebateModel(ModelRef("chatgpt","b"),"Synthetic B")
    private val j=DebateModel(ModelRef("deepseek","judge"),"Synthetic Judge",ReasoningPreference.Low)
    private val config=DebateConfig(a,b,j)
    private fun state(config:DebateConfig=this.config)=DebateUiState(DebateSelection.from(config),config,emptySet())
    private val catalogs=listOf(a,b,j).groupBy {it.ref.providerId}.mapValues {(_,models)->models.map {LlmModel(it.ref.modelId,it.displayName!!,"synthetic",ProviderCapability(emptySet()))}}
    private fun actions(onSend:(String)->Unit={},onCancel:()->Unit={},onPick:(DebateRole,ModelRef)->Unit={_,_->},onOpen:(String)->Unit={})=
        ChatActions({error("must not change Single provider")},{error("must not select Single model")},{},{},{},{},{},{},onSend,onCancel,
            chooseDebateModel=onPick,openSingle=onOpen)
    @Composable private fun Host(state:DebateUiState=state(),busy:Boolean=false,actions:ChatActions=actions()) {
        ChatScreen(ScreenState(selected=a.ref,historyRef=a.ref,ready=true,busy=busy),AuthState.Connected(true),ApiKeyState.CONFIGURED,null,
            ProcessingTime(null,null,false),Appearance.System,{},actions,FoundationState(catalogs=catalogs,mode=ConversationMode.Debate,debate=state))
    }
    private fun stage(type:DebateStageType,state:DebateStageState=DebateStageState.Running,output:String="")=
        DebateStage("stage-$type",type,config.modelFor(type),output=output,state=state)
    @Test fun debateModeIsEnabledAndSelectsExplicitly() {var selected=0
        compose.setContent {MeldwiseTheme {ModePicker(true,{},{},{},onDebate={selected++})}}
        compose.onNodeWithTag("modeRow-Debate").assertIsEnabled().performClick();compose.runOnIdle {assertEquals(1,selected)}
        compose.onNodeWithText("暂未开放").assertDoesNotExist()
    }
    @Test fun setupShowsThreeRolesAndRequiredRequestDisclosure() {
        compose.setContent {MeldwiseTheme {DebateSetup(state(),false,{},{_,_->})}}
        listOf("A","B","JUDGE").forEach {compose.onNodeWithTag("debatePick-$it").assertExists().assertHeightIsAtLeast(48.dp)}
        compose.onNodeWithText(DEBATE_USAGE).performScrollTo().assertIsDisplayed()
    }
    @Test fun invalidEqualABIsExplainedLocally() {compose.setContent {MeldwiseTheme {DebateSetup(state(config.copy(modelB=a)),false,{},{_,_->})}}
        compose.onNodeWithText("模型 A 和模型 B 必须不同；Judge 可以与 A 或 B 相同。").performScrollTo().assertIsDisplayed()}
    @Test fun emptyDebateHasRealConfigurationCta() {compose.setContent {MeldwiseTheme {Host()}}
        compose.onNodeWithText(DEBATE_EXPLANATION).assertExists();compose.onNodeWithText("选择辩论模型").performClick()
        compose.onNodeWithTag("debatePick-JUDGE").assertExists()}
    @Test fun invalidABPreventsComposerSend() {compose.setContent {MeldwiseTheme {Host(state(config.copy(modelB=a)))}}
        compose.onNodeWithTag("composerInput").performTextInput("Question");compose.onNodeWithContentDescription("开始辩论").assertIsNotEnabled()}
    @Test fun judgeEqualAAllowsValidSend() {compose.setContent {MeldwiseTheme {Host(state(config.copy(judge=a)))}}
        compose.onNodeWithTag("composerInput").performTextInput("Question");compose.onNodeWithContentDescription("开始辩论").assertIsEnabled()}
    @Test fun modelALaneSelectionDoesNotChangeBJudgeOrSingle() {var role:DebateRole?=null;var chosen:ModelRef?=null
        compose.setContent {MeldwiseTheme {Host(actions=actions(onPick={r,m->role=r;chosen=m}))}}
        compose.onNodeWithText("选择辩论模型").performClick();compose.onNodeWithTag("debatePick-A").performClick()
        compose.onNodeWithText(modelLabel(j.ref,j.displayName!!)).performClick();compose.onNodeWithText("选择模型").assertExists()
        compose.runOnIdle {assertEquals(DebateRole.A,role);assertEquals(j.ref,chosen)}
    }
    @Test fun modelBLaneSelectionIsIndependent() {var role:DebateRole?=null
        compose.setContent {MeldwiseTheme {Host(actions=actions(onPick={r,_->role=r}))}}
        compose.onNodeWithText("选择辩论模型").performClick();compose.onNodeWithTag("debatePick-B").performClick()
        compose.onNodeWithText("DeepSeek").performClick();compose.onNodeWithText(modelLabel(j.ref,j.displayName!!)).performClick()
        compose.runOnIdle {assertEquals(DebateRole.B,role)}
    }
    @Test fun judgePickerSelectionIsIndependent() {var role:DebateRole?=null
        compose.setContent {MeldwiseTheme {Host(actions=actions(onPick={r,_->role=r}))}}
        compose.onNodeWithText("选择辩论模型").performClick();compose.onNodeWithTag("debatePick-JUDGE").performClick()
        compose.onNodeWithText(modelLabel(a.ref,a.displayName!!)).performClick();compose.runOnIdle {assertEquals(DebateRole.JUDGE,role)}
    }
    @Test fun providerBrowsingDoesNotSelectAnyRole() {var selections=0
        compose.setContent {MeldwiseTheme {Host(actions=actions(onPick={_,_->selections++}))}}
        compose.onNodeWithText("选择辩论模型").performClick();compose.onNodeWithTag("debatePick-A").performClick()
        compose.onNodeWithText("ChatGPT").performClick();compose.runOnIdle {assertEquals(0,selections)}
        compose.onNodeWithText(modelLabel(a.ref,a.displayName!!)).assertDoesNotExist()
    }
    @Test fun thinkingControlsUpdateExactRole() {var role:DebateRole?=null;var preference:ReasoningPreference?=null
        compose.setContent {MeldwiseTheme {DebateSetup(state(),false,{}, {r,p->role=r;preference=p})}}
        compose.onNode(hasText("深入") and hasAnyAncestor(hasTestTag("debateRole-JUDGE"))).performScrollTo().performClick()
        compose.runOnIdle {assertEquals(DebateRole.JUDGE,role);assertEquals(ReasoningPreference.High,preference)}
    }
    @Test fun bothInitialSiblingsShowRunningIndependently() {compose.setContent {MeldwiseTheme {Column {
        DebateMessage(DebateMessageItem.Stage("r",stage(DebateStageType.INITIAL_A)));DebateMessage(DebateMessageItem.Stage("r",stage(DebateStageType.INITIAL_B)))
    }}};compose.onAllNodesWithText("正在回答…").assertCountEquals(2)}
    @Test fun bothReviewsShowRunningIndependently() {compose.setContent {MeldwiseTheme {Column {
        DebateMessage(DebateMessageItem.Stage("r",stage(DebateStageType.REVIEW_A_OF_B)));DebateMessage(DebateMessageItem.Stage("r",stage(DebateStageType.REVIEW_B_OF_A)))
    }}};compose.onAllNodesWithText("正在审阅…").assertCountEquals(2)}
    @Test fun completedSiblingRemainsVisibleWhileOtherRuns() {compose.setContent {MeldwiseTheme {Column {
        DebateMessage(DebateMessageItem.Stage("r",stage(DebateStageType.INITIAL_A,DebateStageState.Complete,"Preserved A")))
        DebateMessage(DebateMessageItem.Stage("r",stage(DebateStageType.INITIAL_B)))
    }}};compose.onNodeWithText("Preserved A").assertExists();compose.onNodeWithText("正在回答…").assertExists()}
    @Test fun judgeRunningUsesTruthfulLabel() {compose.setContent {MeldwiseTheme {DebateMessage(DebateMessageItem.Stage("r",stage(DebateStageType.JUDGE)))}}
        compose.onNodeWithText("正在整理…").assertExists()}
    @Test fun judgeFinalIsVisibleAndUsesSharedFormattedRenderer() {compose.setContent {MeldwiseTheme {
        DebateMessage(DebateMessageItem.Stage("r",stage(DebateStageType.JUDGE,DebateStageState.Complete,"**Final answer**\n\n```python\nprint(1)\n```")))
    }};compose.onNodeWithText("Judge · 最终回答").assertExists();compose.onNodeWithText("Final answer").assertExists();compose.onNodeWithText("Python").assertExists();compose.onNodeWithContentDescription("复制内容").assertExists()}
    @Test fun stageAllowanceFailureUsesSanitizedCategory() {compose.setContent {MeldwiseTheme {DebateMessage(DebateMessageItem.Stage("r",stage(DebateStageType.INITIAL_B,DebateStageState.Failed).copy(error=ErrorKind.PLAN_USAGE_LIMIT)))}}
        compose.onNodeWithText(errorLabel("PLAN_USAGE_LIMIT")).assertExists();compose.onNodeWithText("查看详情").performClick()
        compose.onNodeWithText("模型 B · 初答 · PLAN_USAGE_LIMIT").assertExists()}
    @Test fun localReasoningIsCollapsedAndNeverJudgeAnswerText() {compose.setContent {MeldwiseTheme {DebateMessage(DebateMessageItem.Stage("r",
        stage(DebateStageType.JUDGE,DebateStageState.Complete,"Visible final").copy(reasoning=ReasoningRecord("Local thought",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Completed))))}}
        compose.onNodeWithText("Local thought").assertDoesNotExist();compose.onNodeWithContentDescription("查看思考过程").performClick();compose.onNodeWithText("Local thought").assertExists()}
    @Test fun cancelControlIsReachableAndCallsWholeAttemptCancel() {var cancels=0;compose.setContent {MeldwiseTheme {Host(busy=true,actions=actions(onCancel={cancels++}))}}
        compose.onNodeWithContentDescription("停止当前操作").assertHeightIsAtLeast(48.dp).performClick();compose.runOnIdle {assertEquals(1,cancels)}
    }
    @Test fun consentCancellationDoesNotSend() {var sends=0;var cancels=0;compose.setContent {MeldwiseTheme {DebateSharingDialog(setOf("chatgpt","deepseek"),{sends++},{cancels++})}}
        compose.onNodeWithText("取消").performClick();compose.runOnIdle {assertEquals(0,sends);assertEquals(1,cancels)}
    }
    @Test fun consentRequiresExplicitContinue() {var sends=0;compose.setContent {MeldwiseTheme {DebateSharingDialog(setOf("deepseek"),{sends++},{})}}
        compose.runOnIdle {assertEquals(0,sends)};compose.onNodeWithText("继续并共享").performClick();compose.runOnIdle {assertEquals(1,sends)}
    }
    @Test fun historyDebateCategoryShowsPersistedCount() {compose.setContent {MeldwiseTheme {HistoryPanel(null,listOf(SingleSessionInfo("d",a.ref,"Debate history",ConversationMode.Debate)),emptyList(),false,{},{},{},{_,_->})}}
        compose.onNodeWithTag("modeRow-Debate").assertIsEnabled();compose.onNodeWithText("1 条记录").assertExists()}
    @Test fun openingHistoryIsNotSend() {var opens=0;var sends=0;compose.setContent {MeldwiseTheme {HistoryPanel(HistoryCategory.Debate,
        listOf(SingleSessionInfo("d",a.ref,"Debate history",ConversationMode.Debate)),emptyList(),false,{}, {entry->assertEquals("d",entry.id);opens++},{},{_,_->})}}
        compose.onNodeWithText("Debate history").performClick();compose.runOnIdle {assertEquals(1,opens);assertEquals(0,sends)}
    }
    @Test fun terminalRoundShowsWholeRoundRetryAction() {var retries=0
        val round=DebateRound("r","u",config,DebateStageType.entries.map {stage(it,
            if(it==DebateStageType.INITIAL_B) DebateStageState.Failed else DebateStageState.NotRun)},
            listOf(FrozenVisibleInput(MessageRole.USER,"Question")),0,0,0,DebateRoundState.Failed)
        val c=Conversation("d",a.ref,listOf(ChatMessage("u",null,MessageRole.USER,"Question",MessageState.COMPLETED)),
            "Question",0,0,mode=ConversationMode.Debate,debate=config,debateRounds=listOf(round))
        val actions=ChatActions({},{},{},{},{},{},{},{},{},{},retryDebate={assertEquals("r",it);retries++})
        compose.setContent {MeldwiseTheme {ChatScreen(ScreenState(messages=c.messages,historyRef=a.ref),AuthState.Connected(true),ApiKeyState.CONFIGURED,
            null,ProcessingTime(null,null,false),Appearance.System,{},actions,FoundationState(conversation=c,mode=ConversationMode.Debate,debate=state()))}}
        compose.onNodeWithTag("debateMessageFlow").performScrollToNode(hasText("按原模型重新执行这一轮"))
        compose.onNodeWithText("按原模型重新执行这一轮").assertIsEnabled().performClick();compose.runOnIdle {assertEquals(1,retries)}
    }
    @Test fun narrowLargeFontSetupKeepsJudgeAndDisclosureReachable() {compose.setContent {val density=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {MeldwiseTheme(Appearance.Dark,0x366BD5L) {
            Box(Modifier.width(320.dp)) {DebateSetup(state(config.copy(judge=j.copy(displayName="中文 English ".repeat(20)))),false,{},{_,_->})}
        }}}
        compose.onNodeWithTag("debatePick-JUDGE").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(DEBATE_USAGE).performScrollTo().assertIsDisplayed()
    }
    @Test fun narrowLargeFontComposerRetainsSendAndOptions() {compose.setContent {val density=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {MeldwiseTheme {Box(Modifier.width(320.dp).height(700.dp)) {Host()}}}}
        compose.onNodeWithTag("composerInput").performTextInput("one\ntwo\nthree\nfour\nfive")
        compose.onNodeWithContentDescription("开始辩论").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("更多输入选项").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("composerSummary").assertExists()
    }
}
