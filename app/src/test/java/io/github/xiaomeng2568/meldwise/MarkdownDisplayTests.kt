// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.ui.content.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MarkdownDisplayTests {
    private fun blocks(text:String)=ContentParser.parse(text).blocks
    private fun runs(text:String)=InlineParser.parse(text)
    private fun root(path:String)=File(System.getProperty("projectRoot"),"app/src/main/java/io/github/xiaomeng2568/meldwise/$path").readText()
    @Test fun threeHyphensBreak() {assertTrue(blocks("---").single() is ThematicBreakBlock)}
    @Test fun longHyphensBreak() {assertTrue(blocks("--------").single() is ThematicBreakBlock)}
    @Test fun asteriskBreak() {assertTrue(blocks("* * *").single() is ThematicBreakBlock)}
    @Test fun underscoreBreak() {assertTrue(blocks("_ _ _").single() is ThematicBreakBlock)}
    @Test fun smallIndentBreak() {assertTrue(blocks("   ---").single() is ThematicBreakBlock)}
    @Test fun deepIndentNotBreak() {assertTrue(blocks("    ---").none {it is ThematicBreakBlock})}
    @Test fun mixedMarkersNotBreak() {assertTrue(blocks("-_*").single() is TextBlock)}
    @Test fun shortMarkersNotBreak() {assertTrue(blocks("--").single() is TextBlock)}
    @Test fun proseHyphensStayText() {assertEquals("before --- after",(blocks("before --- after").single() as TextBlock).text)}
    @Test fun breakSeparatesParagraphs() {assertEquals(listOf(TextBlock::class,ThematicBreakBlock::class,TextBlock::class),blocks("before\n---\nafter").map {it::class})}
    @Test fun fencedBreakLiteral() {assertTrue(blocks("```kotlin\n---\n```").single() is CodeBlock)}
    @Test fun textFenceBreakLiteral() {assertTrue(blocks("```text\n---\n```").single() is PlainTextBlock)}
    @Test fun tableDelimiterNotBreak() {assertTrue(blocks("A | B\n--- | ---\na | b").single() is TableBlock)}
    @Test fun escapedHyphensNotBreak() {assertTrue(blocks("\\-\\-\\-").single() is TextBlock)}
    @Test fun taskUnchecked() {val b=blocks("- [ ] next").single() as TextBlock;assertEquals(false,b.taskChecked);assertEquals("next",b.text)}
    @Test fun taskChecked() {val b=blocks("- [x] done").single() as TextBlock;assertEquals(true,b.taskChecked);assertEquals("done",b.text)}
    @Test fun uppercaseChecked() {assertEquals(true,(blocks("* [X] done").single() as TextBlock).taskChecked)}
    @Test fun numberedTask() {val b=blocks("1. [ ] next").single() as TextBlock;assertEquals("1.",b.listMarker);assertEquals(false,b.taskChecked)}
    @Test fun invalidTaskRemainsLiteral() {val b=blocks("- [maybe] next").single() as TextBlock;assertNull(b.taskChecked);assertEquals("[maybe] next",b.text)}
    @Test fun taskRequiresBodyAndWhitespace() {assertNull((blocks("- [x]next").single() as TextBlock).taskChecked)}
    @Test fun taskFenceLiteral() {assertTrue(blocks("```python\n- [x] done\n```").single() is CodeBlock)}
    @Test fun nestedListDepth() {assertEquals(listOf(0,1,2),blocks("- a\n  - b\n    1. c").map {(it as TextBlock).listDepth})}
    @Test fun depthBounded() {assertEquals(4,(blocks(" ".repeat(1000)+"- a").single() as TextBlock).listDepth)}
    @Test fun tabDepth() {assertEquals(2,(blocks("\t- a").single() as TextBlock).listDepth)}
    @Test fun nestedTaskStillTask() {val b=blocks("  - [x] done").single() as TextBlock;assertEquals(1,b.listDepth);assertEquals(true,b.taskChecked)}
    @Test fun strike() {val r=runs("~~old~~").single();assertEquals("old",r.text);assertEquals(InlineStyle.Strike,r.style);assertTrue(r.strike)}
    @Test fun singleTildeLiteral() {assertEquals("~old~",runs("~old~").single().text)}
    @Test fun incompleteStrikeLiteral() {assertEquals("~~old",runs("~~old").single().text)}
    @Test fun escapedStrikeLiteral() {assertEquals("~~old~~",runs("\\~\\~old\\~\\~").single().text);assertFalse(runs("\\~\\~old\\~\\~").single().strike)}
    @Test fun strikeWithStrong() {val r=runs("~~**old**~~").single();assertTrue(r.strong);assertTrue(r.strike)}
    @Test fun strongWithEmphasis() {val r=runs("**bold *italic***").last();assertEquals("italic",r.text);assertTrue(r.strong);assertTrue(r.emphasis)}
    @Test fun tripleEmphasis() {val r=runs("***both***").single();assertTrue(r.strong);assertTrue(r.emphasis)}
    @Test fun internalNestedEmphasis() {val r=runs("**a *b* c**");assertEquals("a b c",r.joinToString("") {it.text});assertTrue(r[1].strong && r[1].emphasis)}
    @Test fun strikeCodeNotParsedInsideCode() {val r=runs("`~~old~~`").single();assertEquals(InlineStyle.Code,r.style);assertFalse(r.strike);assertEquals("~~old~~",r.text)}
    @Test fun codeInsideStrongStillCode() {val r=runs("**`ModelRef`**").single();assertEquals(InlineStyle.Code,r.style);assertEquals("ModelRef",r.text)}
    @Test fun inlineMathInsideStrongKeepsExpression() {val r=runs("**\\(x^2\\)**").single();assertEquals(InlineStyle.Math,r.style);assertEquals("x^2",r.expression)}
    @Test fun mathTildesNotStrike() {val r=runs("\\(x ~~ y\\)").single();assertEquals(InlineStyle.Math,r.style);assertFalse(r.strike)}
    @Test fun mathClosingMarkersNotEmphasisTerminators() {val r=runs("**\\(x**y\\) tail**");assertEquals(InlineStyle.Math,r.first().style);assertEquals(" tail",r.last().text)}
    @Test fun tableCellSharedInlinePipeline() {
        val b=blocks("| A | B |\n| --- | --- |\n| ~~old~~ | **new** |").single() as TableBlock
        assertEquals(InlineStyle.Strike,runs(b.rows.single()[0]).single().style)
        assertEquals(InlineStyle.Strong,runs(b.rows.single()[1]).single().style)
    }
    @Test fun boundedDocumentWithManyBreaks() {val p=ContentParser.parse("---\n".repeat(500));assertEquals(256,p.blocks.size);assertTrue(p.truncated)}
    @Test fun boundedInlineNestedSource() {assertTrue(runs("*".repeat(20000)).sumOf {it.text.length}<=RenderBounds.INLINE_CHARS)}
    @Test fun sourceUnchanged() {val original="- [x] done\n---\n~~old~~";blocks(original);assertEquals("- [x] done\n---\n~~old~~",original)}
    @Test fun noClickableLinkOrNetworkRoute() {val r=runs("[example](https://example.invalid)");assertEquals("[example](https://example.invalid)",r.joinToString("") {it.text})}
    @Test fun rendererThemeAndTaskAccessibility() {
        val ui=root("ui/components/ContentRenderer.kt")
        assertTrue(ui.contains("HorizontalDivider"));assertTrue(ui.contains("color=MaterialTheme.colorScheme.outlineVariant"))
        assertTrue(ui.contains("已完成任务"));assertTrue(ui.contains("未完成任务"));assertFalse(ui.contains("Checkbox("))
    }
    @Test fun rendererStrikeAndNestedStyles() {
        val ui=root("ui/components/MathRenderer.kt")
        assertTrue(ui.contains("TextDecoration.LineThrough"));assertTrue(ui.contains("run.strong"));assertTrue(ui.contains("run.emphasis"))
        assertTrue(ui.contains("SelectionContainer"));assertTrue(ui.contains("inlineCodeSpan"))
    }
}
