package io.github.xiaomeng2568.meldwise.ui.presentation

import io.github.xiaomeng2568.meldwise.data.MessageState
import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.provider.MessageRole
import io.github.xiaomeng2568.meldwise.ui.ScreenState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ProcessingTime(val messageId: String?, val seconds: Long?, val waiting: Boolean) {
    override fun toString() = "ProcessingTime(seconds=$seconds, waiting=$waiting)"
}
/** Read-only observer of existing HTTP diagnostics and message state. Makes zero network calls. */
class ProcessingObserver(screen: StateFlow<ScreenState>, scope: CoroutineScope, private val diagnostics: SafeDiagnostics,
    private val clock: ()->Long = {System.nanoTime()/1_000_000}) {
    private val mutable=MutableStateFlow(ProcessingTime(null,null,false))
    val state: StateFlow<ProcessingTime> = mutable.asStateFlow()
    init {
        scope.launch {
            screen.map {it.busy}.distinctUntilChanged().collectLatest {busy ->
                if(!busy) return@collectLatest
                // Reference identity distinguishes two identical closed-schema HTTP records, even at the ring bound.
                val baseline=diagnostics.snapshot().lastOrNull {it.operation==Operation.RESPONSE && it.outcome==Outcome.HTTP}
                val timer=ResponseWaitTimer()
                var messageId: String?=null
                try {
                    while(currentCoroutineContext().isActive) {
                        val current=screen.value
                        val message=current.messages.lastOrNull {it.role==MessageRole.ASSISTANT}
                        if(messageId==null && message?.state in setOf(MessageState.PENDING,MessageState.STREAMING)) {
                            messageId=message?.id
                            mutable.value=ProcessingTime(messageId,null,false)
                        }
                        if(messageId!=null && message?.id==messageId) {
                            val http=diagnostics.snapshot().lastOrNull {it.operation==Operation.RESPONSE && it.outcome==Outcome.HTTP}
                            timer.observe(clock(),http!=null && http !== baseline,
                                message.text.isNotEmpty(),message.state !in setOf(MessageState.PENDING,MessageState.STREAMING))
                            mutable.value=ProcessingTime(messageId,timer.seconds(clock()),timer.running)
                        }
                        delay(100)
                    }
                } finally {
                    timer.observe(clock(),false,false,true)
                    if(messageId!=null) mutable.value=ProcessingTime(messageId,timer.seconds(clock()),false)
                }
            }
        }
    }
}
