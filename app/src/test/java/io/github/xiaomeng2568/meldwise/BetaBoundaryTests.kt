// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BetaBoundaryTests {
    @Test fun observerDoesNotCatchOrRetryThrowingFactory()=runTest {
        val owner=RuntimeUsage();val op=owner.begin(UsageOperationId(UsageMode.SINGLE,"message"));var calls=0
        val exception=java.io.IOException("synthetic")
        try {op.stream(UsageSlot.SINGLE,ModelRef("chatgpt","a")) {calls++;throw exception}.collect();fail()}
        catch(actual:java.io.IOException) {assertSame(exception,actual)}
        assertEquals(1,calls);assertEquals(1,op.snapshot().requestCount)
    }
    @Test fun observerPropagatesCollectorStorageFailureWithoutExtraRequest()=runTest {
        val op=RuntimeUsage().begin(UsageOperationId(UsageMode.SINGLE,"message"));var calls=0
        val exception=ProviderFailure(LlmError(ErrorKind.STORAGE))
        try {op.stream(UsageSlot.SINGLE,ModelRef("chatgpt","a")) {calls++;flowOf(LlmEvent.TextDelta("a"),LlmEvent.Completed(null))}.collect {throw exception};fail()}
        catch(actual:ProviderFailure) {assertSame(exception,actual)}
        op.finish(UsageSlot.SINGLE,RequestOutcome.FAILED)
        assertEquals(1,calls);assertEquals(RequestOutcome.FAILED,op.snapshot().records.single().outcome)
    }
    @Test fun observerCancellationImmediatelyClosesTransportNoReplay()=runTest {
        var closed=false;var calls=0
        val op=RuntimeUsage().begin(UsageOperationId(UsageMode.SINGLE,"message"))
        val task=launch {try {op.stream(UsageSlot.SINGLE,ModelRef("chatgpt","a")) {calls++;flow {try {emit(LlmEvent.HttpReady);awaitCancellation()} finally {closed=true}}}.collect()}
            finally {op.finish(UsageSlot.SINGLE,RequestOutcome.CANCELLED)}}
        runCurrent();task.cancelAndJoin()
        assertTrue(closed);assertEquals(1,calls);assertEquals(0L,testScheduler.currentTime)
        assertEquals(RequestOutcome.CANCELLED,op.snapshot().records.single().outcome)
    }
    @Test fun historicalRenderingDoesNotChangeStoredOrClipboardSource() {
        val raw="---\n~~old~~\n- [x] done\n\nA|B\n---|---\na|b"
        ContentParser.parse(raw);assertEquals(raw,CodePresentation.copySource(raw))
    }
    @Test fun largeValidTableNearAllStructuralBounds() {
        val header=List(TableBounds.COLUMNS) {"H$it"}.joinToString("|")
        val body=List(TableBounds.BODY_ROWS) {List(TableBounds.COLUMNS) {"v"}.joinToString("|")}
        val table=ContentParser.parse(header+"\n"+List(TableBounds.COLUMNS) {"---"}.joinToString("|")+"\n"+body.joinToString("\n")).blocks.single() as TableBlock
        assertEquals(12,table.header.size);assertEquals(64,table.rows.size)
    }
    @Test fun overBoundTableFallsBackAsWholeSource() {
        val raw="A|B\n---|---\n"+List(65) {"a|b"}.joinToString("\n")
        assertTrue(ContentParser.parse(raw).blocks.none {it is TableBlock})
    }
    @Test fun manyTasksAndListItemsStayBounded() {
        val parsed=ContentParser.parse("- [x] task\n".repeat(20000))
        assertTrue(parsed.truncated);assertEquals(RenderBounds.BLOCKS,parsed.blocks.size)
    }
    @Test fun maximumAndOverDepthListsStayFlatAndBounded() {
        val parsed=ContentParser.parse((0..20).joinToString("\n") {" ".repeat(it*2)+"- item"})
        assertEquals(21,parsed.blocks.size)
        assertTrue(parsed.blocks.filterIsInstance<TextBlock>().all {it.listDepth in 0..4})
    }
    @Test fun longCodeRemainsCodeNotMarkdownOrMath() {
        val raw="```python\n"+("---\n| A | B |\n~~text~~\n\\frac{1}{2}\n".repeat(3000))+"\n```"
        val parsed=ContentParser.parse(raw);assertTrue(parsed.truncated)
        assertTrue(parsed.blocks.single() is CodeBlock)
    }
    @Test fun mixedMathCodeTableAndBreakHaveNativePriority() {
        val raw="\\[x^2\\]\n\n---\n\nA|B\n---|---\n`x|y`|\\(z\\)\n\n```md\n---\n```"
        val blocks=ContentParser.parse(raw).blocks
        assertTrue(blocks[0] is MathBlock);assertTrue(blocks[1] is ThematicBreakBlock)
        assertTrue(blocks[2] is TableBlock);assertTrue(blocks[3] is CodeBlock)
    }
    @Test fun manyThematicBreaksBounded() {assertEquals(RenderBounds.BLOCKS,ContentParser.parse("---\n".repeat(10000)).blocks.size)}
    @Test fun conceptualWidthsKeepTableContentGeometryIndependent() {
        val geometry=TablePresentation.geometry(12,List(24) {280},List(24) {32},96,12,8)
        listOf(320,360,412,600,840).forEach {width ->assertTrue(geometry.width>width);assertTrue(geometry.columnWidths.all {it>=96})}
    }
    @Test fun malformedStressRemainsSourceWithoutRecursiveBlocks() {
        val raw=("A|B\n--|---\na|b\n\n").repeat(2000)
        val result=ContentParser.parse(raw);assertTrue(result.truncated)
        assertTrue(result.blocks.none {it is TableBlock})
    }
    @Test fun candidateMetadataAndREADMEDoNotClaimPublishedBeta() {
        val root=File(requireNotNull(System.getProperty("projectRoot")))
        val gradle=File(root,"app/build.gradle.kts").readText();val readme=File(root,"README.md").readText()
        assertTrue(gradle.contains("versionCode = 219"));assertTrue(gradle.contains("0.3.0-beta.1-validation"));assertTrue(gradle.contains("候选验证"))
        assertTrue(readme.contains("尚未发布"));assertTrue(readme.contains("not yet published"))
        assertTrue(readme.contains("no usage dashboard",ignoreCase=true));assertFalse(readme.contains("releases/tag/v0.3.0-beta.1"))
    }
}
