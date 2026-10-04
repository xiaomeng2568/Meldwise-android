// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xiaomeng2568.meldwise.data.ChatMessage
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.modelLabel
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

@Composable fun UserPrompt(text:String,caption:String?=null) {
    BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.CenterEnd) {
        Surface(Modifier.widthIn(max=MeldwiseContentMetrics.userWidth(maxWidth)).wrapContentWidth().testTag("userPrompt"),
            shape=Radius.bubble,color=MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(Space.content)) {
                SelectionContainer {Text(RenderBounds.prefix(text,RenderBounds.DOCUMENT_CHARS),style=MaterialTheme.typography.bodyLarge)}
                caption?.let {Text(it,style=MaterialTheme.typography.labelMedium)}
            }
        }
    }
}
@Composable fun MessageCard(message:ChatMessage,ref:ModelRef,name:String,seconds:Long?=null,onDetails:()->Unit={}) {
    if(message.role==MessageRole.USER) UserPrompt(message.text,messageStateCaption(message.state))
    else {
        val snapshot=message.modelRef ?: ref
        AssistantOutput(CompareLane(snapshot,message.modelDisplayName ?: message.modelRef?.modelId ?: name,
            laneState(message.state),message.text,reasoningPresentation(message.reasoning),seconds),onDetails=onDetails)
    }
}
/** Shared free-flowing answer for Single and Compare. No result-card shell. */
@Composable fun AssistantOutput(lane:CompareLane,modifier:Modifier=Modifier,onDetails:(()->Unit)?=null,
    role:AnswerRole=AnswerRole.Single,heading:String?=null) {
    var plain by remember {mutableStateOf(false)}
    val status=assistantStatus(lane.state,lane.seconds)
    Column(modifier.fillMaxWidth().testTag("assistantOutput").semantics {stateDescription=laneLabel(lane.state)},
        verticalArrangement=Arrangement.spacedBy(MeldwiseContentMetrics.bodyGap)) {
        heading?.let {
            if(role.primaryHeading()) HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant,modifier=Modifier.padding(bottom=Space.small))
            Text(it,style=if(role.primaryHeading()) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                color=if(role.primaryHeading()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Space.small)) {
            Column(Modifier.weight(1f)) {
                Text(modelLabel(lane.ref,lane.displayName),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines=2,overflow=TextOverflow.Ellipsis)
                status?.let {Text(it,style=MaterialTheme.typography.labelMedium,
                    color=if(lane.state==LaneState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
        when(answerContentMode(plain)) {
            AnswerContentMode.Source -> LiteralSurface(lane.answer,"纯文本 · Plain text",showCopy=false)
            AnswerContentMode.Formatted -> ContentRenderer(remember(lane.answer) {ContentParser.parse(lane.answer)})
        }
        ReasoningPanel(lane.reasoning)
        val details=onDetails?.takeIf {lane.state in setOf(LaneState.Failed,LaneState.Incomplete,LaneState.Interrupted)}
        if(lane.answer.isNotEmpty() || details!=null) MessageActions(lane.answer,plain,{plain=!plain},details)
    }
}
@Composable private fun MessageActions(text:String,plain:Boolean,onToggleSource:()->Unit,onDetails:(()->Unit)?) {
    var menu by remember {mutableStateOf(false)}
    Row(verticalAlignment=Alignment.CenterVertically) {
        if(text.isNotEmpty()) CopyAction(text,quiet=true)
        if(text.isNotEmpty() || onDetails!=null) Box {
            SoftAction(Glyph.More,"更多内容操作",{menu=true},tonal=false,opticalSize=20.dp)
            MaterialTheme(shapes=MaterialTheme.shapes.copy(extraSmall=Radius.medium)) {
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    if(text.isNotEmpty()) MeldwiseMenuItem(text={Text(if(plain) "恢复排版" else "按纯文本查看")},onClick={onToggleSource();menu=false})
                    onDetails?.let {action ->MeldwiseMenuItem(text={Text("查看详情")},onClick={menu=false;action()})}
                }
            }
        }
    }
}
/** Compatibility entry point for component previews; shares the actual flat renderer. */
@Composable fun CompareResultCard(lane:CompareLane,modifier:Modifier=Modifier)=AssistantOutput(lane,modifier)
@Composable fun CompareMessage(item:CompareMessageItem) {
    when(item) {
        is CompareMessageItem.Prompt->UserPrompt(item.item.content)
        is CompareMessageItem.Output->{
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant,modifier=Modifier.padding(bottom=Space.medium))
            AssistantOutput(item.presentation,role=AnswerRole.Independent,heading="模型 ${item.record.laneId} · 独立回答")
        }
    }
}
