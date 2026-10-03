// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.presentation

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.ui.modelLabel

/** Presentation categories only: no fourth store and no copies of full persisted records. */
enum class HistoryCategory(val title:String,val description:String,val available:Boolean=true) {
    Chat("对话","和一个模型聊聊"),
    Compare("对比","看看两个模型怎么回答"),
    Collaborate("协作","初答、审阅，再整理成一份回答"),
    Debate("辩论","暂未开放",false);

    val conversationMode:ConversationMode? get()=when(this) {
        Chat->ConversationMode.Single;Collaborate->ConversationMode.Collaborate;else->null
    }
}
class HistoryEntry(val id:String,val category:HistoryCategory,val title:String,val subtitle:String) {
    val compare get()=category==HistoryCategory.Compare
    override fun toString()="HistoryEntry(category=$category, content=[REDACTED])"
}
data class HistorySummary(val category:HistoryCategory,val count:Int) {
    val label get()=if(category.available) "$count 条记录" else "暂未开放"
}
fun historyEntries(category:HistoryCategory,sessions:List<SingleSessionInfo>,runs:List<CompareRun>):List<HistoryEntry> = when(category) {
    HistoryCategory.Chat,HistoryCategory.Collaborate->sessions.filter {it.mode==category.conversationMode}.map {
        HistoryEntry(it.id,category,it.title,modelLabel(it.ref,it.ref.modelId))
    }
    HistoryCategory.Compare->runs.map {run ->HistoryEntry(run.id,category,run.prompt.take(80),
        listOf(run.laneA,run.laneB).joinToString(" × ") {modelLabel(it.modelRef,it.modelDisplayName ?: it.modelRef.modelId)})}
    HistoryCategory.Debate->emptyList()
}
fun historySummaries(sessions:List<SingleSessionInfo>,runs:List<CompareRun>)=HistoryCategory.entries.map {
    HistorySummary(it,when(it) {
        HistoryCategory.Chat->sessions.count {s->s.mode==ConversationMode.Single}
        HistoryCategory.Collaborate->sessions.count {s->s.mode==ConversationMode.Collaborate}
        HistoryCategory.Compare->runs.size
        HistoryCategory.Debate->0
    })
}
