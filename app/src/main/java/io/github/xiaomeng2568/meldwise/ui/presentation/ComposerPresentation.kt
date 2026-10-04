// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.presentation

import io.github.xiaomeng2568.meldwise.ui.content.RenderBounds

/** UI state only: no change to draft content, request availability, routing or consent. */
internal fun composerExpanded(input: String, focused: Boolean, imeVisible: Boolean) =
    focused || imeVisible || input.isNotEmpty()

internal data class ComposerSummary(val label: String, val configuration: ChatPanel)
internal fun composerSummary(category: HistoryCategory, modelName: String, thinkingLabel: String, reviewLabel: String): ComposerSummary {
    val normalized = modelName.replace(Regex("[\\s\\p{Cc}]+"), " ").trim()
    val short = if (normalized.isEmpty()) "选择模型" else
        RenderBounds.prefix(normalized, 20) + if (normalized.length > 20) "…" else ""
    return when (category) {
        HistoryCategory.Chat -> ComposerSummary("$short · $thinkingLabel", ChatPanel.Models)
        HistoryCategory.Compare -> ComposerSummary("双模型回答", ChatPanel.CompareSetup)
        HistoryCategory.Collaborate -> ComposerSummary("协作 · $reviewLabel", ChatPanel.CollaborateSetup)
        HistoryCategory.Debate -> ComposerSummary("辩论 · 5 请求", ChatPanel.DebateSetup)
    }
}
