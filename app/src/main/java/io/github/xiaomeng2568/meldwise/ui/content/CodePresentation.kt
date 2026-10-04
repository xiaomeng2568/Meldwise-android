// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.content

import java.util.Locale

/** Presentation only. Never executes code or rewrites the source held by a CodeBlock. */
internal object CodePresentation {
    const val clipboardChars = 131072
    const val previewLines = 18
    const val maxTokens = 2048
    val softWrap = false
    val horizontalScroll = true
    private val aliases = mapOf(
        "py" to "python", "kt" to "kotlin", "js" to "javascript", "ts" to "typescript",
        "c++" to "cpp", "cs" to "csharp", "sh" to "shell", "bash" to "shell",
        "pwsh" to "powershell", "md" to "markdown",
    )
    private val labels = mapOf(
        "python" to "Python", "kotlin" to "Kotlin", "javascript" to "JavaScript",
        "typescript" to "TypeScript", "json" to "JSON", "java" to "Java", "c" to "C",
        "cpp" to "C++", "csharp" to "C#", "shell" to "Shell", "powershell" to "PowerShell",
        "html" to "HTML", "css" to "CSS", "xml" to "XML", "sql" to "SQL", "markdown" to "Markdown",
    )
    private val safeLabel = Regex("[A-Za-z][A-Za-z0-9_+.#-]{0,23}")
    fun languageKey(metadata: String?): String? {
        val raw = metadata?.trim()?.takeIf { safeLabel.matches(it) } ?: return null
        val key = raw.lowercase(Locale.ROOT)
        return aliases[key] ?: key
    }
    fun languageLabel(metadata: String?): String =
        languageKey(metadata)?.let { labels[it] ?: metadata!!.trim() } ?: "代码"

    fun bounded(source: String) = RenderBounds.prefix(source, RenderBounds.SURFACE_CHARS)
    fun isLong(source: String): Boolean {
        val bounded = bounded(source)
        return bounded.length > RenderBounds.COLLAPSED_CHARS || bounded.count { it == '\n' } > previewLines
    }
    fun shown(source: String, expanded: Boolean): String {
        val bounded = bounded(source)
        return if (isLong(source) && !expanded)
            RenderBounds.prefix(bounded, RenderBounds.COLLAPSED_CHARS).lineSequence().take(previewLines).joinToString("\n")
        else bounded
    }
    fun copySource(source: String) = RenderBounds.prefix(source, clipboardChars)
}

internal enum class CodeTokenRole { Keyword, StringLiteral, Number, Comment }
internal data class CodeToken(val start: Int, val end: Int, val role: CodeTokenRole)

/** Original bounded lexical colorization, not a compiler/parser. Unsupported or uncertain text stays plain.
 * One forward pass, no recursive grammar or backtracking. Ranges never contain transformed/source text.
 */
internal object CodeHighlighter {
    private val common = "if else for while do break continue return class switch case default".split(' ').toSet()
    private val java = common + "true false null new public private protected static import package try catch finally throw throws this super interface enum extends implements abstract final void int long short byte char float double boolean synchronized volatile transient native strictfp instanceof assert".split(' ')
    private val js = common + "true false null new const let var function async await export import from in instanceof typeof delete yield try catch finally throw this super extends static".split(' ')
    private val c = common + "void int long short char float double struct union typedef sizeof unsigned signed volatile static const enum extern register auto".split(' ')
    private val keywords = mapOf(
        "python" to "and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield".split(' ').toSet(),
        "kotlin" to "if else for while do break continue return class true false null fun val var object when is as in out override data sealed suspend inline reified typealias companion internal open constructor init by public private protected import package try catch finally throw this super interface enum abstract final".split(' ').toSet(),
        "java" to java, "javascript" to js, "typescript" to js + setOf("type", "readonly", "keyof", "declare", "namespace", "interface", "implements", "public", "private", "protected"),
        "c" to c, "cpp" to c + setOf("template", "typename", "namespace", "using", "nullptr", "true", "false", "new", "delete", "public", "private", "protected", "virtual", "override", "try", "catch", "throw", "this"),
        "csharp" to common + "true false null new public private protected static try catch finally throw this base interface enum abstract using namespace string int bool decimal override virtual get set var async await readonly".split(' '),
        "json" to setOf("true", "false", "null"),
        "shell" to setOf("if", "then", "else", "elif", "fi", "for", "in", "do", "done", "case", "esac", "while", "function"),
        "powershell" to setOf("if", "else", "elseif", "foreach", "in", "function", "param", "return", "try", "catch", "finally", "throw"),
        "sql" to setOf("select", "from", "where", "join", "on", "as", "and", "or", "not", "null", "insert", "into", "values", "update", "set", "delete", "create", "table", "order", "by", "group", "having", "limit", "distinct", "union"),
    )
    fun tokens(source: String, metadata: String?): List<CodeToken> {
        val language = CodePresentation.languageKey(metadata) ?: return emptyList()
        val words = keywords[language] ?: return emptyList()
        if (source.length > RenderBounds.SURFACE_CHARS) return emptyList()
        val cLike = language in setOf("kotlin", "java", "javascript", "typescript", "c", "cpp", "csharp")
        val hashComment = language in setOf("python", "shell", "powershell")
        val result = ArrayList<CodeToken>()
        var i = 0
        fun add(end: Int, role: CodeTokenRole) { result.add(CodeToken(i, end, role)); i = end }
        while (i < source.length && result.size < CodePresentation.maxTokens) {
            val c = source[i]
            if ((hashComment && c == '#' && (language == "python" || i == 0 || source[i - 1].isWhitespace())) || (cLike && source.startsWith("//", i)) ||
                (language == "sql" && source.startsWith("--", i))) {
                add(source.indexOf('\n', i).let { if (it < 0) source.length else it }, CodeTokenRole.Comment)
            } else if ((cLike || language == "sql") && source.startsWith("/*", i)) {
                val end = source.indexOf("*/", i + 2)
                if (end < 0) break // An incomplete stream stays ordinary source.
                // Nested block comments are deliberately not guessed.
                if (source.indexOf("/*", i + 2).let { it in (i + 2) until end }) break
                add(end + 2, CodeTokenRole.Comment)
            } else if (c == '"' || c == '\'' || (c == '`' && language in setOf("javascript", "typescript"))) {
                if (language == "json" && c != '"') break
                val triple = language in setOf("python", "kotlin") && source.startsWith("$c$c$c", i)
                val delimiter = if (triple) "$c$c$c" else "$c"
                var end = i + delimiter.length
                var closed = false
                while (end < source.length) {
                    if (source[end] == '\\' && !(triple && language == "kotlin")) { end = (end + 2).coerceAtMost(source.length); continue }
                    if (source.startsWith(delimiter, end)) {
                        // SQL and PowerShell escape quotes by doubling them.
                        if (!triple && language in setOf("sql", "powershell") && source.getOrNull(end + 1) == c) { end += 2; continue }
                        end += delimiter.length; closed = true; break
                    }
                    if (source[end] == '\n' && !triple && c != '`') break
                    end++
                }
                if (!closed) break // Never color keywords from inside an unterminated string.
                add(end, CodeTokenRole.StringLiteral)
            } else if (c.isLetter() || c == '_') {
                var end = i + 1
                while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_')) end++
                val word = source.substring(i, end)
                val key = if (language in setOf("sql", "powershell")) word.lowercase(Locale.ROOT) else word
                if (key in words) add(end, CodeTokenRole.Keyword) else i = end
            } else if (c in '0'..'9' && (i == 0 || !source[i - 1].isLetterOrDigit() && source[i - 1] != '_')) {
                var end = i + 1
                while (end < source.length && (source[end] in '0'..'9' || source[end] == '.' && source.getOrNull(end + 1)?.isDigit() == true)) end++
                if (source.getOrNull(end)?.isLetter() == true || source.getOrNull(end) == '_') {
                    while (end < source.length && (source[end].isLetterOrDigit() || source[end] in "_.")) end++
                    i = end // Do not guess exponent/hex/unit suffixes.
                } else add(end, CodeTokenRole.Number)
            } else i++
        }
        return result
    }
}
