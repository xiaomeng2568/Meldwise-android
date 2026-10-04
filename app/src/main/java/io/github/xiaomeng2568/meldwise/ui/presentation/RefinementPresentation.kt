// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.presentation

import io.github.xiaomeng2568.meldwise.data.ReviewIntensity
import io.github.xiaomeng2568.meldwise.ui.content.ReasoningState

/** Visual roles only: never routing, context selection or orchestration instructions. */
enum class AnswerRole { Single, Independent, Initial, Review, Synthesis }
enum class AnswerContentMode { Formatted, Source }
fun answerContentMode(source: Boolean) = if (source) AnswerContentMode.Source else AnswerContentMode.Formatted
fun AnswerRole.primaryHeading() = this == AnswerRole.Synthesis
fun reviewIntensityLabel(value: ReviewIntensity) = when(value) {
    ReviewIntensity.CONCISE -> "简洁"; ReviewIntensity.STANDARD -> "标准"; ReviewIntensity.STRICT -> "严格"
}
fun reasoningStatus(state: ReasoningState): String? = when(state) {
    ReasoningState.Waiting -> "等待中"
    ReasoningState.Streaming, ReasoningState.Thinking -> "正在处理…"
    ReasoningState.Available -> "可查看"
    ReasoningState.Completed -> "已完成"
    ReasoningState.Interrupted -> "已中断"
    ReasoningState.Unavailable -> null
}
data class ComposerOption(val label: String, val selected: Boolean)
fun composerOption(multiModel: Boolean, singleThinking: Boolean) =
    if (multiModel) ComposerOption("模型设置", false) else ComposerOption("思考", singleThinking)

/** Duplicate suppression is exact and presentation-local; unrelated errors are never hidden. */
fun showComposerError(error: String?, visibleStageErrors: Set<String>): Boolean =
    error != null && error !in visibleStageErrors
