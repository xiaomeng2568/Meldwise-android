// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xiaomeng2568.meldwise.auth.AuthState
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.ApiKeyState
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Compose host; no Activity cold-start automation, credentials or provider connections. */
@RunWith(AndroidJUnit4::class)
class CollaborateUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val a=ModelRef("chatgpt","synthetic-a");private val b=ModelRef("deepseek","synthetic-b")
    private val config=CollaborateConfig(CollaborateModel(a,"A"),CollaborateModel(b,"B"))
    @Test fun collaborationModeSelectionIsLocalAndDebateStaysUnavailable() {
        var selections=0
        compose.setContent {MeldwiseTheme {ModePicker(true,{},{},{selections++})}}
        compose.onNodeWithText("协作").performClick();compose.onNodeWithText("辩论").assertIsNotEnabled()
        compose.runOnIdle {assertEquals(1,selections)}
    }
    @Test fun sharingCancelMakesZeroSendActions() {
        var sends=0;var cancelled=0
        compose.setContent {MeldwiseTheme {CollaborateSharingDialog(config,{sends++},{cancelled++})}}
        compose.onNodeWithText("取消").performClick();compose.runOnIdle {assertEquals(0,sends);assertEquals(1,cancelled)}
    }
    @Test fun sharingRequiresExplicitContinue() {
        var sends=0
        compose.setContent {MeldwiseTheme {CollaborateSharingDialog(config,{sends++},{})}}
        compose.runOnIdle {assertEquals(0,sends)};compose.onNodeWithText("继续").performClick()
        compose.runOnIdle {assertEquals(1,sends)}
    }
    @Test fun stageUsesSharedAnswerRendererAndSeparateCollapsedReasoning() {
        val stage=CollaborateStage("s",CollaborateStageType.REVIEW,1,config.reviewer,"Visible answer",
            ReasoningRecord("Synthetic visible reasoning",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Completed),CollaborateStageState.Complete)
        compose.setContent {MeldwiseTheme {Column {CollaborateMessage(CollaborateMessageItem.Stage("round",stage))}}}
        compose.onNodeWithTag("assistantOutput").assertExists();compose.onNodeWithText("审阅 · 标准").assertExists()
        compose.onNodeWithText("Visible answer").assertExists();compose.onNodeWithText("Synthetic visible reasoning").assertDoesNotExist()
        compose.onNodeWithContentDescription("查看思考过程").performClick();compose.onNodeWithText("Synthetic visible reasoning").assertExists()
    }
    @Test fun planLimitShowsStageSpecificSafeDetails() {
        val stage=CollaborateStage("s",CollaborateStageType.INITIAL,0,config.primary,state=CollaborateStageState.Failed,error=ErrorKind.PLAN_USAGE_LIMIT)
        compose.setContent {MeldwiseTheme {CollaborateMessage(CollaborateMessageItem.Stage("round",stage))}}
        compose.onNodeWithText(collaborateErrorLabel(ErrorKind.PLAN_USAGE_LIMIT)).assertExists()
        compose.onNodeWithText("查看详情").performClick();compose.onNodeWithText("初答 · providerId=chatgpt · PLAN_USAGE_LIMIT").assertExists()
    }
    @Test fun collabModelSheetSelectionStaysOpenWithoutNetworkActions() {
        var preference by mutableStateOf(ReasoningPreference.Off);var updates=0
        val models=mapOf("chatgpt" to listOf(LlmModel(a.modelId,"A","synthetic",ProviderCapability(emptySet()))),
            "deepseek" to listOf(LlmModel(b.modelId,"B","synthetic",ProviderCapability(emptySet()))))
        val actions=ChatActions({},{},{},{},{},{},{},{},{},{},configureCollaborate={preference=it.pb;updates++})
        compose.setContent {MeldwiseTheme {
            ChatScreen(ScreenState(selected=a,historyRef=a,ready=true),AuthState.Connected(true),ApiKeyState.CONFIGURED,null,
                ProcessingTime(null,null,false),Appearance.System,{},actions,
                FoundationState(catalogs=models,mode=ConversationMode.Collaborate,collaborate=config.copy(reviewer=config.reviewer.copy(preference=preference))))
        }}
        compose.onNodeWithText("协作").performClick();compose.onNodeWithText("深入").performClick()
        compose.onNodeWithText("A 初答 → B 审阅 → A 综合").assertExists()
        compose.runOnIdle {assertEquals(ReasoningPreference.High,preference);assertEquals(1,updates)}
    }
}
