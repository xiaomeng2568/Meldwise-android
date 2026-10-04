// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import androidx.compose.ui.graphics.luminance
import io.github.xiaomeng2568.meldwise.ui.components.codeTokenColors
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.theme.accentColors
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CodeBlockPresentationTests {
    private fun ui(path: String) = File(System.getProperty("projectRoot"), "app/src/main/java/io/github/xiaomeng2568/meldwise/ui/$path").readText()
    private fun label(fence: String) = CodePresentation.languageLabel((ContentParser.parse("```$fence\nbody\n```").blocks.single() as CodeBlock).language)
    private fun tokenTexts(source: String, language: String) = CodeHighlighter.tokens(source, language).map { source.substring(it.start, it.end) to it.role }
    @Test fun pythonFenceLabel() { assertEquals("Python", label("python")) }
    @Test fun pythonAliasLabel() { assertEquals("Python", label("py")) }
    @Test fun kotlinLabels() { listOf("kotlin", "kt").forEach { assertEquals("Kotlin", label(it)) } }
    @Test fun javascriptLabels() { listOf("javascript", "js").forEach { assertEquals("JavaScript", label(it)) } }
    @Test fun typescriptLabels() { listOf("typescript", "ts").forEach { assertEquals("TypeScript", label(it)) } }
    @Test fun jsonLabel() { assertEquals("JSON", label("json")) }
    @Test fun javaAndCLabels() { assertEquals("Java", label("java")); assertEquals("C", label("c")) }
    @Test fun cppAliases() { listOf("cpp", "c++").forEach { assertEquals("C++", label(it)) } }
    @Test fun csharpAliases() { listOf("csharp", "cs").forEach { assertEquals("C#", label(it)) } }
    @Test fun shellAliases() { listOf("bash", "sh", "shell").forEach { assertEquals("Shell", label(it)) } }
    @Test fun powershellAliases() { listOf("powershell", "pwsh").forEach { assertEquals("PowerShell", label(it)) } }
    @Test fun markupLabels() { mapOf("html" to "HTML", "css" to "CSS", "xml" to "XML", "sql" to "SQL", "markdown" to "Markdown", "md" to "Markdown").forEach { (key, value) -> assertEquals(value, label(key)) } }
    @Test fun unknownSafeLabelIsShortAndUnchanged() { assertEquals("Rust", label("Rust")); assertEquals("custom-lang.1", label("custom-lang.1")) }
    @Test fun unsafeOrLongMetadataIsNotExposed() { listOf("<script>", "python title=x", "x".repeat(25), "py\u0000", "🐍", "#", "123").forEach { assertEquals("代码", CodePresentation.languageLabel(it)) } }
    @Test fun emptyLanguageIsCode() { assertEquals("代码", label("")); assertEquals("代码", CodePresentation.languageLabel(null)); assertEquals("代码", CodePresentation.languageLabel("  ")) }
    @Test fun labelNormalizationIsLocaleIndependent() { val previous = Locale.getDefault(); try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals("Kotlin", label("KOTLIN")) } finally { Locale.setDefault(previous) } }
    @Test fun copyPreservesIndentationAndBlankLines() { val raw = "  def f():\n\n      return 1\n"; assertEquals(raw, CodePresentation.copySource(raw)) }
    @Test fun copyPreservesBackslashesAndQuotes() { val raw = "p = Path(\"C:\\demo\\x\")\n\\frac{1}{2}\n'quoted'"; assertEquals(raw, CodePresentation.copySource(raw)) }
    @Test fun closedFenceRetainsItsBodyTrailingNewline() { val raw = "  print(1)\n"; val b = ContentParser.parse("```py\n${raw}```").blocks.single() as CodeBlock; assertEquals(raw, b.text); assertEquals(raw, CodePresentation.copySource(b.text)) }
    @Test fun unclosedFenceDoesNotInventTrailingNewline() { val b = ContentParser.parse("```py\n  print(1)").blocks.single() as CodeBlock; assertEquals("  print(1)", CodePresentation.copySource(b.text)) }
    @Test fun crlfBodyIsPreserved() { val b = ContentParser.parse("```py\r\n  x\r\n```\r\n").blocks.single() as CodeBlock; assertEquals("  x\r\n", b.text) }
    @Test fun clipboardKeeps128KBound() { assertEquals(131072, CodePresentation.clipboardChars); assertEquals(131072, CodePresentation.copySource("a".repeat(140000)).length) }
    @Test fun clipboardNeverSplitsSurrogatePair() { val raw = "a".repeat(131071) + "😀"; assertEquals("a".repeat(131071), CodePresentation.copySource(raw)) }
    @Test fun longPreviewDoesNotReplaceCopySource() { val raw = "line\n".repeat(500); assertTrue(CodePresentation.shown(raw, false).length < raw.length); assertEquals(raw, CodePresentation.copySource(raw)) }
    @Test fun shortCodeHasNoExpansionFooter() { assertFalse(CodePresentation.isLong("val x=1\n")); assertEquals("val x=1\n", CodePresentation.shown("val x=1\n", false)) }
    @Test fun longCodeExpandsOnlyWithin32K() { val raw = "x".repeat(40000); assertTrue(CodePresentation.isLong(raw)); assertEquals(1200, CodePresentation.shown(raw, false).length); assertEquals(32768, CodePresentation.shown(raw, true).length) }
    @Test fun manyShortLinesUseBoundedPreview() { val raw = "x\n".repeat(40); assertEquals(18, CodePresentation.shown(raw, false).lineSequence().count()); assertEquals(raw, CodePresentation.shown(raw, true)) }
    @Test fun previewBoundaryDoesNotSplitUnicode() { val raw = "a".repeat(1199) + "😀" + "b".repeat(20); assertFalse(CodePresentation.shown(raw, false).last().isHighSurrogate()) }
    @Test fun codeHasDedicatedRouteWhilePlainIsLiteral() { val s = ui("components/ContentRenderer.kt"); assertTrue(s.contains("is CodeBlock -> CodeBlockSurface(block)")); assertTrue(s.contains("is PlainTextBlock -> LiteralSurface")); assertFalse(s.contains("is CodeBlock -> LiteralSurface")) }
    @Test fun plainTextHasNoHighlighterOrCodeGlyph() { val s = ui("components/ContentRenderer.kt").substringAfter("@Composable fun LiteralSurface").substringBefore("@Composable fun Notice"); assertFalse(s.contains("CodeHighlighter")); assertFalse(s.contains("Glyph.Code")); assertFalse(s.contains("languageLabel")) }
    @Test fun inlineCodeStaysInline() { assertTrue(ContentParser.parse("Use `ModelRef` here").blocks.single() is TextBlock); assertEquals(InlineStyle.Code, InlineParser.parse("`ModelRef`").single().style); assertTrue(ui("components/MathRenderer.kt").contains("InlineStyle.Code")) }
    @Test fun codeContainingLatexNeverBecomesMath() { val raw = "\\frac{1}{2}\n\$x^2\$\n\\[x\\]\n"; val blocks = ContentParser.parse("```python\n${raw}```").blocks; assertEquals(1, blocks.size); assertEquals(raw, (blocks.single() as CodeBlock).text) }
    @Test fun horizontalScrollingAndNoSoftWrapAreExplicit() { assertTrue(CodePresentation.horizontalScroll); assertFalse(CodePresentation.softWrap); val s = ui("components/CodeBlockSurface.kt"); assertTrue(s.contains("horizontalScroll(rememberScrollState())")); assertTrue(s.contains("softWrap = CodePresentation.softWrap")); assertFalse(s.contains("verticalScroll(")) }
    @Test fun headerCopyUsesOriginalSourceAndAccessibleAction() { val s = ui("components/CodeBlockSurface.kt"); assertTrue(s.contains("\"复制代码\"")); assertTrue(s.contains("copyContent(context, block.text)")); assertFalse(s.contains("copyContent(context, shown)")); assertTrue(ui("components/SoftAction.kt").contains("Modifier.size(Sizes.touch)")) }
    @Test fun codeHeaderIsNotASecondSurfaceOrToolbar() { val s = ui("components/CodeBlockSurface.kt"); assertEquals(1, Regex("\\bSurface\\(").findAll(s).count()); assertFalse(s.contains("shadow(")); assertFalse(s.contains("Card(")); assertTrue(s.contains("Glyph.Code")) }
    @Test fun pythonTokenRolesAreRestrained() { val roles = tokenTexts("from pathlib import Path\np = \"text\" # note\nx = 42", "py"); assertTrue("from" to CodeTokenRole.Keyword in roles); assertTrue("\"text\"" to CodeTokenRole.StringLiteral in roles); assertTrue("# note" to CodeTokenRole.Comment in roles); assertTrue("42" to CodeTokenRole.Number in roles) }
    @Test fun stringsAndCommentsDoNotContainNestedKeywordTokens() { val raw = "\"if return 12\" // class 34\nval x=1"; val tokens = CodeHighlighter.tokens(raw, "kt"); assertEquals(4, tokens.size); assertEquals(CodeTokenRole.StringLiteral, tokens.first().role); assertEquals(CodeTokenRole.Comment, tokens[1].role) }
    @Test fun escapedQuoteDoesNotCloseStringEarly() { val roles = tokenTexts("print(\"a\\\"return\")", "py"); assertEquals(listOf("\"a\\\"return\"" to CodeTokenRole.StringLiteral), roles) }
    @Test fun tripleQuotedSourceIsOneString() { val raw = "\"\"\"if\nreturn\"\"\""; assertEquals(listOf(raw to CodeTokenRole.StringLiteral), tokenTexts(raw, "python")) }
    @Test fun unterminatedStringTailStaysPlain() { val raw = "val s=\"if return 1"; assertEquals(listOf("val" to CodeTokenRole.Keyword), tokenTexts(raw, "kotlin")) }
    @Test fun unterminatedBlockCommentTailStaysPlain() { assertTrue(CodeHighlighter.tokens("/* if return 1", "java").isEmpty()) }
    @Test fun unknownLanguagesRemainPlain() { listOf("rust", "html", "css", "xml", "markdown", "bad lang").forEach { assertTrue(CodeHighlighter.tokens("return \"x\" # 1", it).isEmpty()) } }
    @Test fun jsonDoesNotGuessSingleQuotedString() { assertTrue(CodeHighlighter.tokens("'true'", "json").isEmpty()); assertTrue(tokenTexts("{\"x\":true}", "json").contains("true" to CodeTokenRole.Keyword)) }
    @Test fun identifiersAreNotSubstringKeywords() { assertTrue(CodeHighlighter.tokens("returnValue className iffy variable123", "kotlin").isEmpty()) }
    @Test fun numericSuffixesRemainPlain() { assertTrue(CodeHighlighter.tokens("0xFF 1e10 42px", "python").isEmpty()) }
    @Test fun sqlQuotedEscapesRemainOneToken() { assertEquals(listOf("SELECT" to CodeTokenRole.Keyword, "'it''s'" to CodeTokenRole.StringLiteral), tokenTexts("SELECT 'it''s'", "sql")) }
    @Test fun shellAndPowerShellCommentsAreLocal() { assertEquals(CodeTokenRole.Comment, tokenTexts("# return", "sh").single().second); assertEquals(CodeTokenRole.Keyword, tokenTexts("FUNCTION", "pwsh").single().second) }
    @Test fun highlightsAreBoundedInCountAndInputSize() { assertEquals(CodePresentation.maxTokens, CodeHighlighter.tokens("if ".repeat(3000), "python").size); assertTrue(CodeHighlighter.tokens("x".repeat(32769), "python").isEmpty()) }
    @Test fun tokenRangesAreOrderedNonOverlappingAndDeterministic() { val raw = "def f():\n  return \"😀\" # 中文\n"; val a = CodeHighlighter.tokens(raw, "python"); assertEquals(a, CodeHighlighter.tokens(raw, "python")); a.forEach { assertTrue(it.start >= 0 && it.end <= raw.length && it.start < it.end) }; a.zipWithNext().forEach { assertTrue(it.first.end <= it.second.start) }; assertEquals(raw, CodePresentation.copySource(raw)) }
    @Test fun tokenMetadataDoesNotCarrySource() { val raw = "\"private synthetic text\""; assertFalse(CodeHighlighter.tokens(raw, "py").toString().contains("private synthetic text")) }
    @Test fun lightDarkAndCustomAccentTokenContrast() { listOf(false, true).forEach { dark -> listOf(null, 0L, 0xFFFFFFL, 0x336699L).forEach { accent -> val colors = accentColors(dark, accent); codeTokenColors(colors).values.forEach { val a = it.luminance(); val b = colors.surfaceContainerLow.luminance(); assertTrue((maxOf(a,b)+.05f)/(minOf(a,b)+.05f)>=4.5f) } } } }
    @Test fun malformedFixturesNeverChangeCodeText() { listOf("", "\u0000\\\"", "'", "/*", "\"😀", "```", "\"\\", "\"\"\"").forEach { raw -> listOf("py", "kt", "json", "unknown").forEach { CodeHighlighter.tokens(raw, it); assertEquals(raw, CodePresentation.copySource(raw)) } } }
    @Test fun noExecutableOrRemoteRenderingPath() { val s = ui("components/CodeBlockSurface.kt") + ui("content/CodePresentation.kt"); listOf("WebView", "evaluateJavascript", "Runtime.getRuntime", "ProcessBuilder", "http://", "https://").forEach { assertFalse(s.contains(it)) } }
}
