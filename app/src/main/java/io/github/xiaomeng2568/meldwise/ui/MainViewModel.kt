package io.github.xiaomeng2568.meldwise.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.xiaomeng2568.meldwise.AppContainer
import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.network.ModelCatalogDiagnostic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ScreenState(val models:List<LlmModel> = emptyList(),val selected:String?=null,
    val messages:List<ChatMessage> = emptyList(),val busy:Boolean=false,val error:String?=null,
    val catalogDiagnostic:ModelCatalogDiagnostic?=null) {
    override fun toString()="ScreenState([REDACTED])"
}
class MainViewModel(private val container:AppContainer):ViewModel() {
    val auth=container.tokens.state
    val inferenceDiagnostic=container.provider.inferenceDiagnostic
    private val mutable=MutableStateFlow(ScreenState())
    val screen:StateFlow<ScreenState> = mutable
    private var chatJob:Job?=null
    private var authJob:Job?=null
    private var loadJob:Job?=null
    init { viewModelScope.launch {
        runCatching { container.tokens.initialize() }
        try { val messages=withContext(Dispatchers.IO) { container.chat.load() }; replace(messages=messages) }
        catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
    } }
    private fun replace(models:List<LlmModel> = screen.value.models,selected:String?=screen.value.selected,
        messages:List<ChatMessage> = screen.value.messages,busy:Boolean=screen.value.busy,error:String?=null,
        catalogDiagnostic:ModelCatalogDiagnostic?=screen.value.catalogDiagnostic) {
        mutable.value=ScreenState(models,selected,messages,busy,error,catalogDiagnostic)
    }
    fun connect(browser:(String)->Unit) {
        if(authJob?.isActive==true || chatJob?.isActive==true || loadJob?.isActive==true) return
        authJob=viewModelScope.launch {
            replace(busy=true)
            try { container.oauth.connect(browser); replace(models=emptyList(),selected=null) }
            catch(cancel:CancellationException) { throw cancel }
            catch(failure:AuthFailure) { replace(error=failure.reason.name) }
            catch(_:Exception) { replace(error="AUTH_UNAVAILABLE") }
            finally { replace(busy=false,error=screen.value.error) }
        }
    }
    fun loadModels() {
        if(screen.value.busy) return
        loadJob=viewModelScope.launch {
            replace(busy=true,catalogDiagnostic=null)
            try { val models=container.provider.listModels(); replace(models=models,selected=null) }
            catch(cancel:CancellationException) { throw cancel }
            catch(failure:ProviderFailure) { replace(error=failure.error.kind.name) }
            catch(_:Exception) { replace(error="MODEL_CATALOG_UNAVAILABLE") }
            finally { replace(busy=false,error=screen.value.error,catalogDiagnostic=container.provider.catalogDiagnostic) }
        }
    }
    fun select(id:String) { if(!screen.value.busy && screen.value.models.any { it.id==id }) replace(selected=id) }
    fun send(text:String) {
        if(screen.value.busy || text.isBlank()) return
        val model=screen.value.selected ?: return
        chatJob=viewModelScope.launch {
            replace(busy=true)
            var messageId:String?=null; val output=StringBuilder(); var final=MessageState.INCOMPLETE
            var periodic:Job?=null
            var storageFailed=false
            suspend fun persist(state:MessageState) {
                messageId?.let { id -> val messages=withContext(Dispatchers.IO) { container.chat.update(id,output.toString(),state) }; replace(messages=messages) }
            }
            try {
                val begin=withContext(Dispatchers.IO) { container.chat.begin(text) }; messageId=begin.first
                replace(messages=withContext(Dispatchers.IO) { container.chat.load() })
                periodic=launch {
                    var savedLength=0
                    while(isActive) {
                        delay(200)
                        if(output.length!=savedLength) {
                            val length=output.length
                            try { persist(MessageState.STREAMING); savedLength=length }
                            catch(cancel:CancellationException) { throw cancel }
                            catch(_:Exception) { storageFailed=true;replace(error="LOCAL_STORAGE_UNAVAILABLE");chatJob?.cancel();break }
                        }
                    }
                }
                container.provider.streamResponse(LlmRequest(model,begin.second)).collect { event ->
                    when(event) {
                        is LlmEvent.TextDelta->output.append(event.text)
                        is LlmEvent.Completed->final=MessageState.COMPLETED
                        is LlmEvent.Incomplete->{ final=MessageState.INCOMPLETE; replace(error=event.error.kind.name) }
                        is LlmEvent.Failed->{ final=MessageState.FAILED; replace(error=event.error.kind.name) }
                        LlmEvent.Cancelled->final=MessageState.CANCELLED
                        else->Unit
                    }
                }
            } catch(cancel:CancellationException) {
                if(final!=MessageState.COMPLETED) final=if(storageFailed) MessageState.INCOMPLETE else MessageState.CANCELLED
                throw cancel
            }
            catch(_:Exception) { final=MessageState.INCOMPLETE; replace(error="LOCAL_STORAGE_OR_STREAM_FAILURE") }
            finally {
                withContext(NonCancellable) {
                    periodic?.cancelAndJoin()
                    val error=screen.value.error
                    try { persist(final) } catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
                    replace(busy=false,error=screen.value.error ?: error)
                }
            }
        }
    }
    fun cancel() { chatJob?.cancel(); authJob?.cancel(); loadJob?.cancel() }
    fun foregroundStopped() { chatJob?.cancel() } // Browser authorization intentionally survives the browser handoff.
    fun disconnect() {
        if(screen.value.busy) return
        viewModelScope.launch { try { container.tokens.clearLocalConnection(); replace(models=emptyList(),selected=null) }
            catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") } }
    }
}
