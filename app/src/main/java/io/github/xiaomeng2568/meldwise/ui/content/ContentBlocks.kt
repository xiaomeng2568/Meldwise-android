package io.github.xiaomeng2568.meldwise.ui.content

/** Render-time values only. Original provider text remains in the encrypted journal. */
sealed class ContentBlock { override fun toString() = "ContentBlock([REDACTED])" }
class TextBlock(val text: String, val heading: Int = 0, val listMarker: String? = null) : ContentBlock()
class PlainTextBlock(val text: String) : ContentBlock()
class CodeBlock(val text: String, val language: String? = null) : ContentBlock()
class QuoteBlock(val text: String) : ContentBlock()
class InfoBlock(val text: String) : ContentBlock()
class WarningBlock(val text: String) : ContentBlock()
class ErrorBlock(val text: String) : ContentBlock()
class ReasoningBlock(val summary: ReasoningSummary) : ContentBlock()
enum class ReasoningState { Unavailable, Waiting, Streaming, Thinking, Available, Completed, Interrupted }
enum class ReasoningKind { Summary, UserVisibleContent }
/** Separately supplied, reviewed user-visible provider content; never inferred from answers. */
class ReasoningSummary(val state: ReasoningState = ReasoningState.Unavailable,
    val text: String = "", val kind: ReasoningKind = ReasoningKind.Summary) {
    val visible get() = state != ReasoningState.Unavailable &&
        (text.isNotBlank() || state in setOf(ReasoningState.Thinking,ReasoningState.Waiting,ReasoningState.Streaming,ReasoningState.Interrupted))
    override fun toString() = "ReasoningSummary(state=$state, content=[REDACTED])"
}
object RenderBounds {
    const val DOCUMENT_CHARS = 65536
    const val BLOCKS = 256
    const val SURFACE_CHARS = 32768
    const val COLLAPSED_CHARS = 1200
    const val INLINE_CHARS = 16384
    fun prefix(value: String, limit: Int): String {
        var end = value.length.coerceAtMost(limit)
        if (end < value.length && end > 0 && value[end - 1].isHighSurrogate()) end--
        return value.substring(0, end)
    }
}
class ParsedContent(val blocks: List<ContentBlock>, val truncated: Boolean) {
    override fun toString() = "ParsedContent(blocks=${blocks.size}, truncated=$truncated)"
}
/** Small bounded Markdown subset. HTML and URLs stay literal; no execution or rich persistence schema. */
object ContentParser {
    private val heading = Regex("^(#{1,6})[ \\t]+(.+)$")
    private val bullet = Regex("^[ \\t]*[-+*][ \\t]+(.+)$")
    private val numbered = Regex("^[ \\t]*(\\d{1,6})[.)][ \\t]+(.+)$")
    private val fence = Regex("^[ ]{0,3}(`{3,}|~{3,})(.*)$")
    fun parse(original: String): ParsedContent {
        val bounded = RenderBounds.prefix(original, RenderBounds.DOCUMENT_CHARS)
        val lines = bounded.split('\n')
        val blocks = mutableListOf<ContentBlock>()
        var i = 0
        while (i < lines.size && blocks.size < RenderBounds.BLOCKS) {
            val line = lines[i].removeSuffix("\r")
            val open = fence.matchEntire(line)
            when {
                open != null -> {
                    val delimiter = open.groupValues[1]
                    val label = open.groupValues[2].trim().take(32)
                    val body = StringBuilder(); i++
                    while (i < lines.size) {
                        val closing = lines[i].removeSuffix("\r").trim()
                        if (closing.length >= delimiter.length && closing.all { it == delimiter[0] }) { i++; break }
                        body.append(lines[i]); if (i < lines.lastIndex) body.append('\n'); i++
                    }
                    val raw = body.toString()
                    blocks += if (label.lowercase() in setOf("text", "txt", "plain", "plaintext"))
                        PlainTextBlock(raw) else CodeBlock(raw, label.ifBlank { null })
                }
                line.isBlank() -> i++
                heading.matches(line) -> {
                    val h = heading.matchEntire(line)!!
                    blocks += TextBlock(h.groupValues[2], h.groupValues[1].length); i++
                }
                bullet.matches(line) -> { blocks += TextBlock(bullet.matchEntire(line)!!.groupValues[1], listMarker = "•"); i++ }
                numbered.matches(line) -> {
                    val n = numbered.matchEntire(line)!!
                    blocks += TextBlock(n.groupValues[2], listMarker = "${n.groupValues[1]}."); i++
                }
                line.trimStart().startsWith(">") -> {
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quote += lines[i].trimStart().removePrefix(">").removePrefix(" ").removeSuffix("\r"); i++
                    }
                    blocks += QuoteBlock(quote.joinToString("\n"))
                }
                else -> {
                    val paragraph = mutableListOf(line); i++
                    while (i < lines.size && lines[i].isNotBlank() && !special(lines[i].removeSuffix("\r"))) {
                        paragraph += lines[i].removeSuffix("\r"); i++
                    }
                    blocks += TextBlock(paragraph.joinToString("\n"))
                }
            }
        }
        return ParsedContent(blocks, bounded.length < original.length || i < lines.size)
    }
    private fun special(line: String) = fence.matches(line) || heading.matches(line) || bullet.matches(line) ||
        numbered.matches(line) || line.trimStart().startsWith(">")
}
enum class InlineStyle { Normal, Strong, Emphasis, Code }
class InlineRun(val text: String, val style: InlineStyle) {
    override fun toString() = "InlineRun(style=$style, text=[REDACTED])"
}
object InlineParser {
    fun parse(original: String): List<InlineRun> {
        val text = RenderBounds.prefix(original, RenderBounds.INLINE_CHARS)
        val result = mutableListOf<InlineRun>(); val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { result += InlineRun(plain.toString(), InlineStyle.Normal); plain.clear() } }
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in "\\`*{}[]()#+-.!_>") { plain.append(text[i + 1]); i += 2; continue }
            val marker = when {
                text[i] == '`' -> "`"; text.startsWith("**", i) -> "**"; text.startsWith("__", i) -> "__"
                text[i] == '*' -> "*"; text[i] == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()) -> "_"; else -> null
            }
            val end = marker?.let { text.indexOf(it, i + it.length) } ?: -1
            if (marker != null && end > i + marker.length) {
                flush()
                result += InlineRun(text.substring(i + marker.length, end), when(marker) {
                    "`" -> InlineStyle.Code; "**", "__" -> InlineStyle.Strong; else -> InlineStyle.Emphasis
                }); i = end + marker.length
            } else { plain.append(text[i]); i++ }
        }
        flush(); return result
    }
}
