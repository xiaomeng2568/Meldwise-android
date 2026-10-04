// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.github.xiaomeng2568.meldwise.R

/** Unmodified JetBrains Mono 2.304 (OFL-1.1). Only code opts in; prose keeps the system font.
 * Missing glyphs, including CJK, use Android's font fallback. Disable programming ligatures
 * so punctuation remains individually recognizable, without changing selectable/copyable source.
 */
internal val MeldwiseCodeFont = FontFamily(Font(R.font.jetbrains_mono_regular))
internal const val CodeFontFeatures = "'liga' 0, 'calt' 0"

@Composable internal fun highlightedCodeStyle() = MaterialTheme.typography.bodyMedium.copy(
    fontFamily = MeldwiseCodeFont,
    fontFeatureSettings = CodeFontFeatures,
    color = MaterialTheme.colorScheme.onSurface,
)

internal fun inlineCodeSpan(background: Color) = SpanStyle(
    fontFamily = MeldwiseCodeFont,
    fontFeatureSettings = CodeFontFeatures,
    background = background,
)
