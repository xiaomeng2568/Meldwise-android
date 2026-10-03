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
import io.github.xiaomeng2568.meldwise.data.ChatMessage
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.modelLabel
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

@Composable fun UserPrompt(text:String,caption:String?=null) {
    BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.CenterEnd) {
        Surface(Modifier.widthIn(max=maxWidth*Sizes.userFraction).wrapContentWidth().testTag("userPrompt"),
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
@Composable fun AssistantOutput(lane:CompareLane,modifier:Modifier=Modifier,onDetails:(()->Unit)?=null) {
    val status=assistantStatus(lane.state,lane.seconds)
    Column(modifier.fillMaxWidth().testTag("assistantOutput").semantics {stateDescription=laneLabel(lane.state)},
        verticalArrangement=Arrangement.spacedBy(Space.small)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Space.small)) {
            Column(Modifier.weight(1f)) {
                Text(modelLabel(lane.ref,lane.displayName),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                status?.let {Text(it,style=MaterialTheme.typography.labelMedium,
                    color=if(lane.state==LaneState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
        ReasoningPanel(lane.reasoning)
        ContentRenderer(remember(lane.answer) {ContentParser.parse(lane.answer)})
        if(lane.answer.isNotEmpty()) MessageActions(lane.answer)
        if(onDetails!=null && lane.state in setOf(LaneState.Failed,LaneState.Incomplete,LaneState.Interrupted)) TextButton(onClick=onDetails) {Text("查看详情")}
    }
}
@Composable private fun MessageActions(text:String) {
    var menu by remember {mutableStateOf(false)}
    var plain by remember {mutableStateOf(false)}
    Row(verticalAlignment=Alignment.CenterVertically) {
        CopyAction(text)
        Box {
            SoftAction(Glyph.More,"更多内容操作",{menu=true})
            MaterialTheme(shapes=MaterialTheme.shapes.copy(extraSmall=Radius.medium)) {
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text(if(plain) "恢复排版" else "按纯文本查看")},onClick={plain=!plain;menu=false})
                }
            }
        }
    }
    if(plain) LiteralSurface(text,"纯文本 · Plain text")
}
/** Compatibility entry point for component previews; shares the actual flat renderer. */
@Composable fun CompareResultCard(lane:CompareLane,modifier:Modifier=Modifier)=AssistantOutput(lane,modifier)
@Composable fun CompareMessage(item:CompareMessageItem) {
    when(item) {
        is CompareMessageItem.Prompt->UserPrompt(item.item.content)
        is CompareMessageItem.Output->AssistantOutput(item.presentation)
    }
}
