// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

/** One field in a stable composition slot, so layout changes preserve focus/selection/IME.
 * Insets belong to ChatScreen, never this component. Its actual height stays in normal layout flow.
 */
@Composable internal fun AdaptiveComposer(input: String, onInput: (String)->Unit, busy: Boolean, canSend: Boolean,
    onSend: ()->Unit, onStop: ()->Unit, onThinking: ()->Unit, compareMode: Boolean, option: ComposerOption,
    options: Boolean, onOptions: (Boolean)->Unit, sendLabel: String, imeVisible: Boolean,
    summary: ComposerSummary, onConfiguration: ()->Unit) {
    var focused by remember { mutableStateOf(false) }
    val expanded = composerExpanded(input, focused, imeVisible)
    val colors = MaterialTheme.colorScheme
    val sendControl: @Composable ()->Unit = {
        SoftAction(if (busy) Glyph.Stop else Glyph.Send,
            if (busy) if (compareMode) "取消全部" else "停止当前操作" else sendLabel,
            if (busy) onStop else onSend, enabled = busy || canSend, primary = true)
    }
    val optionsControl: @Composable ()->Unit = {
        SoftAction(Glyph.Plus, "更多输入选项", { onOptions(!options) }, enabled = !busy, tonal = false)
    }
    Box(Modifier.fillMaxWidth().testTag("composer").semantics { stateDescription = if (expanded) "编辑状态" else "紧凑状态" }
        .animateContentSize(tween(Motion.switchMs, easing = Motion.easing))) {
        Surface(Modifier.matchParentSize().padding(vertical = Sizes.composerInset).testTag("composerSurface"), shape = Radius.surface,
            color = colors.surfaceContainerLow,
            border = if (focused) BorderStroke(1.dp, colors.primary.copy(alpha = .20f)) else null) {}
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!expanded) optionsControl()
                // Keep this call outside either state branch: no replacement TextField or focus handoff.
                BasicTextField(value = input, onValueChange = onInput, enabled = !busy,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
                    minLines = 1, maxLines = 5,
                    modifier = Modifier.weight(1f).heightIn(min = Sizes.composerInputMin, max = Sizes.composerMax)
                        .onFocusChanged { focused = it.isFocused }.testTag("composerInput")
                        .padding(horizontal = if (expanded) Space.content else Space.small, vertical = Space.micro)
                        .semantics { contentDescription = "消息输入框" },
                    decorationBox = { field -> Box(contentAlignment = Alignment.CenterStart) {
                        if (input.isEmpty()) Text("发消息…", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                        field()
                    } })
                if (!expanded) sendControl()
            }
            if (expanded) Row(Modifier.fillMaxWidth().testTag("composerControls"), verticalAlignment = Alignment.CenterVertically) {
                optionsControl()
                // Outside editable text; weight constrains long names before either 48dp action.
                MeldwiseTextButton(onClick = onConfiguration, enabled = !busy,
                    modifier = Modifier.weight(1f).testTag("composerSummary").semantics { contentDescription = "模型与模式设置" }) {
                    Text(summary.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                sendControl()
            }
            if (options) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                MeldwiseFilterChip(selected = option.selected, onClick = { onOptions(false); onThinking() }, enabled = !busy,
                    label = { Text(option.label) }, shape = Radius.medium, modifier = Modifier.heightIn(min = Sizes.touch), border = null)
            }
        }
    }
}
