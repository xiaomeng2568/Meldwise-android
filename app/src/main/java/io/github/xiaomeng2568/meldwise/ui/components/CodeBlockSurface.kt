// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

@Composable internal fun CodeBlockSurface(block: CodeBlock) {
    var expanded by remember(block.text) { mutableStateOf(false) }
    val shown = remember(block.text, expanded) { CodePresentation.shown(block.text, expanded) }
    val long = remember(block.text) { CodePresentation.isLong(block.text) }
    val tokens = remember(shown, block.language) { CodeHighlighter.tokens(shown, block.language) }
    val colors = codeTokenColors(MaterialTheme.colorScheme)
    val annotated = remember(shown, tokens, colors) {
        colorizedCode(shown, tokens, colors)
    }
    val context = LocalContext.current
    Surface(Modifier.fillMaxWidth().testTag("codeBlock"), shape = Radius.surface, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.animateContentSize(tween(Motion.switchMs, easing = Motion.easing)).padding(horizontal = Space.content)) {
            Row(Modifier.fillMaxWidth().heightIn(min = Sizes.touch), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    MeldwiseIcon(Glyph.Code, opticalSize = 18.dp)
                }
                Text(CodePresentation.languageLabel(block.language), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Always the full original block, never the preview, highlighted text, or fence metadata.
                SoftAction(Glyph.Copy, "复制代码", { copyContent(context, block.text) }, tonal = false, opticalSize = 20.dp)
            }
            SelectionContainer {
                Text(annotated, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = Space.content).testTag("codeBody"),
                    style = highlightedCodeStyle(), softWrap = CodePresentation.softWrap)
            }
            if (long) Row(Modifier.fillMaxWidth().heightIn(min = Sizes.touch).meldwiseClickable(role = Role.Button) { expanded = !expanded }
                .semantics { contentDescription = if (expanded) "收起代码" else "展开代码"; stateDescription = if (expanded) "已展开" else "已折叠" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MeldwiseIcon(Glyph.Chevron, opticalSize = 18.dp)
            }
            if (block.text.length > CodePresentation.bounded(block.text).length)
                Text("这里显示前 32K 字符，复制最多保留 128K。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Space.medium))
        }
    }
}
