// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.provider.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CommunityUiPolicyTests {
    private fun source(name:String)=File(System.getProperty("projectRoot"),"app/src/main/java/io/github/xiaomeng2568/meldwise/$name.kt").readText()
    @Test fun pressAndReleaseHaveShortConsistentDurations() {assertEquals(100,PressFeedbackPolicy.pressMs);assertEquals(140,PressFeedbackPolicy.releaseMs)}
    @Test fun idleFeedbackIsTransparent() {assertEquals(0f,PressFeedbackPolicy.alpha(true,false),0f)}
    @Test fun disabledFeedbackStaysTransparent() {assertEquals(0f,PressFeedbackPolicy.alpha(false,true,true),0f)}
    @Test fun pressIsSubtleAndUniform() {assertTrue(PressFeedbackPolicy.alpha(true,true) in .02f.. .06f);assertFalse(source("ui/components/MeldwisePress").contains("drawCircle"))}
    @Test fun keyboardFocusAndHoverShareLightFeedback() {assertTrue(PressFeedbackPolicy.alpha(true,false,true) in .01f.. .04f)}
    @Test fun pressTakesPrecedenceOverFocus() {assertEquals(PressFeedbackPolicy.pressedAlpha,PressFeedbackPolicy.alpha(true,true,true),0f)}
    @Test fun nativeButtonClicksAreNotDuplicatedByGestures() {val s=source("ui/components/MeldwisePress");assertFalse(s.contains("pointerInput"));assertTrue(s.contains("indication=null"));assertTrue(s.contains("heightIn(min=Sizes.touch)"))}
    @Test fun materialRadialRipplesAreDisabledInSharedTheme() {assertTrue(source("ui/theme/MeldwiseTheme").contains("LocalRippleConfiguration provides null"))}
    @Test fun pickerStartsWithSelectedLaneProvider() {assertEquals("deepseek",ModelPickerState.initial(ModelRef("deepseek","lane"),"chatgpt").expandedProvider)}
    @Test fun pickerWithoutSelectionUsesCurrentProvider() {assertEquals("chatgpt",ModelPickerState.initial(null,"chatgpt").expandedProvider)}
    @Test fun browsingShowsOnlyOneProvider() {val state=ModelPickerState("chatgpt").browse("deepseek");assertTrue(state.shows("deepseek"));assertFalse(state.shows("chatgpt"))}
    @Test fun unsupportedProviderCannotExpand() {val state=ModelPickerState("chatgpt");assertEquals(state,state.browse("unknown"))}
    @Test fun browsingProviderDoesNotMutateGlobalIdentity() {val s=source("ui/SettingsPanels").substringAfter("internal fun ModelPicker").substringBefore("internal fun DiagnosticsPanel");assertFalse(s.contains("actions.chooseProvider"));assertTrue(s.contains("browser=browser.browse(id)"))}
    @Test fun catalogIsOnlyRenderedForExpandedProvider() {assertTrue(source("ui/SettingsPanels").contains("val listed=if(active) catalogs[id]"))}
    @Test fun lanePickerHasSeparateRefreshNotGlobalProviderChange() {val s=source("ui/SettingsPanels");assertTrue(s.contains("actions.refreshModels(id)"));assertTrue(source("ui/ChatScreen").contains("PickerScope.Lane"))}
    @Test fun arrowIsInsideTitleRowAndSubtitleIndependent() {val s=source("ui/components/ModelTitle");assertTrue(s.indexOf("modelTitleArrow")<s.indexOf("modelSubtitle"));assertTrue(s.contains("weight(1f,fill=false)"));assertTrue(s.contains("opticalSize=18.dp"));assertTrue(s.contains("maxLines=1"));assertTrue(s.contains("TextAlign.Center"))}
    @Test fun compareSubmissionDoesNotGainCollaborateStrategies() {val s=source("ui/ChatScreen").substringAfter("class CompareSubmission").substringBefore("class CollaborateSubmission");assertFalse(s.contains("ReviewIntensity"));assertFalse(s.contains("SynthesisRole"))}
    @Test fun mathCanvasUsesThemeTintAndNoProviderNetwork() {val s=source("ui/components/MathRenderer");assertTrue(s.contains("onSurface.toArgb()"));assertTrue(s.contains("nativeCanvas"));listOf("WebView","http://","https://","streamResponse(").forEach {assertFalse(s.contains(it))}}
}
