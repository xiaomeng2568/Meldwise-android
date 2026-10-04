// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import ru.wertik.orcex.layout.*
import org.junit.Assert.*
import org.junit.Test

class MathRenderingTests {
    private fun math(text:String)=InlineParser.parse(text).filter {it.expression!=null}
    private fun literal(text:String)=InlineParser.parse(text).joinToString("") {it.text}
    private fun layout(expression:String)=MathLayoutEngine(MathFontMetrics {text,style->
        GlyphMetrics(text.codePointCount(0,text.length)*style.fontSize*.6f,style.fontSize*.8f,style.fontSize*.2f)
    }).layout(requireNotNull(MathSafety.parse(expression)),MathStyle(fontSize=20f))
    @Test fun dollarInlineHasExactSourceAndSeparateExpression() {val r=math("value ${'$'}x^2${'$'} here").single();assertEquals("x^2",r.expression);assertEquals("${'$'}x^2${'$'}",r.text)}
    @Test fun slashParenthesesRecognizedBeforeEscaping() {assertEquals("x_i",math("value \\(x_i\\)").single().expression)}
    @Test fun doubleDollarIsDisplayBlock() {val b=ContentParser.parse("${'$'}${'$'}\\frac{1}{2}${'$'}${'$'}").blocks.single() as MathBlock;assertEquals("\\frac{1}{2}",b.expression)}
    @Test fun slashBracketsIsDisplayBlock() {assertEquals("x+y",(ContentParser.parse("\\[x+y\\]").blocks.single() as MathBlock).expression)}
    @Test fun multilineDisplayPreservesWhitespace() {val raw="\\[\nx + y\n\n= z\n\\]";val b=ContentParser.parse(raw).blocks.single() as MathBlock;assertEquals(raw,b.source);assertTrue(b.expression.contains("\n\n"))}
    @Test fun proseAroundDisplayStaysOrdered() {val b=ContentParser.parse("before ${'$'}${'$'}x^2${'$'}${'$'} after").blocks;assertEquals(3,b.size);assertTrue(b[0] is TextBlock);assertTrue(b[1] is MathBlock);assertTrue(b[2] is TextBlock)}
    @Test fun fencedCodeHasPriority() {val b=ContentParser.parse("```kotlin\n\\[x\\] ${'$'}y${'$'}\n```").blocks.single();assertTrue(b is CodeBlock);assertTrue((b as CodeBlock).text.contains("\\["))}
    @Test fun fencedPlainTextHasPriority() {assertTrue(ContentParser.parse("```text\n${'$'}${'$'}x${'$'}${'$'}\n```").blocks.single() is PlainTextBlock)}
    @Test fun inlineCodeHasPriority() {assertTrue(math("`\\(x\\) ${'$'}y${'$'}`").isEmpty());assertEquals(InlineStyle.Code,InlineParser.parse("`\\(x\\)`").single().style)}
    @Test fun doubleBacktickCodeHasPriority() {assertTrue(math("``${'$'}x${'$'} `inside` ``").isEmpty())}
    @Test fun escapedDollarIsLiteral() {assertTrue(math("\\${'$'}x\\${'$'}").isEmpty());assertEquals("${'$'}x${'$'}",literal("\\${'$'}x\\${'$'}"))}
    @Test fun escapedDisplayIsNotMath() {assertFalse(ContentParser.parse("\\\\[x\\\\]").blocks.any {it is MathBlock})}
    @Test fun currencyHasNoFalsePositive() {listOf("price ${'$'}5 and ${'$'}10","cost ${'$'}19.99","USD ${'$'} 20").forEach {assertTrue(math(it).isEmpty());assertEquals(it,literal(it))}}
    @Test fun inlineMathCanTouchChineseProse() {assertEquals("x^2",math("公式${'$'}x^2${'$'}如下").single().expression)}
    @Test fun multipleInlineFormulasStaySeparate() {assertEquals(listOf("x","y"),math("${'$'}x${'$'} and \\(y\\)").map {it.expression})}
    @Test fun unclosedStreamingMathStaysExact() {listOf("\\(\\frac{x_i}{2}","\\[x_i + y_j","${'$'}${'$'}x_i + y_j").forEach {assertEquals(it,literal(it));assertTrue(math(it).isEmpty())}}
    @Test fun partialDoubleDollarCannotBecomeSingleDollarMath() {assertTrue(math("${'$'}${'$'}x${'$'}").isEmpty())}
    @Test fun unclosedMathCannotEatFollowingCodeFence() {val b=ContentParser.parse("\\[x\n```text\nraw\n```").blocks;assertEquals(2,b.size);assertTrue(b.last() is PlainTextBlock)}
    @Test fun completingStreamCreatesDisplayOnlyAtClosingBoundary() {val raw="\\[x^2\\]";for(i in 1 until raw.length) assertFalse(ContentParser.parse(raw.take(i)).blocks.any {it is MathBlock});assertTrue(ContentParser.parse(raw).blocks.single() is MathBlock)}
    @Test fun fractionCreatesNativeRuleAndBaselines() {val m=layout("\\frac{x_1}{y^2}");assertTrue(MathSafety.renderable(m));assertTrue(m.commands.any {it is DrawCommand.Line});assertTrue(m.commands.filterIsInstance<DrawCommand.Text>().map {it.baseline}.distinct().size>1)}
    @Test fun originalCommunityExpressionHasNativeGeometry() {assertTrue(MathSafety.renderable(layout("f\\left(-\\frac{2}{3}\\right)=\\frac{85}{27}")))}
    @Test fun incompleteDollarMathNeverLosesMarkdownCharacters() {val source="${'$'}\\sqrt{a\\_b} + **x**";assertEquals(source,literal(source));assertTrue(math(source).isEmpty())}
    @Test fun greekSymbolsAreNativeTextCommands() {assertTrue(MathSafety.renderable(layout("\\alpha + \\beta = \\gamma")))}
    @Test fun casesHaveNativeGeometry() {assertTrue(MathSafety.renderable(layout("\\begin{cases}x&x>0\\\\-x&x<0\\end{cases}")))}
    @Test fun alignedEquationsHaveNativeGeometry() {assertTrue(MathSafety.renderable(layout("\\begin{aligned}x&=1\\\\y&=2\\end{aligned}")))}
    @Test fun radicalSumAndIntegralHaveNativeGeometry() {listOf("\\sqrt{x^2+1}","\\sum_{i=1}^{n} i","\\int_0^1 x^2 dx").forEach {assertTrue(MathSafety.renderable(layout(it)))}}
    @Test fun matrixHasNativeRowsAndColumns() {assertTrue(MathSafety.renderable(layout("\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}")))}
    @Test fun unsupportedCommandIsVisibleFallback() {val r=math("${'$'}\\unsupported{x}${'$'}").single();assertNull(MathSafety.parse(r.expression!!));assertEquals("${'$'}\\unsupported{x}${'$'}",r.text)}
    @Test fun malformedBracesFailSafely() {listOf("\\frac{a}{","}{","{x"," ").forEach {assertNull(MathSafety.parse(it))}}
    @Test fun formulaSizeAndDepthAreBounded() {assertFalse(MathSafety.bounded("x".repeat(2049)));assertFalse(MathSafety.bounded("{".repeat(25)+"x"+"}".repeat(25)));assertTrue(MathSafety.bounded("x".repeat(2048)))}
    @Test fun invalidLayoutDoesNotProduceBlankCanvas() {assertFalse(MathSafety.renderable(MathLayout(Float.NaN,20f,10f,emptyList())));assertFalse(MathSafety.renderable(MathLayout(1f,1f,1f,emptyList())))}
    @Test fun mathRenderObjectsRedactSource() {val secret="SYNTHETIC_PRIVATE_MATH";assertFalse(MathBlock(secret,secret).toString().contains(secret));assertFalse(MathMatch(secret,secret,0,true).toString().contains(secret))}
    @Test fun encryptedHistoryKeepsOriginalMathAndContextText() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.activate(ModelRef("deepseek","m"));val raw="\\[\\frac{1}{2}\\]";val id=r.begin("question").first;r.update(id,raw,MessageState.COMPLETED);ContentParser.parse(raw);val restored=ChatRepository(blob,box);assertEquals(raw,restored.load().last().text);assertTrue(restored.prepare("next").context.messages.any {it.text==raw})}
    @Test fun documentAndBlockBoundsStillApply() {assertTrue(ContentParser.parse("${'$'}${'$'}x${'$'}${'$'}\n\n".repeat(300)).blocks.size<=RenderBounds.BLOCKS)}
}
