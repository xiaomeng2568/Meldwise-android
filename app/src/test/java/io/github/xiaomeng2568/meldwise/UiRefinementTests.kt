// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Presentation contracts, not pixel goldens or a claim of device visual acceptance. */
class UiRefinementTests {
    private val metrics=MeldwiseContentMetrics
    private fun ui(path:String)=File(System.getProperty("projectRoot"),"app/src/main/java/io/github/xiaomeng2568/meldwise/ui/$path").readText()
    private fun ratio(a:Color,b:Color):Float {
        val x=a.luminance();val y=b.luminance();return (maxOf(x,y)+.05f)/(minOf(x,y)+.05f)
    }
    private fun colors(dark:Boolean,accent:Long?=null) {
        val c=accentColors(dark,accent)
        listOf(c.onSurface to c.background,c.onSurfaceVariant to c.background,c.onSurfaceVariant to c.surfaceContainerLow,
            c.onPrimaryContainer to c.primaryContainer,c.error to c.background,c.onPrimary to c.primary).forEach {
            assertTrue("presentation contrast",ratio(it.first,it.second)>=4.5f)
        }
    }
    @Test fun narrowPhoneAxisUsesAvailableWidth() {assertEquals(320.dp,metrics.axisWidth(320.dp));assertEquals(288.dp,metrics.answerWidth(320.dp))}
    @Test fun normalPhoneAxisUsesAvailableWidth() {assertEquals(412.dp,metrics.axisWidth(412.dp));assertEquals(380.dp,metrics.answerWidth(412.dp))}
    @Test fun widePhoneIsStillProportional() {assertEquals(600.dp,metrics.axisWidth(600.dp));assertEquals(568.dp,metrics.answerWidth(600.dp))}
    @Test fun foldableReadingWidthIsBounded() {assertEquals(Sizes.contentMax,metrics.axisWidth(840.dp));assertEquals(metrics.answerWidth(840.dp),metrics.answerWidth(1200.dp))}
    @Test fun composerIsOnlySlightlyWiderThanAnswer() {listOf(320,412,840).forEach {assertEquals(8.dp,metrics.composerWidth(it.dp)-metrics.answerWidth(it.dp))}}
    @Test fun userBubbleRemainsBoundedAcrossWidths() {listOf(320,412,840).forEach {val content=metrics.answerWidth(it.dp);assertEquals(content*Sizes.userFraction,metrics.userWidth(content));assertTrue(metrics.userWidth(content)<content)}}
    @Test fun impossibleWidthsNeverBecomeNegative() {listOf((-10).dp,0.dp,10.dp).forEach {assertTrue(metrics.answerWidth(it)>=0.dp);assertTrue(metrics.composerWidth(it)>=0.dp)};assertEquals(0.dp,metrics.userWidth((-1).dp))}
    @Test fun noticeStaysWithinNarrowContentAxis() {assertEquals(288.dp,metrics.noticeWidth(320.dp));assertTrue(metrics.noticeWidth(320.dp)<=metrics.answerWidth(320.dp))}
    @Test fun noticeIsBoundedOnWideScreens() {assertEquals(Sizes.noticeMax,metrics.noticeWidth(840.dp));assertEquals(56.dp,metrics.leadingInset(840.dp))}
    @Test fun phoneLeadingInsetMatchesTranscript() {assertEquals(metrics.conversationInset,metrics.leadingInset(320.dp))}
    @Test fun stageAndMessageSpacingHaveDifferentRoles() {assertTrue(metrics.stageGap<metrics.messageGap);assertEquals(Space.small,metrics.bodyGap)}
    @Test fun reasoningIndentUsesExistingSpacing() {assertEquals(Space.medium,metrics.reasoningIndent);assertTrue(metrics.reasoningIndent<metrics.conversationInset)}
    @Test fun originalComposerAndTouchGeometryIsPreserved() {assertEquals(44.dp,Sizes.composerMin);assertEquals(48.dp,Sizes.touch);assertEquals(120.dp,Sizes.composerMax)}
    @Test fun sourceAndFormattedModesAreExclusive() {assertEquals(AnswerContentMode.Source,answerContentMode(true));assertEquals(AnswerContentMode.Formatted,answerContentMode(false));assertEquals(2,AnswerContentMode.entries.size)}
    @Test fun answerDoesNotAppendDuplicatePlainText() {val s=ui("components/MessageCard.kt");assertTrue(s.contains("when(answerContentMode(plain))"));assertTrue(s.contains("showCopy=false"));assertFalse(s.substringAfter("@Composable private fun MessageActions").contains("LiteralSurface("))}
    @Test fun ordinaryAssistantHasNoCardShell() {val s=ui("components/MessageCard.kt").substringAfter("@Composable fun AssistantOutput").substringBefore("@Composable private fun MessageActions");assertFalse(Regex("\\bSurface\\(").containsMatchIn(s));assertFalse(Regex("\\bCard\\(").containsMatchIn(s));assertTrue(s.contains("ContentRenderer("))}
    @Test fun originalMathAndCodeParserRemainAvailable() {val p=ContentParser.parse("before $"+"x^2$\n\n\\[\\frac{1}{2}\\]\n\n```kotlin\n\$x\$\n```");assertTrue(p.blocks.any {it is MathBlock});assertTrue(p.blocks.any {it is CodeBlock});assertTrue(ui("components/ContentRenderer.kt").contains("is MathBlock -> DisplayMath(block)"))}
    @Test fun sourceSwitchDoesNotModifyProviderText() {val text="**answer**\n\\[x^2\\]\n```text\nraw\n```";ContentParser.parse(text);answerContentMode(true);assertEquals("**answer**\n\\[x^2\\]\n```text\nraw\n```",text)}
    @Test fun onlySynthesisHasPrimaryHeading() {assertEquals(listOf(AnswerRole.Synthesis),AnswerRole.entries.filter {it.primaryHeading()})}
    @Test fun independentCompareIsNotASequentialStage() {assertFalse(AnswerRole.Independent.primaryHeading());val s=ui("components/MessageCard.kt");assertTrue(s.contains("role=AnswerRole.Independent"));assertTrue(s.contains("独立回答"))}
    @Test fun reviewLabelsMatchExistingPolicyWithoutChangingIt() {assertEquals(listOf("简洁","标准","严格"),ReviewIntensity.entries.map(::reviewIntensityLabel))}
    @Test fun oldRoundUsesItsOwnReviewSnapshot() {
        val model=CollaborateModel(ModelRef("deepseek","a"))
        val user=ChatMessage("u",null,MessageRole.USER,"question",MessageState.COMPLETED)
        val stage=CollaborateStage("s",CollaborateStageType.REVIEW,1,model,"answer",state=CollaborateStageState.Complete)
        val round=CollaborateRound("r","u",listOf(stage),emptyList(),0,1,1,reviewIntensity=ReviewIntensity.STRICT)
        val c=Conversation("c",model.ref,listOf(user),"question",mode=ConversationMode.Collaborate,rounds=listOf(round),
            collaborate=CollaborateConfig(model,CollaborateModel(ModelRef("deepseek","b")),ReviewIntensity.CONCISE))
        val output=collaborateMessageItems(c).filterIsInstance<CollaborateMessageItem.Stage>().single()
        assertEquals(ReviewIntensity.STRICT,output.reviewIntensity);assertEquals(stage,output.stage)
    }
    @Test fun reasoningCompletedLabelDoesNotInventDuration() {assertEquals("已完成",reasoningStatus(ReasoningState.Completed));assertFalse(reasoningStatus(ReasoningState.Completed)!!.contains("秒"))}
    @Test fun reasoningAvailableIsNotFalselyComplete() {assertEquals("可查看",reasoningStatus(ReasoningState.Available))}
    @Test fun reasoningRunningAndInterruptedAreTruthful() {assertEquals("正在处理…",reasoningStatus(ReasoningState.Streaming));assertEquals("正在处理…",reasoningStatus(ReasoningState.Thinking));assertEquals("已中断",reasoningStatus(ReasoningState.Interrupted));assertEquals("等待中",reasoningStatus(ReasoningState.Waiting));assertNull(reasoningStatus(ReasoningState.Unavailable))}
    @Test fun reasoningIsCompactAndNotNestedScrollable() {val s=ui("components/ContentRenderer.kt").substringAfter("@Composable fun ReasoningPanel");assertTrue(s.contains("heightIn(min=Sizes.touch)"));assertTrue(s.contains("mutableStateOf(false)"));assertFalse(s.contains("Surface("));assertFalse(s.contains("verticalScroll("));assertFalse(s.contains("LiteralSurface("))}
    @Test fun singleThinkingOptionPreservesItsSelectedState() {assertEquals(ComposerOption("思考",true),composerOption(false,true));assertEquals(ComposerOption("思考",false),composerOption(false,false))}
    @Test fun multiModelOptionDoesNotBorrowSingleThinking() {assertEquals(ComposerOption("模型设置",false),composerOption(true,true));assertEquals(composerOption(true,true),composerOption(true,false))}
    @Test fun disabledAttachmentPlaceholderIsRemoved() {assertFalse(ui("ChatScreen.kt").contains("Text(\"附件\")"))}
    @Test fun onlyExactAlreadyVisibleStageErrorIsDeduplicated() {assertFalse(showComposerError("PLAN_USAGE_LIMIT",setOf("PLAN_USAGE_LIMIT")));assertTrue(showComposerError("AUTH_REQUIRED",setOf("PLAN_USAGE_LIMIT")));assertFalse(showComposerError(null,emptySet()))}
    @Test fun unrelatedSingleErrorStillHasOneFeedbackOwner() {assertTrue(showComposerError("PLAN_USAGE_LIMIT",emptySet()))}
    @Test fun backClosesChildBeforeHistoryRoot() {val n=ChatNavigation().open(ChatPanel.Settings).open(ChatPanel.History).openHistory(HistoryCategory.Compare);assertEquals(ChatPanel.History,n.back().panel);assertEquals(ChatPanel.Settings,n.back().back().panel);assertEquals(ChatPanel.None,n.back().back().back().panel)}
    @Test fun outsideDismissKeepsModeAndCreatesNoNavigationEntry() {val n=ChatNavigation(compareMode=true).open(ChatPanel.CompareSetup).open(ChatPanel.Models);val dismissed=n.dismiss();assertEquals(ChatPanel.None,dismissed.panel);assertTrue(dismissed.compareMode)}
    @Test fun historyCategoriesStillHaveOnlyThreeRealModes() {assertEquals(3,HistoryCategory.entries.count {it.available});assertEquals("暂未开放",HistorySummary(HistoryCategory.Debate,0).label)}
    @Test fun modeRowsReusePressPrimitiveWithoutIdleCards() {val s=ui("InteractionPanels.kt").substringAfter("@Composable private fun ModeRow").substringBefore("@Composable internal fun HistoryPanel");assertTrue(s.contains("MeldwiseSurface("));assertTrue(s.contains("else Color.Transparent"));assertTrue(s.contains("this.selected=selected"))}
    @Test fun topSubtitleStillHasOneLineEllipsis() {val s=ui("components/ModelTitle.kt");assertTrue(s.contains("maxLines=1,overflow=TextOverflow.Ellipsis"));assertTrue(s.indexOf("modelTitleArrow")<s.indexOf("modelSubtitle"))}
    @Test fun lightSemanticTextRemainsReadable() {colors(false)}
    @Test fun darkSemanticTextRemainsReadable() {colors(true)}
    @Test fun customAccentsDoNotDimImportantText() {listOf(0xFFFFFFL,0L,0x336699L,0xB45B7DL).forEach {colors(false,it);colors(true,it)}}
    @Test fun featureTypographyDoesNotIntroduceHeavyWeights() {val s=ui("theme/MeldwiseTheme.kt");assertFalse(s.contains("FontWeight.SemiBold"));assertFalse(s.contains("FontWeight.Bold"))}
    @Test fun messageActionsKeepTouchTargetsWithoutHeavyDiscs() {val s=ui("components/MessageCard.kt");assertTrue(s.contains("CopyAction(text,quiet=true)"));assertTrue(s.contains("tonal=false,opticalSize=20.dp"));assertTrue(ui("components/SoftAction.kt").contains("Modifier.size(Sizes.touch)"))}
    @Test fun composerReservesLayoutHeightRatherThanFloatingOverContent() {val s=ui("ChatScreen.kt");assertTrue(s.contains("weight(1f).widthIn(max=MeldwiseContentMetrics.readableMax)"));assertTrue(s.contains("horizontal=MeldwiseContentMetrics.composerInset"));assertTrue(ui("components/AdaptiveComposer.kt").contains("maxLines = 5"));assertFalse(s.contains("matchParentSize().clickable"))}
    @Test fun noticeUsesOneSlideAndKeepsAcceptedLifetime() {val s=ui("InteractionPanels.kt").substringAfter("@Composable internal fun CatalogBanner").substringBefore("/** Attach to the active window");assertFalse(s.contains("slideInHorizontally"));assertEquals(1500L,Motion.noticeLifetimeMs)}
    @Test fun nativeCustomPressAndSystemAnimationTimingRemainShared() {val s=ui("components/MeldwisePress.kt");assertTrue(s.contains("animateFloatAsState"));assertFalse(ui("theme/MeldwiseTheme.kt").contains("LocalRippleConfiguration provides RippleConfiguration"));assertTrue(Motion.switchMs<=250)}
}
