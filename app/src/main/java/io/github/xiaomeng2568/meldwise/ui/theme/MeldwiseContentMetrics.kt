// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One centered reading axis. Phone widths stay proportional; wide windows stay readable. */
object MeldwiseContentMetrics {
    val readableMax = Sizes.contentMax
    val conversationInset = Space.content
    val composerInset = Space.medium
    val reasoningIndent = Space.medium
    val messageGap = Space.wide
    val stageGap = Space.content
    val bodyGap = Space.small
    val composerFade = Space.medium

    fun axisWidth(available: Dp): Dp = available.coerceIn(0.dp, readableMax)
    fun answerWidth(available: Dp): Dp = (axisWidth(available) - conversationInset * 2).coerceAtLeast(0.dp)
    fun composerWidth(available: Dp): Dp = (axisWidth(available) - composerInset * 2).coerceAtLeast(0.dp)
    fun userWidth(contentWidth: Dp): Dp = contentWidth.coerceAtLeast(0.dp) * Sizes.userFraction
    fun leadingInset(available: Dp): Dp = (available - axisWidth(available)).coerceAtLeast(0.dp) / 2 + conversationInset
    fun noticeWidth(available: Dp): Dp = answerWidth(available).coerceAtMost(Sizes.noticeMax)
}
