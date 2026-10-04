package io.github.xiaomeng2568.meldwise.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

/** User-initiated clipboard only. Never feeds content into diagnostics, traces, or toString. */
fun copyContent(context: Context, text: String) {
    val bound=131072
    val copied=RenderBounds.prefix(text,bound)
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Meldwise",copied))
    Toast.makeText(context,if(copied.length<text.length) "内容较长，已复制前 128K 字符" else "已复制",Toast.LENGTH_SHORT).show()
}
@Composable fun CopyAction(text: String) {
    val context=LocalContext.current
    SoftAction(Glyph.Copy,"复制内容",{copyContent(context,text)})
}
@Composable fun ContentRenderer(content: ParsedContent) {
    Column(verticalArrangement=Arrangement.spacedBy(Space.medium)) {
        content.blocks.forEach { block ->
            when(block) {
                is TextBlock -> RichTextBlock(block)
                is MathBlock -> DisplayMath(block)
                is PlainTextBlock -> LiteralSurface(block.text,"纯文本 · Plain text")
                is CodeBlock -> LiteralSurface(block.text,block.language ?: "代码 · Code")
                is QuoteBlock -> Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=Radius.medium) {
                    Row(Modifier.padding(Space.content),horizontalArrangement=Arrangement.spacedBy(Space.medium)) {
                        Text("❝",color=MaterialTheme.colorScheme.primary);RichTextBlock(TextBlock(block.text))
                    }
                }
                is InfoBlock -> Notice(block.text)
                is WarningBlock -> Notice(block.text,"注意")
                is ErrorBlock -> Notice(block.text,"错误",error=true)
                is ReasoningBlock -> ReasoningPanel(block.summary)
            }
        }
        if(content.truncated) Notice("这里展示前 64K 字符或 256 个内容块，原文仍完整保存在本机。")
    }
}
@Composable private fun RichTextBlock(block: TextBlock) {
    val style=when(block.heading) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.titleLarge
        in 3..6 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.bodyLarge
    }
    Row(horizontalArrangement=Arrangement.spacedBy(Space.small)) {
        block.listMarker?.let { Text(it,style=style) }
        Column(Modifier.weight(1f)) {
            MathRichText(block.text,style)
            if(block.text.length>RenderBounds.INLINE_CHARS) Text("本段较长，切换纯文本查看更多。",style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable fun LiteralSurface(text: String, label: String) {
    var expanded by remember(text) {mutableStateOf(false)}
    val bounded=remember(text) {RenderBounds.prefix(text,RenderBounds.SURFACE_CHARS)}
    val long=bounded.length>RenderBounds.COLLAPSED_CHARS || bounded.count {it=='\n'}>18
    val shown=if(long && !expanded) RenderBounds.prefix(bounded,RenderBounds.COLLAPSED_CHARS).lineSequence().take(18).joinToString("\n") else bounded
    Surface(Modifier.fillMaxWidth(),shape=Radius.surface,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.animateContentSize(tween(Motion.switchMs,easing=Motion.easing)).padding(horizontal=Space.content)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text(label,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                CopyAction(text)
            }
            SelectionContainer {
                Text(shown,modifier=Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom=Space.content),
                    style=codeStyle(),softWrap=false)
            }
            if(long) Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                SoftAction(Glyph.Chevron,if(expanded) "收起内容" else "展开内容",{expanded=!expanded})
                Text(if(expanded) "收起" else "展开",style=MaterialTheme.typography.labelMedium)
            }
            if(bounded.length<text.length) Text("这里显示前 32K 字符，复制最多保留 128K。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(bottom=Space.medium))
        }
    }
}
@Composable fun Notice(text: String, title: String? = null, error: Boolean = false) {
    Surface(Modifier.fillMaxWidth(),shape=Radius.surface,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(Space.content),horizontalArrangement=Arrangement.spacedBy(Space.medium)) {
            Text(if(error || title=="注意") "!" else "i",color=if(error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Column(verticalArrangement=Arrangement.spacedBy(Space.micro)) {
                title?.let {Text(it,style=MaterialTheme.typography.labelLarge)}
                Text(text,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable fun ReasoningPanel(summary: ReasoningSummary) {
    if(!summary.visible || summary.text.isBlank()) return
    var expanded by remember {mutableStateOf(false)}
    val noun=if(summary.kind==ReasoningKind.Summary) "思考摘要" else "思考过程"
    val label=if(expanded) "收起$noun" else "查看$noun"
    Column(Modifier.testTag("reasoningDisclosure"),verticalArrangement=Arrangement.spacedBy(Space.micro)) {
        Row(Modifier.heightIn(min=Sizes.touch).meldwiseClickable(role=Role.Button) {expanded=!expanded}
            .semantics {stateDescription=if(expanded) "已展开" else "已折叠"},
            verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Space.small)) {
            MeldwiseIcon(if(expanded) Glyph.Close else Glyph.Chevron)
            Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(summary.state==ReasoningState.Streaming) Text("正在思考…",style=MaterialTheme.typography.labelSmall)
            if(summary.state==ReasoningState.Interrupted) Text("已中断",style=MaterialTheme.typography.labelSmall)
        }
        AnimatedVisibility(expanded,
            enter=expandVertically(tween(Motion.switchMs,easing=Motion.easing))+fadeIn(tween(Motion.fadeInMs)),
            exit=shrinkVertically(tween(Motion.switchMs,easing=Motion.easing))+fadeOut(tween(Motion.fadeOutMs))) {
            Surface(Modifier.fillMaxWidth(),shape=Radius.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(Space.medium),verticalArrangement=Arrangement.spacedBy(Space.small)) {
                    SelectionContainer {Text(RenderBounds.prefix(summary.text,RenderBounds.SURFACE_CHARS),style=MaterialTheme.typography.bodyMedium,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    if(summary.text.length>RenderBounds.SURFACE_CHARS) Text("这里显示前 32K 字符。",style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
