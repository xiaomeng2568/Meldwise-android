// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import io.github.xiaomeng2568.meldwise.provider.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic hosts only; this gate compiles/packages these tests but never runs them on a device. */
@RunWith(AndroidJUnit4::class)
class CommunityFeedbackUiTest {
    @get:Rule val compose=createComposeRule()
    private val caps=ProviderCapability(emptySet())
    private val catalogs=mapOf("chatgpt" to listOf(LlmModel("a","A","fixture",caps)),"deepseek" to listOf(LlmModel("b","B","fixture",caps)))
    private fun actions(choose:(String)->Unit={},pick:(ModelRef)->Unit={})=ChatActions(choose,pick,{},{},{},{},{},{},{},{})
    @Test fun cachedProvidersDoNotBothExpandAndLaneBrowseDoesNotChooseProvider() {
        var changed=0;var chosen:ModelRef?=null
        compose.setContent {MeldwiseTheme {ModelPicker(ScreenState(providerId="chatgpt"),true,actions({changed++},{chosen=it}),{},{},catalogs=catalogs,
            scope=PickerScope.Lane)}}
        compose.onNodeWithText("ChatGPT-A").assertExists();compose.onNodeWithText("DeepSeek-B").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek").performClick();compose.onNodeWithText("ChatGPT-A").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek-B").performClick();compose.runOnIdle {assertEquals(0,changed);assertEquals(ModelRef("deepseek","b"),chosen)}
        compose.onNodeWithText("选择模型").assertExists()
    }
    @Test fun thinkingControlsOnlyAppearUnderSelectedExpandedModel() {
        compose.setContent {MeldwiseTheme {ModelPicker(ScreenState(providerId="chatgpt"),true,actions(),{},{},catalogs=catalogs,scope=PickerScope.Lane,
            selection=ModelRef("deepseek","b"),preference=ReasoningPreference.High)}}
        compose.onAllNodesWithText("深入").assertCountEquals(1);compose.onNodeWithText("ChatGPT").performClick();compose.onNodeWithText("深入").assertDoesNotExist()
    }
    @Test fun arrowStaysAdjacentToShortTitleAboveLongSubtitle() {
        compose.setContent {MeldwiseTheme {Box(Modifier.width(220.dp)) {ModelTitle("协作","ChatGPT-Synthetic-long-A → DeepSeek-Synthetic-long-B") {}}}}
        val title=compose.onNodeWithText("协作",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val arrow=compose.onNodeWithTag("modelTitleArrow",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val sub=compose.onNodeWithTag("modelSubtitle",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue(arrow.left-title.right<=compose.density.density*8);assertTrue(sub.top>=arrow.bottom)
    }
    @Test fun disabledSoftActionHasNoCallbackAndRetainsTouchTarget() {
        var clicks=0;compose.setContent {MeldwiseTheme {SoftAction(Glyph.Copy,"fixture copy",{clicks++},enabled=false)}}
        compose.onNodeWithContentDescription("fixture copy").assertIsNotEnabled().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("fixture copy").performTouchInput {click()};compose.runOnIdle {assertEquals(0,clicks)}
    }
    @Test fun pressedStateAndReleaseKeepOriginalSingleClick() {
        var clicks=0;compose.setContent {MeldwiseTheme {MeldwiseTextButton({clicks++},Modifier.testTag("press")) {Text("Tap")}}}
        compose.onNodeWithTag("press").performTouchInput {down(center)}
        compose.onNodeWithTag("press").assert(SemanticsMatcher.expectValue(MeldwisePressedKey,true))
        compose.onNodeWithTag("press").performTouchInput {up()}
        compose.runOnIdle {assertEquals(1,clicks)}
        compose.onNodeWithTag("press").assert(SemanticsMatcher.expectValue(MeldwisePressedKey,false))
    }
    @Test fun selectedChipRetainsSelectedSemantics() {
        compose.setContent {MeldwiseTheme {MeldwiseFilterChip(true,{}, {Text("Selected")})}}
        compose.onNodeWithText("Selected").assertIsSelected().assertHasClickAction()
    }
    @Test fun nativeFractionEventuallyRendersWithSourceAccessibility() {
        val source="\\[\\frac{1}{2}\\]"
        compose.setContent {MeldwiseTheme(Appearance.Dark) {ContentRenderer(ContentParser.parse(source))}}
        compose.waitUntil(5000) {compose.onAllNodesWithTag("nativeMath").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription(source).assertExists()
    }
    @Test fun incompleteFormulaRemainsVisibleLiteral() {
        val source="\\[\\frac{x}{"
        compose.setContent {MeldwiseTheme {ContentRenderer(ContentParser.parse(source))}}
        compose.onNodeWithText(source).assertExists();compose.onNodeWithTag("nativeMath").assertDoesNotExist()
    }
}
