package io.github.xiaomeng2568.meldwise.ui.presentation

enum class ChatPanel { None, Settings, Models, Diagnostics, Appearance, Providers, Gallery, CompareSetup, CollaborateSetup, DebateSetup, Thinking, History, HistoryChat, HistoryCompare, HistoryCollaborate, HistoryDebate, Modes }
data class ChatNavigation(val stack:List<ChatPanel> = emptyList(),val compareMode:Boolean=false,val composerOptions:Boolean=false) {
    val panel get()=stack.lastOrNull() ?: ChatPanel.None
    val historyCategory get()=when(panel) {
        ChatPanel.HistoryChat->HistoryCategory.Chat;ChatPanel.HistoryCompare->HistoryCategory.Compare
        ChatPanel.HistoryCollaborate->HistoryCategory.Collaborate;ChatPanel.HistoryDebate->HistoryCategory.Debate;else->null
    }
    val handlesBack get()=stack.isNotEmpty() || composerOptions || compareMode
    fun open(next:ChatPanel)=if(next==ChatPanel.None) dismiss() else if(panel==next) this else copy(stack=stack+next,composerOptions=false)
    fun back()=when {stack.isNotEmpty()->copy(stack=stack.dropLast(1));composerOptions->copy(composerOptions=false)
        compareMode->copy(compareMode=false);else->this}
    fun dismiss()=copy(stack=emptyList())
    fun openHistory(category:HistoryCategory):ChatNavigation {
        if(!category.available) return this
        val next=when(category) {
            HistoryCategory.Chat->ChatPanel.HistoryChat;HistoryCategory.Compare->ChatPanel.HistoryCompare
            HistoryCategory.Collaborate->ChatPanel.HistoryCollaborate;HistoryCategory.Debate->ChatPanel.HistoryDebate
        }
        val root=stack.indexOfLast {it==ChatPanel.History}
        val parent=if(root>=0) copy(stack=stack.take(root+1),composerOptions=false) else open(ChatPanel.History)
        return parent.open(next)
    }
    fun openHistoryEntry(entry:HistoryEntry):ChatNavigation {
        require(entry.category.available)
        return dismiss().copy(compareMode=entry.compare,composerOptions=false)
    }
    fun selectMode(compare:Boolean)=copy(stack=if(compare) listOf(ChatPanel.CompareSetup) else emptyList(),compareMode=compare,composerOptions=false)
}
