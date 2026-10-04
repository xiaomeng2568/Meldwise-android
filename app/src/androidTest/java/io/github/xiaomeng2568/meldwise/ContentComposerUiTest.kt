// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Paint
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated synthetic hosts only. Compile/package in this Gate; never execute on a device. */
@RunWith(AndroidJUnit4::class)
class ContentComposerUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun composedCodeContainsDistinctSyntaxColorsWithoutChangingText() {
        val raw = "def digest(value: str):\n    # note\n    return \"ok\", 42\n"
        var colors: Map<CodeTokenRole, androidx.compose.ui.graphics.Color> = emptyMap()
        compose.setContent { MeldwiseTheme(Appearance.Dark) { colors=codeTokenColors(MaterialTheme.colorScheme)
            ContentRenderer(ParsedContent(listOf(CodeBlock(raw,"py")),false)) } }
        val text=compose.onNodeWithTag("codeBody").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(raw,text.text)
        val tokens=CodeHighlighter.tokens(raw,"py")
        assertEquals(tokens.size,text.spanStyles.size)
        tokens.zip(text.spanStyles).forEach { (token,span) -> assertEquals(colors[token.role],span.item.color) }
        assertEquals(5,text.spanStyles.map { it.item.color }.toSet().size)
    }
    @Test fun embeddedFontResolvesAndRetainsPlatformCjkFallback() {
        var context: Context? = null
        compose.setContent { context=LocalContext.current; MeldwiseTheme { ContentRenderer(ContentParser.parse("`ModelRef`\n\n```kt\nval 中文 = 42\n```")) } }
        compose.runOnIdle {
            val face=context!!.resources.getFont(R.font.jetbrains_mono_regular)
            val paint=Paint().apply { typeface=face; textSize=24f; fontFeatureSettings=CodeFontFeatures }
            assertEquals(paint.measureText("iiii"),paint.measureText("WWWW"),.1f)
            assertTrue(paint.hasGlyph("中")); assertTrue(paint.hasGlyph("文"))
        }
        compose.onNodeWithTag("codeBody").assertExists()
    }
    @Test fun codeHeaderAndCopyHaveAccessibleTargets() {
        compose.setContent { MeldwiseTheme { ContentRenderer(ContentParser.parse("```py\nprint(1)\n```")) } }
        compose.onNodeWithText("Python").assertExists().assertHasNoClickAction()
        compose.onNodeWithContentDescription("复制代码").assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("print(1)\n").assertExists()
    }
    @Test fun headerCopyPreservesExactSourceNotPreviewOrLanguage() {
        val raw = "  p = \"C:\\x\"\n\n    next\n"
        var clipboard: ClipboardManager? = null
        compose.setContent { clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            MeldwiseTheme { ContentRenderer(ParsedContent(listOf(CodeBlock(raw, "py")), false)) } }
        compose.onNodeWithContentDescription("复制代码").performClick()
        compose.runOnIdle { assertEquals(raw, clipboard!!.primaryClip!!.getItemAt(0).text.toString()) }
    }
    @Test fun plainTextAndInlineCodeHaveNoCodeHeader() {
        compose.setContent { MeldwiseTheme { ContentRenderer(ContentParser.parse("Use `ModelRef`.\n\n```text\n  raw\n```")) } }
        compose.onNodeWithTag("codeBlock").assertDoesNotExist()
        compose.onNodeWithContentDescription("复制代码").assertDoesNotExist()
        compose.onNodeWithText("纯文本 · Plain text").assertExists()
    }
    @Test fun codeContainingFormulaStaysLiteralInDarkTheme() {
        val raw = "\\frac{1}{2}\n\$x^2\$\n\\[x\\]\n"
        compose.setContent { MeldwiseTheme(Appearance.Dark, 0x336699L) { ContentRenderer(ParsedContent(listOf(CodeBlock(raw,"py")),false)) } }
        compose.onNodeWithText(raw).assertExists(); compose.onNodeWithTag("codeBlock").assertExists()
    }
    @Test fun longLineHasHorizontalScrollRange() {
        compose.setContent { MeldwiseTheme { Box(Modifier.width(320.dp)) { ContentRenderer(ParsedContent(listOf(CodeBlock("x".repeat(300),"kt")),false)) } } }
        val range = compose.onNodeWithTag("codeBody").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(range.maxValue() > 0f)
    }
    @Test fun longCodeCanExpandAndCollapseWithoutAnotherVerticalScroller() {
        compose.setContent { MeldwiseTheme { ContentRenderer(ParsedContent(listOf(CodeBlock("line\n".repeat(20),"python")),false)) } }
        compose.onNodeWithContentDescription("展开代码").performClick()
        compose.onNodeWithContentDescription("收起代码").assertExists().performClick()
        compose.onNodeWithContentDescription("展开代码").assertExists()
    }
    @Composable private fun Fixture(input: String, change: (String)->Unit = {}, busy: Boolean = false, ime: Boolean = false,
        configure: ()->Unit = {}, send: ()->Unit = {}, stop: ()->Unit = {}) {
        AdaptiveComposer(input, change, busy, input.isNotBlank(), send, stop, {}, false, ComposerOption("思考", false), false, {},
            "发送消息", ime, ComposerSummary("Model with a very long name · 默认", ChatPanel.Models), configure)
    }
    @Test fun emptyRestingComposerIs44dpVisualAnd48dpInteractive() {
        compose.setContent { MeldwiseTheme { Fixture("") } }
        compose.onNodeWithTag("composer").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("composerSurface", useUnmergedTree=true).assertHeightIsEqualTo(44.dp)
        compose.onNodeWithTag("composerControls").assertDoesNotExist()
        compose.onNodeWithTag("composerInput").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
    }
    @Test fun focusingSameTextFieldShowsSeparateControlRowWithoutLosingFocus() {
        compose.setContent { MeldwiseTheme { Fixture("") } }
        compose.onNodeWithContentDescription("消息输入框").performClick()
        compose.onNodeWithContentDescription("消息输入框").assertIsFocused()
        compose.onNodeWithTag("composerControls").assertExists()
        compose.onNodeWithContentDescription("模型与模式设置").assertHasClickAction()
    }
    @Test fun syntheticImeStateExpandsEvenWithoutFocusedField() {
        compose.setContent { MeldwiseTheme { Fixture("", ime=true) } }
        compose.onNodeWithTag("composerControls").assertExists()
    }
    @Test fun editingDoesNotMixSummaryWithDraft() {
        var draft by mutableStateOf("")
        compose.setContent { MeldwiseTheme { Fixture(draft, { draft=it }) } }
        compose.onNodeWithContentDescription("消息输入框").performTextInput("hello\nworld")
        compose.onNodeWithContentDescription("消息输入框").assertTextEquals("hello\nworld")
        compose.runOnIdle { assertEquals("hello\nworld", draft) }
        compose.onNodeWithTag("composerControls").assertExists()
    }
    @Test fun narrowAndWideLargeFontControlRowsKeepActionsReachable() {
        var width by mutableStateOf(320.dp)
        compose.setContent { MeldwiseTheme { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
            Box(Modifier.width(width)) { Fixture("line1\nline2\nline3\nline4\nline5") }
        } } }
        listOf(320,360,412,600,840).forEach { value ->
            compose.runOnIdle { width=value.dp }; compose.waitForIdle()
            compose.onNodeWithContentDescription("更多输入选项").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
            compose.onNodeWithContentDescription("发送消息").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
            compose.onNodeWithContentDescription("模型与模式设置").assertIsDisplayed()
        }
        compose.onNodeWithTag("composerInput").assertHeightIsAtLeast(48.dp)
        assertTrue(compose.onNodeWithTag("composerInput").fetchSemanticsNode().boundsInRoot.height / compose.density.density <= 120f)
    }
    @Test fun summaryClickAndBusyStopUseOnlyGivenCallbacks() {
        var busy by mutableStateOf(false); var configuration=0; var sends=0; var stops=0
        compose.setContent { MeldwiseTheme { Fixture("draft", busy=busy, configure={configuration++}, send={sends++}, stop={stops++}) } }
        compose.onNodeWithContentDescription("模型与模式设置").performClick()
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.runOnIdle { busy=true }
        compose.onNodeWithContentDescription("停止当前操作").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("更多输入选项").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1,configuration); assertEquals(1,sends); assertEquals(1,stops) }
    }
}
