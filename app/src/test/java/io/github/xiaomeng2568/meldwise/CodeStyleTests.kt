// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.xiaomeng2568.meldwise.ui.components.codeTokenColors
import io.github.xiaomeng2568.meldwise.ui.components.colorizedCode
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class CodeStyleTests {
    private val root = File(requireNotNull(System.getProperty("projectRoot")))
    private fun source(path: String) = File(root, "app/src/main/java/io/github/xiaomeng2568/meldwise/ui/$path").readText()
    private fun roles(raw: String, language: String) = CodeHighlighter.tokens(raw, language).map { raw.substring(it.start, it.end) to it.role }
    private fun palette(dark: Boolean) = codeTokenColors(themeColors(dark))
    private fun styled(raw: String, language: String, dark: Boolean = false) = colorizedCode(raw, CodeHighlighter.tokens(raw, language), palette(dark))
    private fun font() = File(root, "app/src/main/res/font/jetbrains_mono_regular.ttf").readBytes()
    private fun fontNames(): Map<Int, String> {
        val bytes = font()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun u16(offset: Int) = buffer.getShort(offset).toInt() and 0xffff
        val name = (0 until u16(4)).map { 12 + it * 16 }.first { String(bytes, it, 4, Charsets.US_ASCII) == "name" }
        val offset = buffer.getInt(name + 8)
        val strings = offset + u16(offset + 4)
        return (0 until u16(offset + 2)).map { offset + 6 + it * 12 }.filter { u16(it) == 3 }.associate {
            u16(it + 6) to String(bytes, strings + u16(it + 10), u16(it + 8), Charsets.UTF_16BE)
        }
    }

    @Test fun lightPaletteDistinguishesFiveSemanticHues() { assertEquals(5, palette(false).values.toSet().size) }
    @Test fun darkPaletteDistinguishesFiveSemanticHues() { assertEquals(5, palette(true).values.toSet().size) }
    @Test fun functionAndTypeShareOnlyThePurpleTone() { listOf(false,true).forEach { assertEquals(palette(it)[CodeTokenRole.Type], palette(it)[CodeTokenRole.Function]) } }
    @Test fun everyTokenRoleHasAnExplicitColor() { listOf(false,true).forEach { assertEquals(CodeTokenRole.entries.toSet(), palette(it).keys) } }
    @Test fun keywordIsRedInBothThemes() { listOf(false,true).forEach { val c=palette(it).getValue(CodeTokenRole.Keyword); assertTrue(c.red>c.green && c.red>c.blue) } }
    @Test fun stringIsGreenInBothThemes() { listOf(false,true).forEach { val c=palette(it).getValue(CodeTokenRole.StringLiteral); assertTrue(c.green>c.red && c.green>c.blue) } }
    @Test fun typeAndFunctionArePurpleInBothThemes() { listOf(false,true).forEach { val c=palette(it).getValue(CodeTokenRole.Type); assertTrue(c.blue>c.green && c.red>c.green) } }
    @Test fun numberIsBlueInBothThemes() { listOf(false,true).forEach { val c=palette(it).getValue(CodeTokenRole.Number); assertTrue(c.blue>c.green && c.blue>c.red) } }
    @Test fun commentsRemainNeutralSecondaryText() { listOf(false,true).forEach { val c=palette(it).getValue(CodeTokenRole.Comment); assertTrue(maxOf(c.red,c.green,c.blue)-minOf(c.red,c.green,c.blue)<.1f) } }
    @Test fun customAccentDoesNotRecolorSyntaxRoles() { listOf(false,true).forEach { dark -> listOf(0L,0xffffffL,0xff0000L,0x00ff00L,0x336699L).forEach { assertEquals(palette(dark), codeTokenColors(accentColors(dark,it))) } } }
    @Test fun lightAndDarkAreSeparatePalettes() { CodeTokenRole.entries.forEach { assertNotEquals(palette(false)[it],palette(true)[it]) } }
    @Test fun allSyntaxColorsMeetNormalTextContrast() { listOf(false,true).forEach { dark -> val bg=themeColors(dark).surfaceContainerLow.luminance(); palette(dark).forEach { (role,color) -> val fg=color.luminance(); assertTrue(role.name,(maxOf(bg,fg)+.05f)/(minOf(bg,fg)+.05f)>=4.5f) } } }
    @Test fun futureSurfaceChangesStillKeepReadableSyntax() { listOf(Color(0xffcccccc),Color(0xff888888),Color(0xff555555)).forEach { bg -> codeTokenColors(themeColors(false).copy(surfaceContainerLow=bg)).values.forEach { val a=it.luminance(); val b=bg.luminance(); assertTrue((maxOf(a,b)+.05f)/(minOf(a,b)+.05f)>=4.5f) } } }
    @Test fun pythonFunctionDeclarationIsPurple() { assertTrue("sha256_text" to CodeTokenRole.Function in roles("def sha256_text(text: str):\n    return 42", "py")) }
    @Test fun pythonClassDeclarationIsPurple() { assertTrue("Digest" to CodeTokenRole.Type in roles("class Digest:\n    pass", "py")) }
    @Test fun kotlinFunctionDeclarationIsPurple() { assertTrue("copyContent" to CodeTokenRole.Function in roles("fun copyContent(text: String) {}", "kt")) }
    @Test fun kotlinClassDeclarationIsPurple() { assertTrue("ModelRef" to CodeTokenRole.Type in roles("data class ModelRef(val id: String)", "kt")) }
    @Test fun javascriptFunctionAndClassDeclarationsArePurple() { val r=roles("function digest() {}\nclass Result {}", "js"); assertTrue("digest" to CodeTokenRole.Function in r); assertTrue("Result" to CodeTokenRole.Type in r) }
    @Test fun typescriptInterfaceAndAliasDeclarationsArePurple() { val r=roles("interface Output {}\ntype Digest = string", "ts"); assertTrue("Output" to CodeTokenRole.Type in r); assertTrue("Digest" to CodeTokenRole.Type in r) }
    @Test fun javaAndCsharpExplicitTypesArePurple() { listOf("java","cs").forEach { assertTrue("Output" to CodeTokenRole.Type in roles("class Output {}",it)) } }
    @Test fun familiarAnnotatedTypesUsePurple() { mapOf("py" to "value: str", "kt" to "value: String", "ts" to "value: number", "java" to "String value", "cs" to "String value").forEach { (language,text) -> assertTrue(roles(text,language).any { it.second==CodeTokenRole.Type }) } }
    @Test fun genericFamiliarTypesUsePurple() { assertTrue("String" to CodeTokenRole.Type in roles("List<String>","kt")) }
    @Test fun arbitraryCallsAndCapitalizedVariablesAreNotGuessed() { assertTrue(roles("digest(value) UNKNOWN Result Path String","py").isEmpty()); assertTrue(roles("digest(value) UNKNOWN Result String","kt").isEmpty()) }
    @Test fun memberNamesAreNotGuessedAsTypes() { assertTrue(roles("value.String value.str","kt").isEmpty()) }
    @Test fun genericAndExtensionFunctionNamesRemainConservative() { assertFalse(roles("fun <T> List<T>.digest() {}","kt").any { it.second==CodeTokenRole.Function }) }
    @Test fun commentsBreakDeclarationGuessing() { assertFalse(roles("fun /* pending */ digest", "kt").any { it.second==CodeTokenRole.Function }) }
    @Test fun newlinesBreakIncompleteDeclarationGuessing() { assertFalse(roles("def\ndigest", "py").any { it.second==CodeTokenRole.Function }) }
    @Test fun stringsDoNotCreateDeclarationsInsideThem() { assertEquals(listOf("\"def digest class Output\"" to CodeTokenRole.StringLiteral),roles("\"def digest class Output\"","py")) }
    @Test fun commentsDoNotCreateDeclarationsInsideThem() { assertEquals(listOf("# def digest class Output" to CodeTokenRole.Comment),roles("# def digest class Output","py")) }
    @Test fun annotatedTextActuallyContainsEachRoleColor() { val raw="def digest(value: str):\n    # note\n    return \"ok\", 42"; val tokens=CodeHighlighter.tokens(raw,"py"); val styled=styled(raw,"py"); assertEquals(raw,styled.text); assertEquals(tokens.size,styled.spanStyles.size); tokens.zip(styled.spanStyles).forEach { (token,span) -> assertEquals(token.start,span.start); assertEquals(token.end,span.end); assertEquals(palette(false)[token.role],span.item.color) } }
    @Test fun darkAnnotatedTextUsesDarkRoleColors() { val raw="val value: String = \"ok\""; val tokens=CodeHighlighter.tokens(raw,"kt"); val text=styled(raw,"kt",true); assertEquals(tokens.size,text.spanStyles.size); tokens.zip(text.spanStyles).forEach { (token,span) -> assertEquals(palette(true)[token.role],span.item.color) } }
    @Test fun colorizationLeavesUnclassifiedTextWithoutSpan() { val text=styled("ordinary.identifier", "kt"); assertEquals("ordinary.identifier",text.text); assertTrue(text.spanStyles.isEmpty()) }
    @Test fun unknownLanguageHasNoInventedSyntax() { val text=styled("return \"ok\" 42", "unknown"); assertTrue(text.spanStyles.isEmpty()); assertEquals("return \"ok\" 42",text.text) }
    @Test fun malformedCodeKeepsExactVisibleAndCopiedSource() { listOf("  'unfinished\\\n", "/* class X", "\"😀", "\u0000").forEach { raw -> assertEquals(raw,styled(raw,"py").text); assertEquals(raw,CodePresentation.copySource(raw)) } }
    @Test fun sourceWhitespaceAndCjkRemainUnchanged() { val raw="\tdef 中文(value: str):\r\n    return \"\\中文\"\r\n"; assertEquals(raw,styled(raw,"py").text); assertEquals(raw,CodePresentation.copySource(raw)) }
    @Test fun invalidRangesAreIgnoredWithoutChangingSource() { val raw="abc"; val tokens=listOf(CodeToken(-1,2,CodeTokenRole.Keyword),CodeToken(0,4,CodeTokenRole.Number),CodeToken(2,1,CodeTokenRole.Type)); val text=colorizedCode(raw,tokens,palette(false)); assertEquals(raw,text.text); assertTrue(text.spanStyles.isEmpty()) }
    @Test fun unavailableColorFallsBackToOrdinaryText() { assertTrue(colorizedCode("if",CodeHighlighter.tokens("if","py"),emptyMap()).spanStyles.isEmpty()) }
    @Test fun roleRangesStayBoundedAfterAddingDeclarations() { val raw="def digest(): return 42\n".repeat(1000); val tokens=CodeHighlighter.tokens(raw,"py"); assertEquals(CodePresentation.maxTokens,tokens.size); tokens.zipWithNext().forEach { assertTrue(it.first.end<=it.second.start) } }
    @Test fun fontIsThePinnedUnmodifiedOfficialBinary() { val hash=MessageDigest.getInstance("SHA-256").digest(font()).joinToString("") { "%02x".format(it.toInt() and 255) }; assertEquals("a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f",hash); assertEquals(273900,font().size) }
    @Test fun fontMetadataNamesJetBrainsMonoRegular2304() { val names=fontNames(); assertEquals("JetBrains Mono",names[1]); assertEquals("Regular",names[2]); assertTrue(names.getValue(5).startsWith("Version 2.304")) }
    @Test fun fontRetainsOriginalCopyrightAndOflMetadata() { val names=fontNames(); assertTrue(names.getValue(0).contains("Copyright 2020 The JetBrains Mono Project Authors")); assertTrue(names.getValue(13).contains("SIL Open Font License, Version 1.1")) }
    @Test fun completeSeparateFontLicenseIsPackaged() { val terms=File(root,"app/src/main/assets/jetbrains-mono/OFL.txt").readText(); assertTrue(terms.startsWith("Copyright 2020 The JetBrains Mono Project Authors")); assertTrue(terms.contains("SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007")); (1..5).forEach { assertTrue(terms.contains("$it)")) }; assertTrue(terms.contains("TERMINATION")); assertTrue(terms.contains("DISCLAIMER")); assertTrue(terms.trimEnd().endsWith("OTHER DEALINGS IN THE FONT SOFTWARE.")); assertFalse(terms.contains("GPL-3.0-only")) }
    @Test fun fontLicenseMatchesCompletePinnedUpstreamText() { val terms=File(root,"app/src/main/assets/jetbrains-mono/OFL.txt").readText().replace("\r\n","\n").trimEnd(); val hash=MessageDigest.getInstance("SHA-256").digest(terms.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }; assertEquals("86eb358810bafa5553ca76205e72dfb53f5e7ec809a3c36a25f028aa583eb162",hash) }
    @Test fun publicNoticeKeepsFontOutsideMeldwiseGplGrant() { val text=File(root,"THIRD_PARTY_NOTICES.md").readText(); assertTrue(text.contains("JetBrains Mono 2.304")); assertTrue(text.contains("assets/jetbrains-mono/OFL.txt")); assertTrue(text.contains("The font remains under OFL-1.1, not Meldwise's GPL grant")) }
    @Test fun ligaturesAreDisabledForCodeOnly() { assertEquals("'liga' 0, 'calt' 0",CodeFontFeatures); val text=source("theme/CodeTypography.kt"); assertEquals(2,Regex("fontFeatureSettings = CodeFontFeatures").findAll(text).count()); assertTrue(text.contains("R.font.jetbrains_mono_regular")) }
    @Test fun inlineCodeUsesSameEmbeddedFontWithoutSyntaxColoring() { val span=inlineCodeSpan(Color.Gray); assertEquals(MeldwiseCodeFont,span.fontFamily); assertEquals(CodeFontFeatures,span.fontFeatureSettings); assertEquals(Color.Unspecified,span.color); assertEquals(Color.Gray,span.background) }
    @Test fun onlyCodeBlockAndInlineCodeOptIntoNewTypography() { assertTrue(source("components/CodeBlockSurface.kt").contains("style = highlightedCodeStyle()")); assertTrue(source("components/CodeBlockSurface.kt").contains("colorizedCode(shown, tokens, colors)")); assertTrue(source("components/MathRenderer.kt").contains("InlineStyle.Code->inlineCodeSpan(colors.surfaceVariant)")); val ordinary=source("theme/MeldwiseTheme.kt")+source("components/ContentRenderer.kt"); assertFalse(ordinary.contains("MeldwiseCodeFont")); assertFalse(ordinary.contains("highlightedCodeStyle")) }
    @Test fun copyDoesNotReadStyledAnnotations() { val text=source("components/CodeBlockSurface.kt"); assertTrue(text.contains("copyContent(context, block.text)")); assertFalse(text.contains("copyContent(context, annotated")) }
    @Test fun noRuntimeDependencyAddedForFontOrSyntax() { val build=File(root,"app/build.gradle.kts").readText(); listOf("jetbrains-mono", "highlight.js", "prism", "sora-editor").forEach { assertFalse(build.contains(it)) } }
}
