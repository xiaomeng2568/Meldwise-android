package io.github.xiaomeng2568.meldwise.ui.presentation

enum class ChatPanel { None, Settings, Models, Diagnostics, Appearance, Providers, Gallery, CompareSetup, Thinking, History, Modes }
data class ChatNavigation(val stack:List<ChatPanel> = emptyList(),val compareMode:Boolean=false,val composerOptions:Boolean=false) {
    val panel get()=stack.lastOrNull() ?: ChatPanel.None
    val handlesBack get()=stack.isNotEmpty() || composerOptions || compareMode
    fun open(next:ChatPanel)=if(next==ChatPanel.None) dismiss() else if(panel==next) this else copy(stack=stack+next,composerOptions=false)
    fun back()=when {stack.isNotEmpty()->copy(stack=stack.dropLast(1));composerOptions->copy(composerOptions=false)
        compareMode->copy(compareMode=false);else->this}
    fun dismiss()=copy(stack=emptyList())
    fun selectMode(compare:Boolean)=copy(stack=if(compare) listOf(ChatPanel.CompareSetup) else emptyList(),compareMode=compare,composerOptions=false)
}
