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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import io.github.xiaomeng2568.meldwise.ui.presentation.reasoningStatus

/** User-initiated clipboard only. Never feeds content into diagnostics, traces, or toString. */
fun copyContent(context: Context, text: String) {
    val copied=CodePresentation.copySource(text)
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Meldwise",copied))
    Toast.makeText(context,if(copied.length<text.length) "内容较长，已复制前 128K 字符" else "已复制",Toast.LENGTH_SHORT).show()
}
@Composable fun CopyAction(text: String,quiet:Boolean=false) {
    val context=LocalContext.current
    SoftAction(Glyph.Copy,"复制内容",{copyContent(context,text)},tonal=!quiet,opticalSize=if(quiet) 20.dp else Sizes.icon)
}
@Composable fun ContentRenderer(content: ParsedContent) {
    Column(verticalArrangement=Arrangement.spacedBy(Space.medium)) {
        content.blocks.forEach { block ->
            when(block) {
                is TextBlock -> RichTextBlock(block)
                is ThematicBreakBlock -> HorizontalDivider(Modifier.fillMaxWidth().semantics {contentDescription="分隔线"},
                    color=MaterialTheme.colorScheme.outlineVariant)
                is MathBlock -> DisplayMath(block)
                is PlainTextBlock -> LiteralSurface(block.text,"纯文本 · Plain text")
                is CodeBlock -> CodeBlockSurface(block)
                is TableBlock -> MarkdownTable(block)
                is QuoteBlock -> {
                    val rule=MaterialTheme.colorScheme.outlineVariant
                    Box(Modifier.fillMaxWidth().drawBehind {drawLine(rule,Offset.Zero,Offset(0f,size.height),1.dp.toPx())}
                        .padding(start=Space.content)) {RichTextBlock(TextBlock(block.text))}
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
    Row(Modifier.padding(start=(block.listDepth.coerceIn(0,4)*12).dp),horizontalArrangement=Arrangement.spacedBy(Space.small)) {
        block.listMarker?.let { Text(if(block.taskChecked==null) it else if(block.taskChecked) "☑" else "☐",style=style,
            modifier=if(block.taskChecked==null) Modifier else Modifier.semantics {contentDescription=if(block.taskChecked) "已完成任务" else "未完成任务"}) }
        Column(Modifier.weight(1f)) {
            MathRichText(block.text,style)
            if(block.text.length>RenderBounds.INLINE_CHARS) Text("本段较长，切换纯文本查看更多。",style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable fun LiteralSurface(text: String, label: String,showCopy:Boolean=true) {
    var expanded by remember(text) {mutableStateOf(false)}
    val bounded=remember(text) {RenderBounds.prefix(text,RenderBounds.SURFACE_CHARS)}
    val long=bounded.length>RenderBounds.COLLAPSED_CHARS || bounded.count {it=='\n'}>18
    val shown=if(long && !expanded) RenderBounds.prefix(bounded,RenderBounds.COLLAPSED_CHARS).lineSequence().take(18).joinToString("\n") else bounded
    Surface(Modifier.fillMaxWidth(),shape=Radius.surface,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.animateContentSize(tween(Motion.switchMs,easing=Motion.easing)).padding(horizontal=Space.content)) {
            Row(Modifier.fillMaxWidth().heightIn(min=Sizes.touch),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text(label,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
                if(showCopy) CopyAction(text,quiet=true)
            }
            SelectionContainer {
                Text(shown,modifier=Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom=Space.content),
                    style=codeStyle(),softWrap=false)
            }
            if(long) Row(Modifier.fillMaxWidth().heightIn(min=Sizes.touch).meldwiseClickable(role=Role.Button) {expanded=!expanded}
                .semantics {contentDescription=if(expanded) "收起内容" else "展开内容";stateDescription=if(expanded) "已展开" else "已折叠"},
                horizontalArrangement=Arrangement.spacedBy(Space.small),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text(if(expanded) "收起" else "展开",style=MaterialTheme.typography.labelMedium)
                MeldwiseIcon(Glyph.Chevron,opticalSize=18.dp)
            }
            if(bounded.length<text.length) Text("这里显示前 32K 字符，复制最多保留 128K。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(bottom=Space.medium))
        }
    }
}
@Composable fun Notice(text: String, title: String? = null, error: Boolean = false) {
    val rule=if(error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().drawBehind {drawLine(rule,Offset.Zero,Offset(0f,size.height),1.dp.toPx())}
        .padding(start=Space.medium,top=Space.small,bottom=Space.small),horizontalArrangement=Arrangement.spacedBy(Space.medium)) {
        Text(if(error || title=="注意") "!" else "i",color=if(error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(Space.micro)) {
            title?.let {Text(it,style=MaterialTheme.typography.labelLarge)}
            Text(text,style=MaterialTheme.typography.bodySmall,color=if(error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun ReasoningPanel(summary: ReasoningSummary) {
    if(!summary.visible || summary.text.isBlank()) return
    var expanded by remember {mutableStateOf(false)}
    val noun=if(summary.kind==ReasoningKind.Summary) "思考摘要" else "思考过程"
    val label=if(expanded) "收起$noun" else "查看$noun"
    val rule=MaterialTheme.colorScheme.outlineVariant
    val angle by animateFloatAsState(if(expanded) 180f else 0f,tween(Motion.switchMs,easing=Motion.easing),label="reasoningDisclosureAngle")
    Column(Modifier.testTag("reasoningDisclosure"),verticalArrangement=Arrangement.spacedBy(Space.micro)) {
        Row(Modifier.fillMaxWidth().heightIn(min=Sizes.touch).meldwiseClickable(role=Role.Button) {expanded=!expanded}
            .semantics {contentDescription=label;stateDescription=if(expanded) "已展开" else "已折叠"},
            verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Space.small)) {
            Text(listOfNotNull(noun,reasoningStatus(summary.state)).joinToString(" · "),Modifier.weight(1f),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            MeldwiseIcon(Glyph.Chevron,Modifier.graphicsLayer {rotationZ=angle},opticalSize=18.dp)
        }
        AnimatedVisibility(expanded,
            enter=expandVertically(tween(Motion.switchMs,easing=Motion.easing))+fadeIn(tween(Motion.fadeInMs)),
            exit=shrinkVertically(tween(Motion.switchMs,easing=Motion.easing))+fadeOut(tween(Motion.fadeOutMs))) {
            Column(Modifier.fillMaxWidth().drawBehind {drawLine(rule,Offset.Zero,Offset(0f,size.height),1.dp.toPx())}
                .padding(start=MeldwiseContentMetrics.reasoningIndent,bottom=Space.small),verticalArrangement=Arrangement.spacedBy(Space.small)) {
                SelectionContainer {Text(RenderBounds.prefix(summary.text,RenderBounds.SURFACE_CHARS),style=MaterialTheme.typography.bodyMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(summary.text.length>RenderBounds.SURFACE_CHARS) Text("这里显示前 32K 字符。",style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}
