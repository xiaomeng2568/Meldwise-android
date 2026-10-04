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
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic rendering only. Compile/package in this gate, never execute on a device. */
@RunWith(AndroidJUnit4::class)
class AnswerDisclosureUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val model=DebateModel(ModelRef("deepseek","fixture"),"Fixture")
    private fun initial(id:String="a",text:String="Answer A",type:DebateStageType=DebateStageType.INITIAL_A,state:DebateStageState=DebateStageState.Complete)=
        DebateMessageItem.Stage("r",DebateStage(id,type,model,output=text,state=state))
    @Composable private fun Host(content:@Composable ()->Unit) {MeldwiseTheme {
        val disclosures=remember {AnswerDisclosures()}
        CompositionLocalProvider(LocalAnswerDisclosures provides disclosures) {Column(Modifier.width(320.dp)) {content()}}
    }}
    @Test fun debateInitialsDefaultExpandedAndToggleIndependently() {
        compose.setContent {Host {DebateMessage(initial());DebateMessage(initial("b","Answer B",DebateStageType.INITIAL_B))}}
        compose.onNodeWithText("Answer A").assertExists();compose.onNodeWithText("Answer B").assertExists()
        compose.onNodeWithContentDescription("收起模型 A · 初答").performClick()
        compose.onNodeWithText("Answer A").assertDoesNotExist();compose.onNodeWithText("Answer B").assertExists()
        compose.onNodeWithContentDescription("展开模型 A · 初答").performClick();compose.onNodeWithText("Answer A").assertExists()
    }
    @Test fun compareAnswersHaveIndependentDisclosure() {
        val a=CompareLaneRecord("A",model.ref,"Compare A",CompareLaneState.Completed)
        val b=a.copy(laneId="B",modelRef=ModelRef("deepseek","b"),output="Compare B")
        val run=CompareRun("run","Question",a,b)
        compose.setContent {Host {compareMessageItems(run).forEach {key(it.key) {CompareMessage(it)}}}}
        compose.onNodeWithContentDescription("收起模型 A · 独立回答").performClick()
        compose.onNodeWithText("Compare A").assertDoesNotExist();compose.onNodeWithText("Compare B").assertExists()
        compose.onNodeWithContentDescription("展开模型 A · 独立回答").performClick();compose.onNodeWithText("Compare A").assertExists()
    }
    @Test fun collaborateInitialAndReviewCanCollapseButFinalStaysVisible() {
        compose.setContent {Host {CollaborateStageType.entries.forEach {type->
            CollaborateMessage(CollaborateMessageItem.Stage("r",CollaborateStage(type.name,type,type.ordinal,
                CollaborateModel(model.ref,"Fixture"),"Content ${type.name}",state=CollaborateStageState.Complete)))
        }}}
        compose.onNodeWithContentDescription("收起初答").performClick();compose.onNodeWithContentDescription("收起审阅 · 标准").performClick()
        compose.onNodeWithText("Content INITIAL").assertDoesNotExist();compose.onNodeWithText("Content REVIEW").assertDoesNotExist()
        compose.onNodeWithText("Content SYNTHESIS").assertExists();compose.onNodeWithContentDescription("收起综合").assertDoesNotExist()
    }
    @Test fun judgeFinalNeverGetsCollapseButton() {compose.setContent {Host {DebateMessage(initial("judge","Final",DebateStageType.JUDGE))}}
        compose.onNodeWithText("Final").assertExists();compose.onNodeWithContentDescription("收起Judge · 最终回答").assertDoesNotExist()}
    @Test fun singleAnswerIsNotRedesigned() {compose.setContent {Host {AssistantOutput(CompareLane(model.ref,"Fixture",LaneState.Completed,"Single"))}}
        compose.onNodeWithText("Single").assertExists();compose.onNodeWithText("收起").assertDoesNotExist()}
    @Test fun noEmptyToggleWhileWaitingForFirstVisibleAnswer() {compose.setContent {Host {DebateMessage(initial(text="",state=DebateStageState.Running))}}
        compose.onNodeWithText("正在回答…").assertExists();compose.onNodeWithText("收起").assertDoesNotExist()}
    @Test fun collapsedStreamingOutputStaysCollapsedWhileStatusUpdates() {var text by mutableStateOf("First")
        compose.setContent {Host {DebateMessage(initial(text=text,state=DebateStageState.Running))}}
        compose.onNodeWithContentDescription("收起模型 A · 初答").performClick();compose.runOnIdle {text="First and later text"}
        compose.onNodeWithText("正在回答…").assertExists();compose.onNodeWithText("First and later text").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开模型 A · 初答").performClick();compose.onNodeWithText("First and later text").assertExists()
    }
    @Test fun partialFailureRemainsVisibleAfterCollapse() {val item=initial(state=DebateStageState.Failed)
        compose.setContent {Host {DebateMessage(DebateMessageItem.Stage("r",item.stage.copy(error=ErrorKind.PLAN_USAGE_LIMIT)))}}
        compose.onNodeWithContentDescription("收起模型 A · 初答").performClick();compose.onNodeWithText(errorLabel("PLAN_USAGE_LIMIT")).assertExists()
        compose.onNodeWithText("失败").assertExists();compose.onNodeWithText("查看详情").performClick()
        compose.onNodeWithText("模型 A · 初答 · PLAN_USAGE_LIMIT").assertExists()
    }
    @Test fun collapsedCancelledPartialKeepsTruthfulStatus() {compose.setContent {Host {DebateMessage(initial(state=DebateStageState.Cancelled))}}
        compose.onNodeWithContentDescription("收起模型 A · 初答").performClick();compose.onNodeWithText("已取消").assertExists()}
    @Test fun disposingAndRecreatingAnItemDoesNotResetItsScreenOwnedFlag() {var visible by mutableStateOf(true)
        compose.setContent {Host {if(visible) DebateMessage(initial())}}
        compose.onNodeWithContentDescription("收起模型 A · 初答").performClick()
        compose.runOnIdle {visible=false};compose.onNodeWithText("模型 A · 初答").assertDoesNotExist();compose.runOnIdle {visible=true}
        compose.onNodeWithText("Answer A").assertDoesNotExist();compose.onNodeWithContentDescription("展开模型 A · 初答").assertExists()
    }
    @Test fun newAttemptUsesANewIndependentKey() {var id by mutableStateOf("a")
        compose.setContent {Host {key(id) {DebateMessage(initial(id))}}}
        compose.onNodeWithContentDescription("收起模型 A · 初答").performClick();compose.runOnIdle {id="retry-a"}
        compose.onNodeWithText("Answer A").assertExists();compose.onNodeWithContentDescription("收起模型 A · 初答").assertExists()
    }
    @Test fun plainSourceChoiceSurvivesWholeAnswerCollapse() {compose.setContent {Host {DebateMessage(initial(text="**Original**"))}}
        compose.onNodeWithContentDescription("更多内容操作").performClick();compose.onNodeWithText("按纯文本查看").performClick()
        compose.onNodeWithContentDescription("收起模型 A · 初答").performClick();compose.onNodeWithContentDescription("展开模型 A · 初答").performClick()
        compose.onNodeWithText("**Original**").assertExists();compose.onNodeWithText("Original").assertDoesNotExist()
    }
    @Test fun answerCollapseDoesNotMergeOrRevealReasoning() {val item=initial()
        compose.setContent {Host {DebateMessage(DebateMessageItem.Stage("r",item.stage.copy(reasoning=
            ReasoningRecord("Local reasoning",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Completed))))}}
        compose.onNodeWithText("Local reasoning").assertDoesNotExist();compose.onNodeWithContentDescription("收起模型 A · 初答").performClick()
        compose.onNodeWithContentDescription("展开模型 A · 初答").performClick();compose.onNodeWithText("Local reasoning").assertDoesNotExist()
        compose.onNodeWithContentDescription("查看思考过程").assertExists()
    }
    @Test fun collapseAndExpansionHaveIntermediateHeightsAndSettle() {
        compose.mainClock.autoAdvance=false
        compose.setContent {Host {DebateMessage(initial(text=(1..6).joinToString("\n\n") {"Line $it"}))}}
        compose.mainClock.advanceTimeBy(400)
        fun height()=compose.onNodeWithTag("debateStage-INITIAL_A").getUnclippedBoundsInRoot().let {it.bottom-it.top}
        val full=height();compose.onNodeWithContentDescription("收起模型 A · 初答").performClick();compose.mainClock.advanceTimeBy(96)
        val shrinking=height();assertTrue(shrinking<full);compose.mainClock.advanceTimeBy(400)
        val small=height();assertTrue(shrinking>small)
        compose.onNodeWithContentDescription("展开模型 A · 初答").performClick();compose.mainClock.advanceTimeBy(96)
        val growing=height();assertTrue(growing>small && growing<full);compose.mainClock.advanceTimeBy(400);assertEquals(full,height())
    }
    @Test fun fastReverseSettlesToExpandedWithoutDuplicateBody() {compose.mainClock.autoAdvance=false
        compose.setContent {Host {DebateMessage(initial())}};compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithContentDescription("收起模型 A · 初答").performClick();compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithContentDescription("展开模型 A · 初答").performClick();compose.mainClock.advanceTimeBy(400)
        compose.onAllNodesWithText("Answer A").assertCountEquals(1)
    }
    @Test fun narrowLargeFontToggleRetainsAccessibleTarget() {compose.setContent {MeldwiseTheme {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)) {
            Box(Modifier.width(320.dp)) {DebateMessage(initial())}
        }
    }};compose.onNodeWithContentDescription("收起模型 A · 初答").assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("展开模型 A · 初答").assertIsDisplayed()}
    @Test fun expandedAnswerRestoresSharedCodeRenderer() {compose.setContent {Host {DebateMessage(initial(text="```python\n  print(1)\n```"))}}
        compose.onNodeWithContentDescription("收起模型 A · 初答").performClick();compose.onNodeWithContentDescription("展开模型 A · 初答").performClick()
        compose.onNodeWithText("Python").assertExists();compose.onNodeWithText("  print(1)\n").assertExists()
    }
    @Test fun darkCustomAccentUsesSameDisclosureAndKeepsMetadata() {compose.setContent {MeldwiseTheme(Appearance.Dark,0x336699) {DebateMessage(initial())}}
        compose.onNodeWithContentDescription("收起模型 A · 初答").performClick();compose.onNodeWithText("DeepSeek-Fixture").assertExists()
        compose.onNodeWithContentDescription("展开模型 A · 初答").performClick();compose.onNodeWithText("Answer A").assertExists()}
}
