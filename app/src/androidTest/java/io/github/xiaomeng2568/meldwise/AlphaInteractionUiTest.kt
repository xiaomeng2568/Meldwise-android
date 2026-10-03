package io.github.xiaomeng2568.meldwise

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xiaomeng2568.meldwise.auth.AuthState
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.network.ModelCatalogDiagnostic
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.ApiKeyState
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Compose host only. No MainActivity, AppContainer, private storage or provider calls. */
@RunWith(AndroidJUnit4::class)
class AlphaInteractionUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val ref=ModelRef("deepseek","synthetic-model")
    private val model=LlmModel(ref.modelId,"synthetic","provider-catalog",ProviderCapability(emptySet()))
    private var picked by mutableStateOf<ModelRef?>(null)
    private var effort by mutableStateOf(ReasoningPreference.Off)
    private var networkActions=0
    private var deleted=false
    private var moved=0
    private var dismissed:Long?=null
    private var catalogStatus by mutableStateOf(CatalogUiState())
    private fun fixture(status:CatalogUiState=CatalogUiState(),selected:Boolean=false) {
        catalogStatus=status
        if(selected) picked=ref
        val actions=ChatActions({},{picked=it},{networkActions++},{networkActions++},{},{},{},{},{networkActions++},{},
            thinking={effort=it},compare={networkActions++},dismissNotice={dismissed=it;catalogStatus=catalogStatus.copy(notices=catalogStatus.notices.filterNot {n->n.id==it})})
        compose.setContent {MeldwiseTheme {
            ChatScreen(ScreenState(providerId="deepseek",ready=true,models=listOf(model),selected=picked,historyRef=ref),
                AuthState.Disconnected,ApiKeyState.CONFIGURED,null,ProcessingTime(null,null,false),Appearance.System,{},actions,
                FoundationState(catalogs=mapOf("deepseek" to listOf(model)),thinking=effort,catalogStatus=catalogStatus))
        }}
    }
    private fun back() {InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);compose.waitForIdle()}

    @Test fun topPlusOpensLocalModeChooserAndDebateStaysDisabled() {
        fixture();compose.onNodeWithContentDescription("选择对话模式").performClick()
        compose.onNodeWithText("对话").assertExists();compose.onNodeWithText("对比").assertExists()
        compose.onNodeWithText("辩论").assertIsNotEnabled();assertEquals(0,networkActions)
    }
    @Test fun composerPlusDoesNotDuplicateModeChooser() {
        fixture();compose.onNodeWithContentDescription("更多输入选项").performClick()
        compose.onNodeWithText("思考").assertExists();compose.onNodeWithText("对比").assertDoesNotExist()
        assertEquals(0,networkActions)
    }
    @Test fun modelAndThinkingSaveWithoutDismissingPicker() {
        fixture();compose.onNodeWithText("DeepSeek-synthetic").performClick()
        compose.onNodeWithText("DeepSeek-synthetic").performClick()
        compose.onNodeWithText("深入").performClick();compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("选择模型").assertExists();compose.onNodeWithText("深入").assertExists()
        compose.runOnIdle {assertEquals(ref,picked);assertEquals(ReasoningPreference.Off,effort);assertEquals(0,networkActions)}
    }
    @Test fun androidBackReturnsFromAppearanceToSettingsThenChat() {
        fixture();compose.onNodeWithContentDescription("打开菜单").performClick();compose.onNodeWithText("外观").performClick()
        compose.onNodeWithText("跟随系统").assertExists();back()
        compose.onNodeWithText("提供方与账号").assertExists();compose.onNodeWithText("跟随系统").assertDoesNotExist()
        back();compose.onNodeWithText("设置").assertDoesNotExist();compose.onNodeWithContentDescription("消息输入框").assertExists()
        assertFalse(compose.activity.isFinishing);assertEquals(0,networkActions)
    }
    @Test fun androidBackFromLanePickerReturnsToCompareSetup() {
        fixture();compose.onNodeWithContentDescription("选择对话模式").performClick();compose.onNodeWithText("对比").performClick()
        compose.onNodeWithText("模型 B：请选择").performClick();compose.onNodeWithText("选择模型").assertExists()
        back();compose.onNodeWithText("模型 B：请选择").assertExists();assertFalse(compose.activity.isFinishing);assertEquals(0,networkActions)
    }
    @Test fun cachedSelectionIsUsableWhileCatalogRefreshes() {
        fixture(CatalogUiState(loading=setOf("deepseek")),selected=true)
        compose.onNodeWithContentDescription("消息输入框").performTextInput("local fixture")
        compose.onNodeWithContentDescription("发送消息").assertIsEnabled();assertEquals(0,networkActions)
    }
    @Test fun failureBannerShowsOnlySafeHttpDetailAndDismissAction() {
        compose.mainClock.autoAdvance=false
        val notice=CatalogNotice(7,"deepseek",CatalogNoticeKind.Failure,ErrorKind.AUTHORIZATION,ModelCatalogDiagnostic(httpStatus=403))
        fixture(CatalogUiState(notices=listOf(notice)));compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("HTTP 403 · 权限不足").assertExists()
        compose.onNodeWithContentDescription("关闭加载提示").performClick();compose.runOnIdle {assertEquals(7L,dismissed);assertEquals(0,networkActions)}
    }
    @Test fun historyMenuReordersAndDeletesOnlySelectedRow() {
        compose.setContent {MeldwiseTheme {HistoryRow("synthetic history","DeepSeek-synthetic",false,true,true,{}, {deleted=true}, {moved=it})}}
        compose.onNodeWithContentDescription("记录操作").performClick();compose.onNodeWithText("上移").performClick()
        compose.runOnIdle {assertEquals(-1,moved);assertFalse(deleted)}
        compose.onNodeWithContentDescription("记录操作").performClick();compose.onNodeWithText("删除").performClick()
        compose.runOnIdle {assertTrue(deleted);assertEquals(0,networkActions)}
    }
    private fun noticeWhileModelSheetOpen(kind:CatalogNoticeKind) {
        compose.mainClock.autoAdvance=false
        fixture();compose.onNodeWithText("DeepSeek-synthetic").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {catalogStatus=CatalogUiState(notices=listOf(CatalogNotice(9,"deepseek",kind,
            if(kind==CatalogNoticeKind.Failure) ErrorKind.AUTHORIZATION else null,
            if(kind==CatalogNoticeKind.Failure) ModelCatalogDiagnostic(httpStatus=403) else null)))}
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(isPopup()).assertExists()
    }
    @Test fun successNoticeFloatsAboveOpenModelSheetWithoutDismissingIt() {
        noticeWhileModelSheetOpen(CatalogNoticeKind.Success)
        compose.onNodeWithText("DeepSeek · 模型列表已更新").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭加载提示").performClick()
        compose.onNodeWithText("选择模型").assertIsDisplayed()
        compose.runOnIdle {assertEquals(9L,dismissed);assertEquals(0,networkActions)}
    }
    @Test fun failureNoticeAboveModelSheetOpensSanitizedDetails() {
        noticeWhileModelSheetOpen(CatalogNoticeKind.Failure)
        compose.onNodeWithText("HTTP 403 · 权限不足").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("模型加载详情").assertIsDisplayed()
        compose.onNodeWithText("providerId=deepseek").assertExists()
        compose.runOnIdle {assertEquals(9L,dismissed);assertEquals(0,networkActions)}
    }
    @Test fun warningNoticeFloatsAboveModelSheet() {
        noticeWhileModelSheetOpen(CatalogNoticeKind.Warning)
        compose.onNodeWithText("模型缓存暂不可用").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("选择模型").assertIsDisplayed()
        compose.runOnIdle {assertEquals(0,networkActions)}
    }
    @Test fun sheetNavigationDoesNotRestartNoticeTimeout() {
        noticeWhileModelSheetOpen(CatalogNoticeKind.Success)
        compose.mainClock.advanceTimeBy(2000)
        back();compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("DeepSeek · 模型列表已更新").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("DeepSeek · 模型列表已更新").assertDoesNotExist()
        compose.runOnIdle {assertEquals(9L,dismissed);assertEquals(0,networkActions)}
    }
}
