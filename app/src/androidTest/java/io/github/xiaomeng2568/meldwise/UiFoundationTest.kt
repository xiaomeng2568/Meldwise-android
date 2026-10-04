package io.github.xiaomeng2568.meldwise

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xiaomeng2568.meldwise.auth.AuthState
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.ApiKeyState
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Compose host, synthetic UI only. Never launches MainActivity or uses AppContainer. */
@RunWith(AndroidJUnit4::class)
class UiFoundationTest {
    @get:Rule val compose=createComposeRule()
    private val ref=ModelRef(ProviderIds.DEEPSEEK,"synthetic-model")
    private fun message(role:MessageRole,text:String,state:MessageState=MessageState.COMPLETED)=ChatMessage("fixture",null,role,text,state)
    @Test fun completedAssistantHasContentWithoutDeveloperCaption() {
        compose.setContent {MeldwiseTheme {MessageCard(message(MessageRole.ASSISTANT,"**Hello**"),ref,"V4.1")}}
        compose.onNodeWithText("Hello").assertExists();compose.onNodeWithText("助手 · 已完成").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek-V4.1").assertExists();compose.onNodeWithContentDescription("复制内容").assertExists()
    }
    @Test fun userBubbleKeepsLiteralTextAndNoRoleLabel() {
        compose.setContent {MeldwiseTheme {MessageCard(message(MessageRole.USER,"**literal**"),ref,"V4.1")}}
        compose.onNodeWithText("**literal**").assertExists();compose.onNodeWithText("你 · 已完成").assertDoesNotExist()
    }
    @Test fun plainAndCodeSurfacesPreserveTheirSource() {
        compose.setContent {MeldwiseTheme {ContentRenderer(ParsedContent(listOf(PlainTextBlock("  **raw**\n    next"),CodeBlock("# not heading","kotlin")),false))}}
        compose.onNodeWithText("  **raw**\n    next").assertExists();compose.onNodeWithText("# not heading").assertExists();compose.onNodeWithText("kotlin").assertExists()
    }
    @Test fun unavailableReasoningIsAbsent() {
        compose.setContent {MeldwiseTheme {Column {ReasoningPanel(ReasoningSummary());ContentRenderer(ContentParser.parse("final answer"))}}}
        compose.onNodeWithContentDescription("查看思考摘要").assertDoesNotExist();compose.onNodeWithText("final answer").assertExists()
    }
    @Test fun summaryIsCollapsedAndSeparate() {
        compose.setContent {MeldwiseTheme {Column {ReasoningPanel(ReasoningSummary(ReasoningState.Completed,"separate summary"));ContentRenderer(ContentParser.parse("answer"))}}}
        compose.onNodeWithText("separate summary").assertDoesNotExist();compose.onNodeWithContentDescription("查看思考摘要").performClick()
        compose.onNodeWithText("separate summary").assertExists();compose.onNodeWithText("answer").assertExists()
    }
    @Test fun compareLanesRenderIndependentStatesInDarkMode() {
        compose.setContent {MeldwiseTheme(Appearance.Dark) {Column {
            CompareResultCard(CompareLane(ModelRef("chatgpt","same"),"5.5",LaneState.Completed,"one"))
            CompareResultCard(CompareLane(ModelRef("deepseek","same"),"V4.1",LaneState.Cancelled,"two"))
        }}}
        compose.onNodeWithText("已完成").assertDoesNotExist();compose.onNodeWithText("已取消").assertExists()
        compose.onNodeWithText("ChatGPT-5.5").assertExists();compose.onNodeWithText("DeepSeek-V4.1").assertExists()
    }
    @Test fun pickerReturnsExactProviderModelRef() {
        var selected:ModelRef?=null
        val actions=actions(select={selected=it})
        compose.setContent {MeldwiseTheme {ModelPicker(ScreenState(providerId="deepseek",models=listOf(LlmModel("actual-id","V4.1","fixture",ProviderCapability(emptySet())))),true,actions,{},{})}}
        compose.onNodeWithText("DeepSeek-V4.1").performClick();compose.runOnIdle {assertEquals(ModelRef("deepseek","actual-id"),selected)}
    }
    @Test fun largeFontsKeepComposerAndDisabledFutureFeaturesSafe() {
        compose.setContent {MeldwiseTheme {CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)) {
            Box(Modifier.width(320.dp).height(640.dp)) {
                ChatScreen(ScreenState(),AuthState.Disconnected,ApiKeyState.MISSING,null,ProcessingTime(null,null,false),Appearance.System,{},actions())
            }
        }}}
        compose.onNodeWithContentDescription("消息输入框").assertExists();compose.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
        compose.onNodeWithContentDescription("对比，后续开放").assertDoesNotExist()
    }
    private fun screen(deep:Boolean=true,actions:ChatActions=actions(),foundation:FoundationState=FoundationState()) {
        compose.setContent {MeldwiseTheme {ChatScreen(ScreenState(providerId=if(deep) "deepseek" else "chatgpt",historyRef=ref),
            AuthState.Disconnected,ApiKeyState.CONFIGURED,null,ProcessingTime(null,null,false),Appearance.System,{},actions,foundation)}}
    }
    @Test fun composerStartsCompactAndGrowsWithinCap() {
        screen();compose.onNodeWithTag("composer").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("composerSurface",useUnmergedTree=true).assertHeightIsEqualTo(44.dp)
        compose.onNodeWithContentDescription("消息输入框").performTextInput((1..20).joinToString("\n") {"line $it"})
        compose.waitForIdle();compose.onNodeWithTag("composer").assertHeightIsAtLeast(48.dp)
        val density=compose.density.density
        assertTrue(compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.height/density<=140f)
    }
    @Test fun copyControlHasFullTouchTargetAndButtonSemantics() {
        compose.setContent {MeldwiseTheme {CopyAction("local fixture")}}
        compose.onNodeWithContentDescription("复制内容").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertHasClickAction()
    }
    @Test fun compareOpensConfigurationWithoutSending() {
        var calls=0;screen(actions=ChatActions({},{},{},{},{},{},{},{},{calls++},{},compare={calls++}))
        compose.onNodeWithContentDescription("选择对话模式").performClick();compose.onNodeWithText("对比").performClick()
        compose.onNodeWithText("模型 A：请选择").assertExists();compose.onNodeWithText("模型 B：请选择").assertExists()
        compose.runOnIdle {assertEquals(0,calls)}
    }
    @Test fun thinkingSelectsActualDeepseekEffort() {
        var preference:ReasoningPreference?=null
        screen(actions=ChatActions({},{},{},{},{},{},{},{},{},{},thinking={preference=it}))
        compose.onNodeWithContentDescription("更多输入选项").performClick();compose.onNodeWithText("思考").performClick()
        compose.onNodeWithText("深入").performClick();compose.runOnIdle {assertEquals(ReasoningPreference.High,preference)}
    }
    @Test fun chatgptDoesNotOfferUnconfirmedEffortFields() {
        screen(deep=false)
        compose.onNodeWithContentDescription("更多输入选项").performClick();compose.onNodeWithText("思考").performClick()
        compose.onNodeWithText("ChatGPT 使用模型默认设置。").assertExists();compose.onNodeWithText("深入").assertDoesNotExist()
    }
    @Test fun reasoningStreamsSeparatelyFromAnswer() {
        compose.setContent {MeldwiseTheme {MessageCard(ChatMessage("local",null,MessageRole.ASSISTANT,"ANSWER",MessageState.STREAMING,
            ReasoningRecord("VISIBLE_REASONING",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Streaming)),ref,"V4.1")}}
        compose.onNodeWithText("ANSWER").assertExists();compose.onNodeWithText("VISIBLE_REASONING").assertDoesNotExist()
        compose.onNodeWithContentDescription("查看思考过程").performClick();compose.onNodeWithText("VISIBLE_REASONING").assertExists()
        compose.onNodeWithText("VISIBLE_REASONINGANSWER").assertDoesNotExist()
    }
    @Test fun pickerPreservesIdentityAcrossCachedProviders() {
        var selected:ModelRef?=null
        val models=listOf(LlmModel("same-id","V4.1","fixture",ProviderCapability(emptySet())))
        compose.setContent {MeldwiseTheme {ModelPicker(ScreenState(providerId="deepseek"),true,actions(),{},{},
            catalogs=mapOf("chatgpt" to models,"deepseek" to models),onPick={selected=it})}}
        compose.onNodeWithText("DeepSeek-V4.1").performClick();compose.runOnIdle {assertEquals(ModelRef("deepseek","same-id"),selected)}
    }
    @Test fun accentPickerSupportsCustomColorWithoutNetwork() {
        var accent:Long?=null
        compose.setContent {MeldwiseTheme {AccentPicker(null) {accent=it}}}
        compose.onNodeWithText("自定义色号").performTextClearance();compose.onNodeWithText("自定义色号").performTextInput("336699")
        compose.onNodeWithText("应用").performClick();compose.runOnIdle {assertEquals(0x336699L,accent)}
    }
    private fun actions(select:(ModelRef)->Unit = {}) = ChatActions({},select,{},{},{},{},{},{},{},{})
}
