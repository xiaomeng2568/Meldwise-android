package io.github.xiaomeng2568.meldwise

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.io.File

/** Real-device layout evidence with LOCAL SYNTHETIC text. No MainActivity, stores, keys or provider calls. */
@RunWith(AndroidJUnit4::class)
class FlatCompareVisualTest {
    @get:Rule val compose=createComposeRule()
    private var actionsInvoked=0
    private val a=ModelRef("chatgpt","fixture-a")
    private val b=ModelRef("deepseek","fixture-b")
    private fun catalog(id:String,name:String)=listOf(LlmModel(id,name,"local-visual-fixture",ProviderCapability(emptySet())))
    private fun fixture(dark:Boolean=false,scaled:Boolean=false) {
        val run=CompareRun("local-visual","你好",CompareLaneRecord("A",a,"你好！今天想聊点什么？",CompareLaneState.Completed,
            processingDuration=2,modelDisplayName="5.6-Luna"),CompareLaneRecord("B",b,"你好，很高兴见到你。",CompareLaneState.Completed,
            reasoning=ReasoningRecord("这是本地排版样例，用来检查折叠区。\n收到问候后，给出简短自然的回应。",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Completed),
            processingDuration=3,modelDisplayName="V4.1-Flash"),CompareRunState.Completed)
        val catalogs=mapOf("chatgpt" to catalog("fixture-a","5.6-Luna"),"deepseek" to catalog("fixture-b","V4.1-Flash"))
        val actions=ChatActions({actionsInvoked++},{actionsInvoked++},{actionsInvoked++},{actionsInvoked++},{actionsInvoked++},{actionsInvoked++},{actionsInvoked++},{actionsInvoked++},{actionsInvoked++},{actionsInvoked++},compare={actionsInvoked++})
        val mode=if(dark) Appearance.Dark else Appearance.Light
        compose.setContent {MeldwiseTheme(mode,0x366BD5L) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,if(scaled) 2f else 1f)) {
                Box(if(scaled) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                    ChatScreen(ScreenState(providerId="deepseek",ready=true,selected=b,historyRef=b,models=catalogs.getValue("deepseek")),
                        AuthState.Connected(true),ApiKeyState.CONFIGURED,null,ProcessingTime(null,null,false),mode,{},actions,
                        FoundationState(catalogs=catalogs,run=run))
                }
            }
        }}
        compose.waitForIdle()
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val destination=File(instrumentation.context.getExternalFilesDir(null),"flat-compare-visual").apply {mkdirs()}
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: error("SCREENSHOT_UNAVAILABLE")
        try {File(destination,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
        assertEquals("Local visual fixtures must not dispatch actions",0,actionsInvoked)
    }
    @Test fun lightFlatFlowCollapsed() {
        fixture();compose.onNodeWithText("你好").assertExists();compose.onNodeWithText("ChatGPT-5.6-Luna").assertExists()
        compose.onNodeWithText("DeepSeek-V4.1-Flash").assertExists();compose.onAllNodesWithTag("assistantOutput").assertCountEquals(2)
        compose.onNodeWithContentDescription("ChatGPT 提供方").assertExists();compose.onNodeWithContentDescription("DeepSeek 提供方").assertExists()
        compose.onAllNodesWithTag("reasoningDisclosure").assertCountEquals(1);compose.onNodeWithText("查看思考过程").assertExists()
        compose.onNodeWithTag("composer").assertHeightIsEqualTo(48.dp);compose.onNodeWithTag("composerSurface",true).assertHeightIsEqualTo(44.dp)
        capture("01-compare-light-collapsed")
    }
    @Test fun lightReasoningExpandedAndCollapsible() {
        fixture();compose.onNodeWithText("查看思考过程").performClick();compose.onNodeWithText("收起思考过程").assertExists()
        compose.onNodeWithText("你好，很高兴见到你。").assertExists();capture("02-compare-light-expanded")
        compose.onNodeWithText("收起思考过程").performClick();compose.onNodeWithText("查看思考过程").assertExists()
    }
    @Test fun darkFlatFlow() {fixture(dark=true);capture("03-compare-dark-collapsed")}
    @Test fun darkReasoningExpanded() {fixture(dark=true);compose.onNodeWithText("查看思考过程").performClick();capture("04-compare-dark-expanded")}
    @Test fun thinkingChoiceAppliesWithoutSecondConfirmation() {
        fixture(dark=true);compose.onNodeWithText("对比").performClick();compose.onNodeWithText("好了").assertDoesNotExist()
        compose.onNodeWithText("关闭").assertExists();capture("05-compact-settings")
        compose.onNodeWithText("关闭").performClick();compose.onNodeWithText("模型 A：ChatGPT-5.6-Luna").assertDoesNotExist()
        compose.onNodeWithText("ChatGPT-5.6-Luna").assertExists();assertEquals(0,actionsInvoked)
    }
    @Test fun composerWithKeyboardAndMultilineGrowth() {
        fixture();compose.onNodeWithContentDescription("消息输入框").performClick().performTextInput("这是一条本地输入样例")
        capture("06-composer-keyboard")
        compose.onNodeWithContentDescription("消息输入框").performTextInput((1..18).joinToString("\n") {"本地第 $it 行"})
        compose.waitForIdle();val height=compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.height/compose.density.density
        assertTrue(height in 48f..120f);assertEquals(0,actionsInvoked)
    }
    @Test fun width320AndDoubleFontRemainScrollable() {
        fixture(dark=true,scaled=true)
        compose.onNodeWithTag("compareMessageFlow").performScrollToNode(hasText("DeepSeek-V4.1-Flash"))
        compose.onNodeWithContentDescription("消息输入框").assertExists();compose.onNodeWithContentDescription("同时询问").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        capture("07-width320-font2x")
    }
    @Test fun copyUsesRoundControlAndRetainsTouchTarget() {
        compose.setContent {MeldwiseTheme {CopyAction("local sample")}}
        compose.onNodeWithContentDescription("复制内容").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertHasClickAction()
    }
}
