// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

@Composable internal fun DebateSetup(state:DebateUiState,busy:Boolean,onPick:(DebateRole)->Unit,
    onThinking:(DebateRole,ReasoningPreference)->Unit) {
    PanelColumn("辩论") {
        Text(DEBATE_EXPLANATION,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        DebateRole.entries.forEach {role ->
            val model=state.selection.model(role)
            Column(Modifier.fillMaxWidth().testTag("debateRole-${role.name}"),verticalArrangement=Arrangement.spacedBy(Space.micro)) {
                MeldwiseTextButton(enabled=!busy,onClick={onPick(role)},modifier=Modifier.fillMaxWidth().heightIn(min=Sizes.touch).testTag("debatePick-${role.name}")) {
                    Text("${role.label}：${model?.let {modelLabel(it.ref,it.displayName ?: it.ref.modelId)} ?: "请选择"}",
                        Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                    MeldwiseIcon(Glyph.Forward,opticalSize=18.dp)
                }
                model?.let {ThinkingChoices(it.ref,it.preference,!busy) {p->onThinking(role,p)}}
            }
        }
        state.validation?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
        Text(DEBATE_FLOW,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(DEBATE_USAGE,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun DebateSharingDialog(providers:Set<String>,onContinue:()->Unit,onCancel:()->Unit) {
    AlertDialog(onDismissRequest=onCancel,title={Text("共享可见内容以开始辩论？")},
        text={Text(debateConsentCopy(providers))},
        confirmButton={MeldwiseTextButton(onClick=onContinue) {Text("继续并共享")}},
        dismissButton={MeldwiseTextButton(onClick=onCancel) {Text("取消")}})
}
@Composable internal fun DebateMessage(item:DebateMessageItem) {
    when(item) {
        is DebateMessageItem.Prompt->UserPrompt(item.message.text)
        is DebateMessageItem.Stage->{
            val s=item.stage
            val status=debateStageStatus(s)
            val time=debateProcessingCaption(s)
            Column(Modifier.fillMaxWidth().testTag("debateStage-${s.type.name}"),verticalArrangement=Arrangement.spacedBy(Space.small)) {
                AssistantOutput(CompareLane(s.model.ref,s.model.displayName ?: s.model.ref.modelId,
                    when(s.state) {
                        DebateStageState.Pending,DebateStageState.NotRun->LaneState.Pending
                        DebateStageState.Running->if(s.output.isNotEmpty()) LaneState.Streaming else LaneState.Waiting
                        DebateStageState.Complete->LaneState.Completed;DebateStageState.Failed->LaneState.Failed
                        DebateStageState.Cancelled->LaneState.Cancelled;DebateStageState.Interrupted->LaneState.Interrupted
                    },s.output,reasoningPresentation(s.reasoning),s.processingDuration),
                    role=debateAnswerRole(s.type),heading=debateStageLabel(s.type),
                    statusLabel=listOfNotNull(status,time).joinToString(" · ").ifEmpty {null},
                    accessibleState=status ?: "已完成",
                    disclosureKey=if(s.type in setOf(DebateStageType.INITIAL_A,DebateStageType.INITIAL_B)) "debate/${item.key}" else null)
                s.error?.takeIf {it!=ErrorKind.CANCELLED}?.let {kind ->
                    Text(errorLabel(kind.name),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                    var details by remember(s.stageId) {mutableStateOf(false)}
                    MeldwiseTextButton(onClick={details=!details}) {Text(if(details) "收起详情" else "查看详情")}
                    if(details) Text("${debateStageLabel(s.type)} · ${kind.name}",style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
