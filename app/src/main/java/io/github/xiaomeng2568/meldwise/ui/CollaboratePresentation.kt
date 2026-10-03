// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

fun collaborateStageLabel(type:CollaborateStageType)=when(type) {
    CollaborateStageType.INITIAL->"初答";CollaborateStageType.REVIEW->"审阅";CollaborateStageType.SYNTHESIS->"综合"
}
fun collaborateLaneState(stage:CollaborateStage)=when(stage.state) {
    CollaborateStageState.Pending,CollaborateStageState.NotRun->LaneState.Pending
    CollaborateStageState.Running->if(stage.output.isNotEmpty()) LaneState.Streaming else if(stage.reasoning.text.isNotEmpty()) LaneState.Thinking else LaneState.Waiting
    CollaborateStageState.Complete->LaneState.Completed;CollaborateStageState.Failed->LaneState.Failed
    CollaborateStageState.Cancelled->LaneState.Cancelled;CollaborateStageState.Interrupted->LaneState.Interrupted
}
fun collaborateErrorLabel(kind:ErrorKind)=if(kind==ErrorKind.PLAN_USAGE_LIMIT)
    "当前 ChatGPT 套餐或所选模型的可用额度已达到限制。可以稍后再试，或调整协作模型后重新执行。" else errorLabel(kind.name)
sealed class CollaborateMessageItem(val key:String) {
    class Prompt(val message:ChatMessage):CollaborateMessageItem(message.id)
    class Stage(val roundId:String,val stage:CollaborateStage):CollaborateMessageItem(stage.stageId)
    override fun toString()="CollaborateMessageItem([REDACTED])"
}
fun collaborateMessageItems(c:Conversation):List<CollaborateMessageItem> {
    require(c.mode==ConversationMode.Collaborate)
    return c.messages.flatMap {user ->listOf(CollaborateMessageItem.Prompt(user))+
        c.rounds.single {it.userMessageId==user.id}.stages.map {CollaborateMessageItem.Stage(c.rounds.single {r ->r.userMessageId==user.id}.roundId,it)}}
}
@Composable internal fun CollaborateMessage(item:CollaborateMessageItem) {
    when(item) {
        is CollaborateMessageItem.Prompt->UserPrompt(item.message.text)
        is CollaborateMessageItem.Stage->{
            val s=item.stage
            Column(Modifier.fillMaxWidth().testTag("collaborateStage/${s.order}"),verticalArrangement=Arrangement.spacedBy(Space.small)) {
                Text(collaborateStageLabel(s.type)+if(s.state==CollaborateStageState.NotRun) " · 未执行" else "",
                    style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(s.state!=CollaborateStageState.NotRun) {
                    AssistantOutput(CompareLane(s.model.ref,s.model.displayName ?: s.model.ref.modelId,collaborateLaneState(s),
                        s.output,reasoningPresentation(s.reasoning),s.processingDuration))
                    s.error?.let {kind ->
                        Text(collaborateErrorLabel(kind),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                        var details by remember {mutableStateOf(false)}
                        TextButton(onClick={details=!details}) {Text(if(details) "收起详情" else "查看详情")}
                        if(details) Text("${collaborateStageLabel(s.type)} · providerId=${s.model.ref.providerId} · ${kind.name}",
                            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
