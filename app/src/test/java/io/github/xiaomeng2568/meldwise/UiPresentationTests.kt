package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UiPresentationTests {
    private val a=ModelRef(ProviderIds.CHATGPT,"same-id")
    private val b=ModelRef(ProviderIds.DEEPSEEK,"same-id")
    private fun message(role: MessageRole=MessageRole.ASSISTANT, text: String="answer", state: MessageState=MessageState.COMPLETED)=
        ChatMessage("local-only",null,role,text,state)
    @Test fun labelsPreserveProviderOwnership() {
        assertEquals("ChatGPT-5.5",modelLabel(a,"GPT-5.5"));assertEquals("DeepSeek-V4.1",modelLabel(b,"DeepSeek-V4.1"))
    }
    @Test fun unknownModelRemainsUnknown() {assertTrue(modelLabel(ModelRef("chatgpt","UNKNOWN"),"anything").contains("未知模型"))}
    @Test fun unknownProviderIsNotDeepSeek() {assertTrue(modelLabel(ModelRef("future","model"),"name").startsWith("未知提供方"))}
    @Test fun selectorMatchesExactRef() {assertTrue(selectedModel(a,a));assertFalse(selectedModel(a,b));assertFalse(selectedModel(null,a))}
    @Test fun userPresentationHasNoMetadata() {val p=presentMessage(message(MessageRole.USER,"**literal**"),a,"A");assertTrue(p.user);assertNull(p.metadata);assertNull(p.stateCaption)}
    @Test fun userTextStaysLiteral() {assertEquals("**literal**",(presentMessage(message(MessageRole.USER,"**literal**"),a,"A").answer.blocks.single() as PlainTextBlock).text)}
    @Test fun assistantHasSmallProviderModelMetadata() {val p=presentMessage(message(),b,"V4.1");assertFalse(p.user);assertEquals("DeepSeek-V4.1",p.metadata)}
    @Test fun completedHidesDeveloperStateLabel() {assertNull(messageStateCaption(MessageState.COMPLETED))}
    @Test fun cancelledIsNotCompleted() {assertEquals(LaneState.Cancelled,laneState(MessageState.CANCELLED));assertEquals("已取消",messageStateCaption(MessageState.CANCELLED))}
    @Test fun incompleteIsNotCompleted() {assertEquals(LaneState.Incomplete,laneState(MessageState.INCOMPLETE));assertNotNull(messageStateCaption(MessageState.INCOMPLETE))}
    @Test fun failedHasExplicitCaption() {assertEquals("请求失败",messageStateCaption(MessageState.FAILED))}
    @Test fun pendingAndStreamingRemainDistinct() {assertEquals(LaneState.Pending,laneState(MessageState.PENDING));assertEquals(LaneState.Streaming,laneState(MessageState.STREAMING))}
    @Test fun paragraphsAndHeadings() {val blocks=ContentParser.parse("# Heading\n\none\n\ntwo").blocks;assertEquals(3,blocks.size);assertEquals(1,(blocks[0] as TextBlock).heading)}
    @Test fun bulletsAndNumberedLists() {val blocks=ContentParser.parse("- one\n* two\n1. three\n2) four").blocks;assertEquals(listOf("•","•","1.","2."),blocks.map {(it as TextBlock).listMarker})}
    @Test fun quoteGroupsAdjacentLines() {val block=ContentParser.parse("> one\n> two").blocks.single() as QuoteBlock;assertEquals("one\ntwo",block.text)}
    @Test fun plainFencePreservesWhitespace() {val block=ContentParser.parse("```text\n  a\t b\n\n **raw**  \n```").blocks.single() as PlainTextBlock;assertEquals("  a\t b\n\n **raw**  \n",block.text)}
    @Test fun plainAliasesAreLiteral() {listOf("txt","plain","plaintext").forEach {assertTrue(ContentParser.parse("~~~$it\n# raw\n~~~").blocks.single() is PlainTextBlock)}}
    @Test fun codeIsNeverMarkdown() {val block=ContentParser.parse("```kotlin\n# heading\n**value**\n```").blocks.single() as CodeBlock;assertEquals("kotlin",block.language);assertTrue(block.text.contains("**value**"))}
    @Test fun unlabeledFenceIsCode() {assertNull((ContentParser.parse("```\nbody\n```").blocks.single() as CodeBlock).language)}
    @Test fun unclosedStreamingFenceStaysCode() {assertEquals("val x=1",(ContentParser.parse("```kotlin\nval x=1").blocks.single() as CodeBlock).text)}
    @Test fun fenceInsideLongerFenceIsLiteral() {assertTrue((ContentParser.parse("````kotlin\n```\n````").blocks.single() as CodeBlock).text.contains("```"))}
    @Test fun htmlRemainsInertText() {assertEquals("<script>alert(1)</script>",(ContentParser.parse("<script>alert(1)</script>").blocks.single() as TextBlock).text)}
    @Test fun documentBoundIsExplicit() {val parsed=ContentParser.parse("x".repeat(100000));assertTrue(parsed.truncated);assertEquals(RenderBounds.DOCUMENT_CHARS,(parsed.blocks.single() as TextBlock).text.length)}
    @Test fun blockCountIsBounded() {val parsed=ContentParser.parse(List(400) {"- item"}.joinToString("\n"));assertEquals(RenderBounds.BLOCKS,parsed.blocks.size);assertTrue(parsed.truncated)}
    @Test fun noSurrogateIsCutInHalf() {assertEquals("abc",RenderBounds.prefix("abc😀d",4));assertEquals("abc😀",RenderBounds.prefix("abc😀d",5))}
    @Test fun inlineEmphasisAndCode() {assertEquals(listOf(InlineStyle.Strong,InlineStyle.Normal,InlineStyle.Emphasis,InlineStyle.Normal,InlineStyle.Code),InlineParser.parse("**bold** *em* `code`").map {it.style})}
    @Test fun inlineCodeDoesNotInterpretEmphasis() {val run=InlineParser.parse("`**raw**`").single();assertEquals(InlineStyle.Code,run.style);assertEquals("**raw**",run.text)}
    @Test fun escapedEmphasisIsLiteral() {assertEquals("*raw*",InlineParser.parse("\\*raw\\*").joinToString("") {it.text})}
    @Test fun ordinaryBackslashesStayVisible() {assertEquals("C:\\Users",InlineParser.parse("C:\\Users").joinToString("") {it.text})}
    @Test fun inlineWorkIsBounded() {assertEquals(RenderBounds.INLINE_CHARS,InlineParser.parse("a".repeat(30000)).sumOf {it.text.length})}
    @Test fun unavailableReasoningHasNoPanel() {assertFalse(ReasoningSummary().visible);assertFalse(ReasoningSummary(ReasoningState.Unavailable,"anything").visible)}
    @Test fun answerProseNeverCreatesReasoning() {val p=presentMessage(message(text="Let me think. <think>words</think>"),a,"A");assertFalse(p.reasoning.visible);assertFalse(p.answer.blocks.any {it is ReasoningBlock})}
    @Test fun reasoningRemainsSeparateFromAnswer() {val r=ReasoningSummary(ReasoningState.Completed,"separate summary");val p=presentMessage(message(text="final answer"),a,"A",r);assertSame(r,p.reasoning);assertEquals("final answer",(p.answer.blocks.single() as TextBlock).text)}
    @Test fun emptyCompletedReasoningDoesNotInventContent() {assertFalse(ReasoningSummary(ReasoningState.Completed).visible)}
    @Test fun explicitThinkingAndInterruptedRemainRepresentable() {assertTrue(ReasoningSummary(ReasoningState.Thinking).visible);assertTrue(ReasoningSummary(ReasoningState.Interrupted).visible)}
    @Test fun compareStatesAreIndependent() {val lanes=listOf(CompareLane(a,"A",LaneState.Completed,"a"),CompareLane(b,"B",LaneState.Failed,""));assertEquals(LaneState.Completed,lanes[0].state);assertEquals(LaneState.Failed,lanes[1].state);assertNotEquals(lanes[0].ref,lanes[1].ref)}
    @Test fun presentationToStringDoesNotExposeText() {val secret="synthetic-private-text";listOf(TextBlock(secret),PlainTextBlock(secret),CodeBlock(secret),ReasoningSummary(ReasoningState.Completed,secret),InlineRun(secret,InlineStyle.Code),CompareLane(a,"A",LaneState.Completed,secret),presentMessage(message(text=secret),a,"A")).forEach {assertFalse(it.toString().contains(secret))}}
    @Test fun timerDoesNotStartBeforeHttp() {val timer=ResponseWaitTimer();timer.observe(1000,false,false,false);assertNull(timer.seconds(5000))}
    @Test fun timerStartsAtHttpAndStopsAtFirstAnswer() {val timer=ResponseWaitTimer();timer.observe(1000,true,false,false);assertEquals(3L,timer.seconds(4000));timer.observe(4500,true,true,false);assertFalse(timer.running);assertEquals(3L,timer.seconds(12000))}
    @Test fun timerStopsOnCancellationWithoutCompletion() {val timer=ResponseWaitTimer();timer.observe(1000,true,false,false);timer.observe(6500,false,false,true);assertEquals(5L,timer.seconds(20000));assertFalse(timer.running)}
    @Test fun zeroAndNegativeDurationsStaySafe() {val timer=ResponseWaitTimer();timer.observe(2000,true,true,false);assertEquals(0L,timer.seconds(1000))}
    @Test fun uiNeverOwnsProviderRequests() {
        val root=File(requireNotNull(System.getProperty("projectRoot")),"app/src/main/java/io/github/xiaomeng2568/meldwise/ui")
        val sources=root.walkTopDown().filter {it.extension=="kt" && it.name!="MainViewModel.kt"}.map {it.readText()}.joinToString("\n")
        listOf("streamResponse(","listModels(","Request.Builder","TokenManager(","deepSeekCredentials.read()","WebView").forEach {assertFalse(sources.contains(it))}
    }
}
