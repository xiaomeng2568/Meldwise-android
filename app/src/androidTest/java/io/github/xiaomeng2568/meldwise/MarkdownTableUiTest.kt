// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xiaomeng2568.meldwise.ui.components.ContentRenderer
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic hosts only. Compile/package in this Gate, never run on a phone/emulator. */
@RunWith(AndroidJUnit4::class)
class MarkdownTableUiTest {
    @get:Rule val compose=createComposeRule()
    private val simple="Name|Value\n---|---:\nA|123"
    @Test fun nativeHeaderAndBodyAreVisible() {
        compose.setContent {MeldwiseTheme {ContentRenderer(ContentParser.parse(simple))}}
        compose.onNodeWithTag("markdownTable").assertExists()
        listOf("Name","Value","A","123").forEach {compose.onNodeWithText(it).assertExists()}
    }
    @Test fun rootAndCellsExposeTableAccessibilityStructure() {
        compose.setContent {MeldwiseTheme {ContentRenderer(ContentParser.parse(simple))}}
        val root=compose.onNodeWithTag("markdownTable").fetchSemanticsNode().config[SemanticsProperties.CollectionInfo]
        assertEquals(2,root.rowCount);assertEquals(2,root.columnCount)
        val header=compose.onNodeWithTag("tableCell-0-0",useUnmergedTree=true).fetchSemanticsNode().config
        assertTrue(header.contains(SemanticsProperties.Heading))
        val cell=compose.onNodeWithTag("tableCell-1-1",useUnmergedTree=true).fetchSemanticsNode().config[SemanticsProperties.CollectionItemInfo]
        assertEquals(1,cell.rowIndex);assertEquals(1,cell.columnIndex)
    }
    @Test fun wideTableHasHorizontalScrollRangeOnNarrowPhone() {
        val line=List(4) {"Long column content ".repeat(10)}.joinToString("|")
        val raw="A|B|C|D\n---|---|---|---\n$line"
        compose.setContent {MeldwiseTheme {Box(Modifier.width(320.dp)) {ContentRenderer(ContentParser.parse(raw))}}}
        val range=compose.onNodeWithTag("markdownTable").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(range.maxValue()>0f)
    }
    @Test fun shortTableDoesNotArtificiallyExpandEveryColumn() {
        compose.setContent {MeldwiseTheme {Box(Modifier.width(320.dp)) {ContentRenderer(ContentParser.parse(simple))}}}
        val range=compose.onNodeWithTag("markdownTable").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(0f,range.maxValue(),.1f)
    }
    @Test fun inlineCodeAndStrongEmphasisUseTheSharedCellText() {
        val raw="A|B\n---|---\n`x|y`|**strong** *emphasis*"
        compose.setContent {MeldwiseTheme {ContentRenderer(ContentParser.parse(raw))}}
        compose.onNodeWithText("x|y").assertExists();compose.onNodeWithText("strong emphasis").assertExists()
        compose.onNodeWithTag("codeBlock").assertDoesNotExist()
    }
    @Test fun escapedPipeIsVisibleWithoutChangingCodeBackslashes() {
        compose.setContent {MeldwiseTheme {ContentRenderer(ContentParser.parse("A|B\n---|---\na\\|b|`c\\|d`"))}}
        compose.onNodeWithText("a|b").assertExists();compose.onNodeWithText("c\\|d").assertExists()
    }
    @Test fun malformedTableDoesNotHaveTableSemantics() {
        compose.setContent {MeldwiseTheme {ContentRenderer(ContentParser.parse("A|B\n--|---\na|b"))}}
        compose.onNodeWithTag("markdownTable").assertDoesNotExist()
    }
    @Test fun emptyCellHasAnAccessibleLabel() {
        compose.setContent {MeldwiseTheme {ContentRenderer(ContentParser.parse("A|B\n---|---\n|value||"))}}
        compose.onNodeWithContentDescription("空单元格",useUnmergedTree=true).assertExists()
    }
    @Test fun codeFenceTableUsesTheExistingCodeRenderer() {
        compose.setContent {MeldwiseTheme {ContentRenderer(ContentParser.parse("```md\n$simple\n```"))}}
        compose.onNodeWithTag("markdownTable").assertDoesNotExist();compose.onNodeWithTag("codeBlock").assertExists()
    }
    @Test fun inlineMathKeepsNativeOrLiteralFallbackInATableCell() {
        compose.setContent {MeldwiseTheme {ContentRenderer(ContentParser.parse("A|B\n---|---\n${'$'}x^2${'$'}|1"))}}
        compose.onNodeWithTag("tableCell-1-0",useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("markdownTable").assertExists()
        // Async Orcex may resolve after initial composition; both existing paths are safe.
    }
    @Test fun themesAndLargeFontKeepEveryCellInTheLayout() {
        var width by mutableStateOf(320.dp)
        var appearance by mutableStateOf(Appearance.Light)
        compose.setContent {MeldwiseTheme(appearance,0x336699L) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)) {
                Box(Modifier.width(width)) {ContentRenderer(ContentParser.parse(simple))}
            }
        }}
        listOf(Appearance.Light,Appearance.Dark).forEach {theme ->listOf(320,412,600,840).forEach {value ->
            compose.runOnIdle {appearance=theme;width=value.dp};compose.waitForIdle()
            compose.onNodeWithTag("tableCell-1-1",useUnmergedTree=true).assertExists()
            compose.onNodeWithTag("markdownTable").assertExists()
        }}
    }
    @Test fun headerIsMediumAndBodyUsesThemeForeground() {
        var foreground:androidx.compose.ui.graphics.Color?=null
        compose.setContent {MeldwiseTheme(Appearance.Dark) {foreground=MaterialTheme.colorScheme.onSurface;ContentRenderer(ContentParser.parse(simple))}}
        val body=mutableListOf<TextLayoutResult>()
        val header=mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("123").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(body)}
        compose.onNodeWithText("Name").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(header)}
        assertEquals(foreground,body.single().layoutInput.style.color)
        assertEquals(FontWeight.Medium,header.single().layoutInput.style.fontWeight)
    }
}
