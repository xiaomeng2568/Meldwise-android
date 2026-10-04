// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.github.xiaomeng2568.meldwise.ui.content.CodeToken
import io.github.xiaomeng2568.meldwise.ui.content.CodeTokenRole

/** Syntax-only semantic tones, independent of the user's accent, provider or conversation mode.
 * Light/dark variants share role meaning. Even a future surface change must retain 4.5:1 contrast.
 */
internal fun codeTokenColors(colors: ColorScheme): Map<CodeTokenRole, Color> {
    val background = colors.surfaceContainerLow
    val dark = background.luminance() < .5f
    fun readable(light: Long, night: Long): Color {
        val candidate = Color(if (dark) night else light)
        val target = if (background.luminance() < .179f) Color.White else Color.Black
        for (step in 0..100) {
            val adjusted = lerp(candidate, target, step / 100f)
            val a = adjusted.luminance(); val b = background.luminance()
            if ((maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= 4.5f) return adjusted
        }
        return target
    }
    val purple = readable(0xFF7046A6, 0xFFCFACF5)
    return mapOf(
        CodeTokenRole.Keyword to readable(0xFFB42332, 0xFFFF8F96),
        CodeTokenRole.StringLiteral to readable(0xFF26713D, 0xFF9DD69B),
        CodeTokenRole.Type to purple,
        CodeTokenRole.Function to purple,
        CodeTokenRole.Number to readable(0xFF225A9F, 0xFF9DC7FF),
        CodeTokenRole.Comment to readable(0xFF64616D, 0xFFBBB6C5),
    )
}

/** Styling is an overlay only: the original UTF-16 source, whitespace and newlines stay intact. */
internal fun colorizedCode(source: String, tokens: List<CodeToken>, colors: Map<CodeTokenRole, Color>): AnnotatedString =
    buildAnnotatedString {
        append(source)
        tokens.forEach { token ->
            if (token.start >= 0 && token.start < token.end && token.end <= source.length) {
                colors[token.role]?.let { addStyle(SpanStyle(color = it), token.start, token.end) }
            }
        }
    }
