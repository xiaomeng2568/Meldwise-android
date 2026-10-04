// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.ui.content.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Native literal presentation, not sanitization or execution of untrusted source. */
class BetaInertContentTests {
    private val sources = listOf(
        "<script>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        "[x](javascript:alert(1))",
    )

    @Test fun imageEventHandlerRemainsOrdinaryText() {
        val source = sources[1]
        assertEquals(source, (ContentParser.parse(source).blocks.single() as TextBlock).text)
    }

    @Test fun javascriptLinkRemainsOrdinaryText() {
        val source = sources[2]
        assertEquals(source, (ContentParser.parse(source).blocks.single() as TextBlock).text)
    }

    @Test fun executableLookingSourcesRemainPlainInlineRuns() {
        sources.forEach { source ->
            val runs = InlineParser.parse(source)
            assertEquals(source, runs.joinToString("") { it.text })
            assertTrue(runs.all { it.style == InlineStyle.Normal && it.expression == null })
        }
    }

    @Test fun parsingAndCopyDoNotRewriteOriginalSource() {
        sources.forEach { source ->
            ContentParser.parse(source)
            InlineParser.parse(source)
            assertEquals(source, CodePresentation.copySource(source))
        }
    }

    @Test fun fencedExecutableLookingSourceStaysLiteralCode() {
        val source = sources.joinToString("\n")
        val block = ContentParser.parse("```html\n$source\n```").blocks.single() as CodeBlock
        assertEquals("$source\n", block.text)
        assertEquals(block.text, CodePresentation.copySource(block.text))
    }

    @Test fun nativeContentPipelineHasNoExecutionNavigationOrNetworkPath() {
        val root = File(requireNotNull(System.getProperty("projectRoot")),
            "app/src/main/java/io/github/xiaomeng2568/meldwise/ui")
        val paths = listOf("components/ContentRenderer.kt", "components/MathRenderer.kt",
            "components/CodeBlockSurface.kt", "components/MarkdownTable.kt")
        val source = paths.joinToString("\n") { File(root, it).readText() } +
            File(root, "content").walkTopDown().filter { it.extension == "kt" }
                .joinToString("\n") { it.readText() }
        listOf("WebView", "evaluateJavascript", "loadUrl(", "LocalUriHandler", "openUri(",
            "ClickableText", "LinkAnnotation", "ACTION_VIEW", "startActivity(",
            "Request.Builder", "OkHttpClient", "streamResponse(", "URL(",
            "Runtime.getRuntime", "ProcessBuilder").forEach { forbidden ->
            assertFalse("Unexpected content execution/navigation: $forbidden", source.contains(forbidden))
        }
        assertTrue(source.contains("SelectionContainer {Text(annotated"))
    }
}
