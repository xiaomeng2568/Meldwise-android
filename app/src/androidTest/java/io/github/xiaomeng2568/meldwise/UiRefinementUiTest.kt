// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xiaomeng2568.meldwise.auth.AuthState
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.ApiKeyState
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI hosts. This task only compiles/packages AndroidTest; no device execution. */
@RunWith(AndroidJUnit4::class)
class UiRefinementUiTest {
    @get:Rule val compose=createComposeRule()
    private val ref=ModelRef("deepseek","fixture-a")
    private fun actions()=ChatActions({},{},{},{},{},{},{},{},{},{})
    @Test fun sourceModeReplacesFormattedAnswerAndUsesOneWholeAnswerCopy() {
        compose.setContent {MeldwiseTheme {AssistantOutput(CompareLane(ref,"A",LaneState.Completed,"**Visible answer**"))}}
        compose.onNodeWithText("Visible answer").assertExists()
        compose.onNodeWithContentDescription("更多内容操作").performClick()
        compose.onNodeWithText("按纯文本查看").performClick()
        compose.onNodeWithText("Visible answer").assertDoesNotExist()
        compose.onAllNodesWithText("**Visible answer**").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("复制内容").assertCountEquals(1)
        compose.onNodeWithContentDescription("更多内容操作").performClick()
        compose.onNodeWithText("恢复排版").performClick()
        compose.onNodeWithText("Visible answer").assertExists()
        compose.onNodeWithText("**Visible answer**").assertDoesNotExist()
    }
    @Test fun quietCopyStillHasFullAccessibleTouchTarget() {
        compose.setContent {MeldwiseTheme {CopyAction("source",quiet=true)}}
        compose.onNodeWithContentDescription("复制内容").assertHasClickAction().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
    }
    @Test fun reasoningHasTruthfulCompactStatusAndIndependentText() {
        compose.setContent {MeldwiseTheme {Column {ReasoningPanel(ReasoningSummary(ReasoningState.Completed,"UI_ONLY_REASONING"));ContentRenderer(ContentParser.parse("Final answer"))}}}
        compose.onNodeWithText("思考摘要 · 已完成").assertExists()
        compose.onNodeWithText("UI_ONLY_REASONING").assertDoesNotExist()
        compose.onNodeWithContentDescription("查看思考摘要").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("UI_ONLY_REASONING").assertExists()
        compose.onNodeWithContentDescription("收起思考摘要").performClick()
        compose.onNodeWithText("UI_ONLY_REASONING").assertDoesNotExist()
        compose.onNodeWithText("Final answer").assertExists()
    }
    @Test fun reviewUsesActualRoundIntensityAndModelSnapshot() {
        val s=CollaborateStage("s",CollaborateStageType.REVIEW,1,CollaborateModel(ref,"Original model"),"Review output",state=CollaborateStageState.Complete)
        compose.setContent {MeldwiseTheme {CollaborateMessage(CollaborateMessageItem.Stage("r",s,ReviewIntensity.STRICT))}}
        compose.onNodeWithText("审阅 · 严格").assertExists();compose.onNodeWithText("DeepSeek-Original model").assertExists()
    }
    @Test fun synthesisUsesSameFreeFlowingContentRenderer() {
        compose.setContent {MeldwiseTheme {AssistantOutput(CompareLane(ref,"A",LaneState.Completed,"**Synthesis answer**"),role=AnswerRole.Synthesis,heading="综合")}}
        compose.onNodeWithText("综合").assertExists();compose.onNodeWithText("Synthesis answer").assertExists()
        compose.onNodeWithTag("assistantOutput").assertExists()
    }
    @Test fun historyStillHasThreeRealCountsAndUnavailableDebate() {
        compose.setContent {MeldwiseTheme {HistoryPanel(null,listOf(SingleSessionInfo("s",ref,"Chat"),SingleSessionInfo("c",ref,"Collab",ConversationMode.Collaborate)),emptyList(),false,{},{},{},{_,_->})}}
        compose.onNodeWithText("对话").assertExists();compose.onNodeWithText("对比").assertExists();compose.onNodeWithText("协作").assertExists()
        compose.onAllNodesWithText("1 条记录").assertCountEquals(2);compose.onNodeWithText("0 条记录").assertExists()
        compose.onNodeWithTag("modeRow-Debate").assertIsNotEnabled();compose.onNodeWithText("暂未开放").assertExists()
    }
    @Test fun fourModeRowsRemainAccessibleAndDisabledDebateNeverFires() {
        var calls=0
        compose.setContent {MeldwiseTheme {ModePicker(true,{calls++},{calls++},{calls++},HistoryCategory.Chat)}}
        compose.onNodeWithTag("modeRow-Chat").assertIsSelected().assertHasClickAction()
        compose.onNodeWithTag("modeRow-Compare").assertHasClickAction();compose.onNodeWithTag("modeRow-Collaborate").assertHasClickAction()
        compose.onNodeWithTag("modeRow-Debate").assertIsNotEnabled().performTouchInput {click()}
        compose.runOnIdle {assertEquals(0,calls)}
    }
    @Test fun narrowLargeFontComposerRetainsSendAndInputOptions() {
        compose.setContent {MeldwiseTheme {CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)) {
            Box(Modifier.width(320.dp).height(700.dp)) {ChatScreen(ScreenState(providerId="deepseek",historyRef=ref),AuthState.Disconnected,ApiKeyState.CONFIGURED,null,
                ProcessingTime(null,null,false),Appearance.System,{},actions())}
        }}}
        compose.onNodeWithContentDescription("更多输入选项").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("发送消息").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("消息输入框").performTextInput("first\nsecond\nthird")
        compose.onNodeWithContentDescription("更多输入选项").performClick()
        compose.onNodeWithText("附件").assertDoesNotExist();compose.onNodeWithText("思考").assertExists()
    }
    @Test fun collaborateComposerOptionDoesNotPretendToBeGlobalThinking() {
        compose.setContent {MeldwiseTheme {ChatScreen(ScreenState(providerId="deepseek",historyRef=ref),AuthState.Disconnected,ApiKeyState.CONFIGURED,null,
            ProcessingTime(null,null,false),Appearance.Light,{},actions(),FoundationState(thinking=ReasoningPreference.High,mode=ConversationMode.Collaborate))}}
        compose.onNodeWithContentDescription("更多输入选项").performClick()
        compose.onNodeWithText("模型设置").assertIsNotSelected();compose.onNodeWithText("思考").assertDoesNotExist()
    }
    @Test fun darkAccentDoesNotBypassOriginalCodeRenderer() {
        compose.setContent {MeldwiseTheme(Appearance.Dark,0x336699) {AssistantOutput(CompareLane(ref,"A",LaneState.Completed,"```kotlin\n  raw source\n```"))}}
        compose.onNodeWithText("  raw source\n").assertExists();compose.onNodeWithText("Kotlin").assertExists()
    }
    @Test fun errorDetailsRemainAccessibleThroughQuietActionMenu() {
        var details=0
        compose.setContent {MeldwiseTheme {AssistantOutput(CompareLane(ref,"A",LaneState.Failed,""),onDetails={details++})}}
        compose.onNodeWithText("请求失败").assertExists();compose.onNodeWithContentDescription("更多内容操作").performClick()
        compose.onNodeWithText("查看详情").performClick();compose.runOnIdle {assertEquals(1,details)}
    }
}
