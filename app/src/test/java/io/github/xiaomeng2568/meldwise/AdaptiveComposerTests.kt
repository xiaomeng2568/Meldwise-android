// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import androidx.compose.ui.unit.dp
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AdaptiveComposerTests {
    private fun ui(path: String) = File(System.getProperty("projectRoot"), "app/src/main/java/io/github/xiaomeng2568/meldwise/ui/$path").readText()
    private fun summary(mode: HistoryCategory, name: String = "GPT-5.6", effort: String = "默认", review: String = "标准") = composerSummary(mode, name, effort, review)
    private fun composer() = ui("components/AdaptiveComposer.kt")
    @Test fun emptyUnfocusedImeHiddenIsCompact() { assertFalse(composerExpanded("", false, false)) }
    @Test fun focusExpandsEvenWithEmptyInput() { assertTrue(composerExpanded("", true, false)) }
    @Test fun imeVisibilityExpandsWithoutFocus() { assertTrue(composerExpanded("", false, true)) }
    @Test fun multilineDraftExpands() { assertTrue(composerExpanded("a\nb", false, false)) }
    @Test fun nonEmptyDraftRemainsExpandedAfterKeyboardDismissal() { assertTrue(composerExpanded("draft", false, false)) }
    @Test fun clearingDraftReturnsToRestWhenNotFocused() { assertFalse(composerExpanded("", false, false)); assertTrue(composerExpanded("", true, false)) }
    @Test fun whitespaceDraftStillHasStableEditingLayout() { assertTrue(composerExpanded(" ", false, false)) }
    @Test fun singleSummaryUsesExistingModelPickerRoute() { assertEquals(ChatPanel.Models, summary(HistoryCategory.Chat).configuration); assertEquals("GPT-5.6 · 默认", summary(HistoryCategory.Chat).label) }
    @Test fun compareSummaryUsesExistingSetup() { assertEquals(ComposerSummary("双模型回答", ChatPanel.CompareSetup), summary(HistoryCategory.Compare)) }
    @Test fun collaborateSummaryUsesExistingSetupAndReviewPolicy() { assertEquals(ComposerSummary("协作 · 严格", ChatPanel.CollaborateSetup), summary(HistoryCategory.Collaborate, review = "严格")); assertEquals("协作 · 简洁", summary(HistoryCategory.Collaborate, review = "简洁").label) }
    @Test fun multiModelSummaryDoesNotContainLongPairOrSingleEffort() { val long = "x".repeat(300); assertEquals("双模型回答", summary(HistoryCategory.Compare, long, "深入").label); assertEquals("协作 · 标准", summary(HistoryCategory.Collaborate, long, "尽力").label) }
    @Test fun singleModelNameIsNormalizedAndBounded() { assertEquals("Model A · 深入", summary(HistoryCategory.Chat, "  Model\n\tA  ", "深入").label); assertEquals("a".repeat(20)+"… · 默认", summary(HistoryCategory.Chat, "a".repeat(300)).label) }
    @Test fun mixedChineseEnglishNameIsSafe() { val label = summary(HistoryCategory.Chat, "模型Alpha😀".repeat(5)).label; assertTrue(label.endsWith(" · 默认")); assertFalse(label.substringBefore('…').last().isHighSurrogate()) }
    @Test fun missingModelNameHasSafeFallback() { assertEquals("选择模型 · 默认", summary(HistoryCategory.Chat, "\n ").label) }
    @Test fun summaryHasItsOwnControlNotEditableDecoration() { val s = composer(); val decoration = s.substringAfter("decorationBox =").substringBefore("if (!expanded) sendControl()"); assertFalse(decoration.contains("summary.label")); assertTrue(s.substringAfter("testTag(\"composerControls\")").contains("summary.label")); assertTrue(s.contains("onClick = onConfiguration")) }
    @Test fun oneTextFieldSurvivesLayoutStateChanges() { assertEquals(1, Regex("\\bBasicTextField\\(").findAll(composer()).count()); assertTrue(composer().contains(".onFocusChanged")); assertFalse(composer().contains("FocusRequester")) }
    @Test fun controlsOnlyAppearInExpandedRow() { val s = composer(); assertTrue(s.contains("if (expanded) Row")); assertTrue(s.contains("if (!expanded) optionsControl()")); assertTrue(s.contains("if (!expanded) sendControl()")) }
    @Test fun narrowWidthsReserveBoth48dpActionsBeforeSummary() { listOf(320,360,412,600,840).forEach { val width = MeldwiseContentMetrics.composerWidth(it.dp); assertTrue(width - Sizes.touch*2 > 0.dp) }; val s = composer(); assertTrue(s.contains("Modifier.weight(1f).testTag(\"composerSummary\")")); assertTrue(s.contains("maxLines = 1, overflow = TextOverflow.Ellipsis")) }
    @Test fun sendAndStopKeepOriginalAvailabilityAndCallbacks() { val s = composer(); assertTrue(s.contains("if (busy) Glyph.Stop else Glyph.Send")); assertTrue(s.contains("if (busy) onStop else onSend")); assertTrue(s.contains("enabled = busy || canSend")); assertTrue(s.contains("\"取消全部\"")); assertTrue(s.contains("\"停止当前操作\"")) }
    @Test fun existingPlusActionIsFunctionalAndNoFakeCapabilitiesExist() { val s = composer(); assertTrue(s.contains("onOptions(!options)")); assertTrue(s.contains("onOptions(false); onThinking()")); listOf("附件", "麦克风", "语音", "attachment", "microphone").forEach { assertFalse(s.contains(it)) } }
    @Test fun fiveLinesAnd120dpTextLimitAreRetained() { assertEquals(120.dp, Sizes.composerMax); assertTrue(composer().contains("maxLines = 5")); assertTrue(composer().contains("max = Sizes.composerMax")); assertEquals(44.dp, Sizes.composerMin) }
    @Test fun textRegionAlsoKeepsFull48dpTouchHeight() { assertTrue(composer().contains("heightIn(min = Sizes.touch, max = Sizes.composerMax)")) }
    @Test fun allIconActionsKeepSharedAccessibleTargets() { assertEquals(48.dp, Sizes.touch); assertTrue(composer().contains("SoftAction(")); assertTrue(composer().contains("contentDescription = \"消息输入框\"")); assertTrue(composer().contains("contentDescription = \"模型与模式设置\"")); assertTrue(ui("components/SoftAction.kt").contains("Modifier.size(Sizes.touch)")) }
    @Test fun singleImeOwnerIsUnchanged() { assertEquals(1, Regex("\\.imePadding\\(").findAll(ui("ChatScreen.kt")).count()); assertFalse(composer().contains("imePadding(")); assertTrue(ui("ChatScreen.kt").contains("imeVisible=keyboardVisible")) }
    @Test fun fadeStays12dpAndDoesNotInterceptTouches() { assertEquals(12.dp, MeldwiseContentMetrics.composerFade); val s = ui("ChatScreen.kt").substringAfter("val background=MaterialTheme.colorScheme.background").substringBefore("screen.error?"); assertTrue(s.contains("drawBehind")); assertFalse(s.contains("clickable")); assertFalse(s.contains("pointerInput")) }
    @Test fun composerRemainsInNormalLayoutFlow() { val s = ui("ChatScreen.kt"); assertTrue(s.contains("AdaptiveComposer(")); assertFalse(s.contains("private fun Composer(")); assertFalse(composer().contains("offset(")); assertFalse(composer().contains("imePadding"+"(")) }
    @Test fun systemScaledSharedTweenWithoutDecorativeMotion() { val s = composer(); assertTrue(s.contains("animateContentSize(tween(Motion.switchMs, easing = Motion.easing))")); listOf("spring(", "scale(", "graphicsLayer", "shadow(").forEach { assertFalse(s.contains(it)) } }
    @Test fun acceptedPressFeedbackRemainsShared() { val s = composer(); assertTrue(s.contains("MeldwiseTextButton(")); assertTrue(s.contains("MeldwiseFilterChip(")); assertFalse(s.contains("ripple(")); assertEquals(100, io.github.xiaomeng2568.meldwise.ui.components.PressFeedbackPolicy.pressMs) }
    @Test fun keyboardFirstBackAndOverlaySemanticsRemainIntact() { val s = ui("ChatScreen.kt"); assertTrue(s.contains("navigation.handlesBack && !keyboardVisible")); assertTrue(s.contains("BackHandler(enabled=!keyboardVisible)")); assertTrue(s.contains("onDismissRequest={navigation=navigation.dismiss();pickingLane=null}")) }
    @Test fun summaryOpensExistingSurfaceWithoutProviderSideEffect() { val s = ui("ChatScreen.kt").substringAfter("imeVisible=keyboardVisible").substringBefore("Text(if(compareMode"); assertTrue(s.contains("navigation=navigation.open(summary.configuration)")); assertFalse(s.contains("actions.")) }
    @Test fun noRequestOrCoreImportInComposerImplementation() { val s = composer(); listOf("auth.", "network.", "provider.", "data.", "storage.", "ConversationContextBuilder", "CollaborateExecutor").forEach { assertFalse(s.contains(it)) } }
}
