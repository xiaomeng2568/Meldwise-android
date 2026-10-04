// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import androidx.compose.ui.graphics.luminance
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.theme.accentColors
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MarkdownTableTests {
    private val sample="| Name | Value |\n| --- | --- |\n| A | 1 |\n| B | 2 |"
    private fun table(source:String)=ContentParser.parse(source).blocks.filterIsInstance<TableBlock>().single()
    private fun noTable(source:String) {assertFalse(ContentParser.parse(source).blocks.any {it is TableBlock})}
    private fun source(path:String)=File(System.getProperty("projectRoot"),"app/src/main/java/io/github/xiaomeng2568/meldwise/ui/$path").readText()
    private fun rows(columns:Int,count:Int)=buildString {
        append((1..columns).joinToString(" | ") {"H$it"}).append('\n')
        append(List(columns) {"---"}.joinToString(" | ")).append('\n')
        append(List(count) {List(columns) {"value"}.joinToString(" | ")}.joinToString("\n"))
    }
    @Test fun standardPipeTableHasHeaderAndTwoBodyRows() {
        val t=table(sample);assertEquals(listOf("Name","Value"),t.header)
        assertEquals(listOf(listOf("A","1"),listOf("B","2")),t.rows)
    }
    @Test fun optionalEdgePipesAreNotRequired() {
        val t=table("Name | Value\n--- | ---:\nA | 1")
        assertEquals(listOf("A","1"),t.rows.single());assertEquals(TableAlignment.Right,t.alignments.last())
    }
    @Test fun onlyLeadingEdgePipeIsAccepted() {assertEquals(listOf("A","1"),table("|Name|Value\n|---|---\n|A|1").rows.single())}
    @Test fun onlyTrailingEdgePipeIsAccepted() {assertEquals(listOf("A","1"),table("Name|Value|\n---|---|\nA|1|").rows.single())}
    @Test fun mixedEdgeStylesAreAcceptedPerRow() {assertEquals(listOf("A","1"),table("|Name|Value|\n---|---\n|A|1").rows.single())}
    @Test fun whitespaceAroundCellsIsStructuralNotContent() {assertEquals(listOf("A","1"),table(" H | V \n :--- | ---: \n A  |  1 ").rows.single())}
    @Test fun leftCenterAndRightAlignment() {
        val t=table("| Left | Center | Right |\n| :--- | :---: | ---: |\n| a | b | 123 |")
        assertEquals(listOf(TableAlignment.Left,TableAlignment.Center,TableAlignment.Right),t.alignments)
    }
    @Test fun bareSeparatorMeansLeftAlignment() {assertEquals(listOf(TableAlignment.Left,TableAlignment.Left),table(sample).alignments)}
    @Test fun separatorCanUseMoreThanThreeHyphens() {assertEquals(TableAlignment.Center,table("A|B\n:------:|----\na|b").alignments.first())}
    @Test fun oneBodyRowIsSufficient() {assertEquals(1,table("A|B\n---|---\na|b").rows.size)}
    @Test fun noBodyRowIsNotATable() {noTable("A|B\n---|---")}
    @Test fun headerAloneIsNotATable() {noTable("a | b")}
    @Test fun ordinaryPipeProseStaysText() {noTable("ordinary prose | with pipe\nmore ordinary prose");assertEquals(1,ContentParser.parse("a | b").blocks.size)}
    @Test fun separatorMustBeTheNextLine() {noTable("A|B\n\n---|---\na|b")}
    @Test fun twoHyphensAreIncomplete() {noTable("A|B\n--|---\na|b")}
    @Test fun separatorCellsCannotContainWords() {noTable("A|B\n---|text\na|b")}
    @Test fun separatorCannotContainInternalSpacesOrExtraColons() {
        listOf(": -- -","::---","---::","-:-","—--").forEach {noTable("A|B\n$it|---\na|b")}
    }
    @Test fun separatorColumnCountMustMatchHeader() {noTable("A|B|C\n---|---\na|b|c")}
    @Test fun fewerBodyColumnsRejectWholeCandidate() {
        val raw="A|B|C\n---|---|---\na|b|c\nx|y";noTable(raw)
        assertEquals(raw,(ContentParser.parse(raw).blocks.single() as TextBlock).text)
    }
    @Test fun extraBodyColumnsRejectWholeCandidate() {noTable("A|B\n---|---\na|b\nx|y|z")}
    @Test fun malformedFirstBodyRowDoesNotDropTheHeader() {
        val raw="|A|B|\n|---|---|\n|a|b|c|";assertEquals(raw,(ContentParser.parse(raw).blocks.single() as TextBlock).text)
    }
    @Test fun ambiguousUnclosedInlineCodeRejectsTable() {noTable("A|B\n---|---\n`a|b")}
    @Test fun singleColumnPipeFormStaysText() {noTable("|Header|\n|---|\n|value|")}
    @Test fun allEmptyHeaderIsAmbiguous() {noTable("|||\n|---|---|\n|a|b|")}
    @Test fun emptyHeaderCellAlongsideNamedCellIsAllowed() {assertEquals(listOf("","B"),table("||B|\n|---|---|\n|a|b|").header)}
    @Test fun emptyBodyCellsAreRetained() {assertEquals(listOf("",""),table("|A|B|\n|---|---|\n|||").rows.single())}
    @Test fun emptyMiddleCellDoesNotChangeColumnCount() {assertEquals(listOf("a","","c"),table("A|B|C\n---|---|---\na||c").rows.single())}
    @Test fun threeSpaceIndentIsAllowed() {assertEquals(1,table("   A|B\n   ---|---\n   a|b").rows.size)}
    @Test fun fourSpaceAndTabIndentStayLiteral() {listOf("    ","\t").forEach {indent->noTable("${indent}A|B\n${indent}---|---\n${indent}a|b")}}
    @Test fun crlfTableSourceIsPreserved() {
        val raw=sample.replace("\n","\r\n");val t=table(raw);assertEquals(raw,t.source);assertEquals("1",t.rows.first().last())
    }
    @Test fun sourceIncludesTheSeparatorButNotAFabricatedFinalNewline() {assertEquals(sample,table(sample+"\n").source)}
    @Test fun tableCanFollowProseWithoutABlankLine() {
        val blocks=ContentParser.parse("Introduction\n$sample").blocks
        assertEquals(2,blocks.size);assertTrue(blocks.first() is TextBlock);assertTrue(blocks.last() is TableBlock)
    }
    @Test fun followingNonPipeProseRemainsSeparate() {assertEquals(2,ContentParser.parse("$sample\nAfter").blocks.size)}
    @Test fun blankLineSeparatesTables() {assertEquals(2,ContentParser.parse("$sample\n\n$sample").blocks.filterIsInstance<TableBlock>().size)}
    @Test fun headingAfterTableKeepsItsPresentation() {assertEquals(1,(ContentParser.parse("$sample\n# Next").blocks.last() as TextBlock).heading)}
    @Test fun codeFenceAfterTableKeepsItsOwnPriority() {
        val b=ContentParser.parse("$sample\n```kt\nval x=1\n```").blocks
        assertTrue(b.first() is TableBlock);assertTrue(b.last() is CodeBlock)
    }
    @Test fun textFenceContainingTableStaysPlainText() {
        val block=ContentParser.parse("```text\n$sample\n```").blocks.single()
        assertTrue(block is PlainTextBlock);assertEquals(sample+"\n",(block as PlainTextBlock).text)
    }
    @Test fun codeFenceContainingTableStaysCode() {assertTrue(ContentParser.parse("```python\n$sample\n```").blocks.single() is CodeBlock)}
    @Test fun tildeFenceAndUnclosedFenceKeepPriority() {
        assertTrue(ContentParser.parse("~~~python\n$sample\n~~~").blocks.single() is CodeBlock)
        assertTrue(ContentParser.parse("```kt\n$sample").blocks.single() is CodeBlock)
    }
    @Test fun displayMathBlockCannotBecomeATable() {assertTrue(ContentParser.parse("\\[\n$sample\n\\]").blocks.single() is MathBlock)}
    @Test fun quotesAreNotRecursivelyParsedAsTables() {assertTrue(ContentParser.parse(sample.lineSequence().joinToString("\n") {"> $it"}).blocks.single() is QuoteBlock)}
    @Test fun inlineCodePipeDoesNotCreateAnExtraColumn() {
        val cell=table("A|B\n---|---\n`x|y`|1").rows.single().first()
        assertEquals("`x|y`",cell);assertEquals("x|y",InlineParser.parse(cell).single().text)
    }
    @Test fun doubleBacktickSpanCanContainPipeAndSingleBacktick() {
        val cell=table("A|B\n---|---\n``x|`y``|1").rows.single().first()
        assertEquals(InlineStyle.Code,InlineParser.parse(cell).single().style)
    }
    @Test fun escapedPipesKeepCellSourceButDisplayLiteralPipe() {
        val cell=table("A|B\n---|---\na\\|b|1").rows.single().first()
        assertEquals("a\\|b",cell);assertEquals("a|b",PipeTableParser.inlineSource(cell))
    }
    @Test fun escapedPipeAtCellEdgeIsNotAnOptionalTableEdge() {assertEquals("\\|x",table("\\|x|B\n---|---\na|b").header.first())}
    @Test fun pairedBackslashesDoNotEscapeTheStructuralPipe() {
        assertEquals(listOf("a\\\\","b"),table("A|B\n---|---\na\\\\|b").rows.single())
    }
    @Test fun oddBackslashRunRemovesOnlyThePipeEscape() {
        val raw="a"+"\\".repeat(3)+"|b"
        assertEquals("a"+"\\".repeat(2)+"|b",PipeTableParser.inlineSource(raw))
        assertEquals("a\\|b",InlineParser.parse(PipeTableParser.inlineSource(raw)).joinToString("") {it.text})
    }
    @Test fun codeBackslashesAndPipesStayLiteral() {
        val raw="`a\\|b`";assertEquals(raw,PipeTableParser.inlineSource(raw))
        assertEquals("a\\|b",InlineParser.parse(raw).single().text)
    }
    @Test fun unrelatedBackslashesAreNotNormalizedByTableParsing() {
        val raw="C:\\demo\\folder";assertEquals(raw,table("A|B\n---|---\n$raw|1").rows.single().first())
        assertEquals(raw,PipeTableParser.inlineSource(raw))
    }
    @Test fun inlineDollarMathUsesTheExistingInlineParser() {
        val cell=table("A|B\n---|---\n${'$'}x^2${'$'}|1").rows.single().first()
        assertEquals("x^2",InlineParser.parse(cell).single().expression)
    }
    @Test fun slashParenthesisMathAndItsPipeStayOneCell() {
        val raw="\\(\\lvert x | y\\rvert\\)"
        val cell=table("A|B\n---|---\n$raw|1").rows.single().first()
        assertEquals(raw,cell);assertEquals(raw,PipeTableParser.inlineSource(cell));assertEquals(InlineStyle.Math,InlineParser.parse(cell).single().style)
    }
    @Test fun dollarMathWithPipesIsNotSplitIntoColumns() {assertEquals("${'$'}|x|${'$'}",table("A|B\n---|---\n${'$'}|x|${'$'}|1").rows.single().first())}
    @Test fun unsupportedInlineMathRetainsSourceFallback() {
        val raw="${'$'}\\unsupported{x}${'$'}";val cell=table("A|B\n---|---\n$raw|1").rows.single().first()
        assertEquals(raw,InlineParser.parse(cell).single().text);assertNull(MathSafety.parse(InlineParser.parse(cell).single().expression!!))
    }
    @Test fun strongAndEmphasisKeepSharedInlineStyles() {
        val cell=table("A|B\n---|---\n**strong** *emphasis*|1").rows.single().first()
        assertTrue(InlineParser.parse(cell).any {it.style==InlineStyle.Strong});assertTrue(InlineParser.parse(cell).any {it.style==InlineStyle.Emphasis})
    }
    @Test fun latexInsideCellCodeNeverBecomesMath() {
        val cell=table("A|B\n---|---\n`\\frac{1}{2} ${'$'}x${'$'}`|1").rows.single().first()
        assertTrue(InlineParser.parse(cell).all {it.expression==null})
    }
    @Test fun maximumColumnsAreAccepted() {assertEquals(TableBounds.COLUMNS,table(rows(TableBounds.COLUMNS,1)).header.size)}
    @Test fun tooManyColumnsStayText() {noTable(rows(TableBounds.COLUMNS+1,1))}
    @Test fun maximumBodyRowsAreAccepted() {assertEquals(TableBounds.BODY_ROWS,table(rows(2,TableBounds.BODY_ROWS)).rows.size)}
    @Test fun excessRowsRejectEntireCandidateNotJustTheTail() {
        val raw=rows(2,TableBounds.BODY_ROWS+1);noTable(raw);assertEquals(raw,(ContentParser.parse(raw).blocks.single() as TextBlock).text)
    }
    @Test fun maximumCellLengthIsPreservedWithoutTruncation() {
        val raw="x".repeat(TableBounds.CELL_CHARACTERS);assertEquals(raw,table("A|B\n---|---\n$raw|1").rows.single().first())
    }
    @Test fun oversizedCellFallsBackInsteadOfTruncatingIt() {noTable("A|B\n---|---\n${"x".repeat(TableBounds.CELL_CHARACTERS+1)}|1")}
    @Test fun oversizedHeaderAlsoFallsBack() {noTable("${"x".repeat(TableBounds.CELL_CHARACTERS+1)}|B\n---|---\na|b")}
    @Test fun totalTableCharacterLimitIsEnforced() {
        val raw="A|B\n---|---\n"+List(10) {"x".repeat(1800)+"|"+"y".repeat(1800)}.joinToString("\n")
        assertTrue(raw.length>TableBounds.CHARACTERS);noTable(raw)
    }
    @Test fun longRowsWithinLimitsRemainTables() {
        val raw=(1..12).joinToString("|") {"x".repeat(500)}
        assertEquals(12,table(rows(12,1).substringBeforeLast('\n')+"\n"+raw).rows.single().size)
    }
    @Test fun documentAndBlockLimitsRemainAuthoritative() {
        val blocks=ContentParser.parse((sample+"\n\n").repeat(300));assertTrue(blocks.truncated);assertEquals(RenderBounds.BLOCKS,blocks.blocks.size)
        assertTrue(ContentParser.parse("x".repeat(RenderBounds.DOCUMENT_CHARS+1)).truncated)
    }
    @Test fun documentCellBudgetAcceptsTheExactLimit() {
        val raw=(sample+"\n\n").repeat(170)+"A|B\n---|---\na|b"
        val tables=ContentParser.parse(raw).blocks.filterIsInstance<TableBlock>()
        assertEquals(TableBounds.DOCUMENT_CELLS,tables.sumOf {it.header.size*(it.rows.size+1)})
    }
    @Test fun documentCellBudgetFallsBackWithoutDiscardingLaterSource() {
        val extra="A|B\n---|---\nPRIVATE|VALUE\nx|y"
        val raw=(sample+"\n\n").repeat(171)+extra
        val blocks=ContentParser.parse(raw).blocks
        assertEquals(170,blocks.filterIsInstance<TableBlock>().size)
        assertEquals(extra,(blocks.last() as TextBlock).text)
        assertEquals(sample,(blocks[170] as TextBlock).text)
    }
    @Test fun rejectedTableDoesNotConsumeTheRemainingCellBudget() {
        val raw=(sample+"\n\n").repeat(171)+"A|B\n---|---\na|b"
        val blocks=ContentParser.parse(raw).blocks
        assertTrue(blocks[170] is TextBlock);assertTrue(blocks.last() is TableBlock)
        assertEquals(TableBounds.DOCUMENT_CELLS,blocks.filterIsInstance<TableBlock>().sumOf {it.header.size*(it.rows.size+1)})
    }
    @Test fun malformedLaterRowIsNotReinterpretedAsASuffixTable() {
        val raw="A|B|C\n---|---|---\nx|y\n---|---\na|b";noTable(raw)
        assertEquals(raw,(ContentParser.parse(raw).blocks.single() as TextBlock).text)
    }
    @Test fun emptyMalformedOrRepeatedPipesNeverThrow() {
        listOf("","|","|||","\\|","`|","`|`","-|-", "|".repeat(20000)).forEach {noTable(it)}
    }
    @Test fun parsingIsDeterministicAndNeverMutatesSource() {
        val raw=sample;val a=table(raw);val b=table(raw)
        assertEquals(raw,a.source);assertEquals(a.rows,b.rows);assertEquals(a.alignments,b.alignments);assertEquals(sample,raw)
    }
    @Test fun tableMetadataRedactsSensitiveSource() {
        val raw="PRIVATE_A|PRIVATE_B\n---|---\nSECRET|VALUE"
        assertFalse(table(raw).toString().contains("PRIVATE"));assertFalse(PipeTableParser.candidate(raw.lines(),0) {false}.toString().contains("SECRET"))
    }
    @Test fun encryptedHistoryAndFutureVisibleContextKeepOriginalTableText() {
        val blob=MemoryBlob();val box=testBox();val repository=ChatRepository(blob,box);repository.activate(ModelRef("deepseek","m"))
        val id=repository.begin("question").first;repository.update(id,sample,MessageState.COMPLETED)
        ContentParser.parse(sample)
        val restored=ChatRepository(blob,box);assertEquals(sample,restored.load().last().text)
        assertTrue(restored.prepare("next").context.messages.any {it.text==sample})
    }
    @Test fun nativeTableRouteDoesNotReplaceOtherContentRoutes() {
        val text=source("components/ContentRenderer.kt")
        listOf("is TableBlock -> MarkdownTable(block)","is CodeBlock -> CodeBlockSurface(block)","is MathBlock -> DisplayMath(block)","is PlainTextBlock -> LiteralSurface").forEach {assertTrue(text.contains(it))}
    }
    @Test fun tableUsesSharedInlineMathAndCodeRenderer() {
        val text=source("components/MarkdownTable.kt");assertTrue(text.contains("MathRichText(inline,"));assertFalse(text.contains("InlineParser.parse("))
        assertFalse(text.contains("MathSafety.parse("));assertFalse(text.contains("CodeHighlighter"))
    }
    @Test fun tableIsNativeAndNonExecutable() {
        val text=source("components/MarkdownTable.kt")+source("content/PipeTableParser.kt")
        listOf("WebView","evaluateJavascript","ProcessBuilder","Runtime.getRuntime","http://","https://").forEach {assertFalse(text.contains(it))}
    }
    @Test fun horizontalScrollingDoesNotIntroduceANestedVerticalScroll() {
        val text=source("components/MarkdownTable.kt");assertTrue(text.contains("horizontalScroll(rememberScrollState())"));assertFalse(text.contains("verticalScroll("))
    }
    @Test fun gridHasCollectionAndHeaderAccessibilitySemantics() {
        val text=source("components/MarkdownTable.kt");assertTrue(text.contains("collectionInfo=CollectionInfo"));assertTrue(text.contains("collectionItemInfo=CollectionItemInfo"));assertTrue(text.contains("heading()"))
        assertEquals("表格，2 列，2 行数据",TablePresentation.description(table(sample)))
    }
    @Test fun emptyCellsHaveAnAccessibleLabelRatherThanInvisibleFiller() {
        assertTrue(source("components/MarkdownTable.kt").contains("if(source.isEmpty()) contentDescription=\"空单元格\""))
    }
    @Test fun bodyAndHeaderUseReadableThemeForegroundInLightDarkAndAccent() {
        listOf(false,true).forEach {dark->listOf(null,0L,0xFFFFFFL,0x336699L).forEach {accent ->
            val colors=accentColors(dark,accent)
            listOf(colors.background,colors.surfaceContainerLow).forEach {background ->
                val a=colors.onSurface.luminance();val b=background.luminance();assertTrue((maxOf(a,b)+.05f)/(minOf(a,b)+.05f)>=4.5f)
            }
        }}
        val text=source("components/MarkdownTable.kt");assertTrue(text.contains("color=colors.onSurface"));assertTrue(text.contains("colors.outlineVariant"));assertTrue(text.contains("colors.surfaceContainerLow"))
    }
    @Test fun noHeavyCardOrPerCellSurfaceIsIntroduced() {
        val text=source("components/MarkdownTable.kt");listOf("Card(","Surface(","shadow(","Color(0x").forEach {assertFalse(text.contains(it))}
    }
    @Test fun columnWidthsUseMaximumCellWidthNotPhoneDivision() {
        val grid=TablePresentation.geometry(2,listOf(40,100,150,50),listOf(20,20,40,20),96,12,8)
        assertEquals(listOf(174,124),grid.columnWidths);assertEquals(listOf(36,56),grid.rowHeights)
        assertEquals(listOf(0,174,298),grid.columnEdges);assertEquals(92,grid.height)
    }
    @Test fun shortEmptyColumnsRetainReadableMinimum() {
        assertEquals(listOf(96,96),TablePresentation.geometry(2,listOf(0,10,0,0),List(4) {20},96,12,8).columnWidths)
    }
    @Test fun alignmentPositionsAreDistinctAndNonnegative() {
        assertEquals(0,TablePresentation.horizontalOffset(TableAlignment.Left,100))
        assertEquals(50,TablePresentation.horizontalOffset(TableAlignment.Center,100))
        assertEquals(100,TablePresentation.horizontalOffset(TableAlignment.Right,100))
        TableAlignment.entries.forEach {assertEquals(0,TablePresentation.horizontalOffset(it,-1))}
    }
    @Test fun allPhoneWidthsKeepWideTableWidthInsteadOfSqueezingColumns() {
        val grid=TablePresentation.geometry(4,List(8) {280},List(8) {40},96,12,8)
        listOf(320,412,600,840).forEach {phone ->assertTrue(grid.width>phone)}
        assertEquals(List(4) {304},grid.columnWidths)
    }
    @Test fun largeFontCellHeightIsNotClippedToAFixedRowHeight() {
        val normal=TablePresentation.geometry(2,List(4) {200},List(4) {20},96,12,8)
        val large=TablePresentation.geometry(2,List(4) {200},listOf(40,60,200,80),96,12,8)
        assertTrue(large.height>normal.height);assertEquals(216,large.rowHeights.last())
    }
    @Test fun multilineCellAlignmentUsesTextStyleWithoutChangingMathIntegration() {
        val text=source("components/MarkdownTable.kt");assertTrue(text.contains(".copy(textAlign=align)"));assertTrue(text.contains("cell.place(x,"))
    }
}
