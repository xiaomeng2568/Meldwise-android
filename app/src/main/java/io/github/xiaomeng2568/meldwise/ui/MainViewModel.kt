package io.github.xiaomeng2568.meldwise.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.xiaomeng2568.meldwise.AppContainer
import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.network.ModelCatalogDiagnostic
import io.github.xiaomeng2568.meldwise.security.ApiKeyState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ScreenState(val models:List<LlmModel> = emptyList(),val selected:ModelRef?=null,
    val messages:List<ChatMessage> = emptyList(),val busy:Boolean=false,val error:String?=null,
    val catalogDiagnostic:ModelCatalogDiagnostic?=null,val providerId:String=ProviderIds.CHATGPT,
    val historyRef:ModelRef=ModelRef(ProviderIds.CHATGPT,"UNKNOWN"),val ready:Boolean=false) {
    override fun toString()="ScreenState([REDACTED])"
}
class MainViewModel(private val container:AppContainer):ViewModel() {
    val auth=container.tokens.state
    @OptIn(ExperimentalCoroutinesApi::class)
    val inferenceDiagnostic by lazy { screen.map { it.providerId }.distinctUntilChanged().flatMapLatest {
        if(it==ProviderIds.CHATGPT) container.provider.inferenceDiagnostic else container.deepSeek.inferenceDiagnostic
    }
    }
    private val apiState=MutableStateFlow(ApiKeyState.MISSING)
    val deepSeekState:StateFlow<ApiKeyState> = apiState.asStateFlow()
    private val localRestoreFinished=MutableStateFlow(false)
    internal val localRestorationFinished:StateFlow<Boolean> = localRestoreFinished.asStateFlow()
    private val mutable=MutableStateFlow(ScreenState(busy=true))
    val screen:StateFlow<ScreenState> = mutable
    private var chatJob:Job?=null
    private var authJob:Job?=null
    private var loadJob:Job?=null
    init { viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { container.tokens.initialize() } }
        try {
            val messages=withContext(Dispatchers.IO) { apiState.value=container.deepSeekCredentials.state();container.chat.load() }
            val ref=container.chat.activeRef()
            replace(messages=messages,providerId=ref.providerId,historyRef=ref,ready=localReady(ref.providerId))
        }
        catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
        finally { replace(busy=false,error=screen.value.error);localRestoreFinished.value=true }
    } }
    private suspend fun localReady(id:String)=withContext(Dispatchers.IO) { container.providers.get(id).validateConnection()==ProviderStatus.READY }
    private fun replace(models:List<LlmModel> = screen.value.models,selected:ModelRef?=screen.value.selected,
        messages:List<ChatMessage> = screen.value.messages,busy:Boolean=screen.value.busy,error:String?=null,
        catalogDiagnostic:ModelCatalogDiagnostic?=screen.value.catalogDiagnostic,providerId:String=screen.value.providerId,
        historyRef:ModelRef=screen.value.historyRef,ready:Boolean=screen.value.ready) {
        mutable.value=ScreenState(models,selected,messages,busy,error,catalogDiagnostic,providerId,historyRef,ready)
    }
    fun chooseProvider(id:String) {
        if(screen.value.busy || id==screen.value.providerId) return
        replace(busy=true)
        viewModelScope.launch {
            try {
                val messages=withContext(Dispatchers.IO) { container.chat.activateProvider(id) }
                replace(providerId=id,messages=messages,historyRef=container.chat.activeRef(),models=emptyList(),selected=null,
                    catalogDiagnostic=null,ready=localReady(id))
            } catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
            finally { replace(busy=false,error=screen.value.error) }
        }
    }
    fun saveApiKey(key:String) = changeApiKey(key)
    fun removeApiKey() = changeApiKey(null)
    private fun changeApiKey(key:String?) {
        if(screen.value.busy) return
        replace(busy=true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { if(key==null) container.deepSeekCredentials.remove() else container.deepSeekCredentials.replace(key) }
                container.deepSeek.invalidateCatalog()
                apiState.value=withContext(Dispatchers.IO) { container.deepSeekCredentials.state() }
                replace(models=emptyList(),selected=null,catalogDiagnostic=null,ready=localReady(screen.value.providerId))
            } catch(_:Exception) {
                apiState.value=withContext(Dispatchers.IO) { container.deepSeekCredentials.state() }
                replace(ready=false,error="STORAGE")
            } finally { replace(busy=false,error=screen.value.error) }
        }
    }
    fun showLegacy() {
        if(screen.value.busy || screen.value.providerId!=ProviderIds.CHATGPT) return
        replace(busy=true)
        viewModelScope.launch {
            try { val ref=ModelRef(ProviderIds.CHATGPT,"UNKNOWN")
                replace(messages=withContext(Dispatchers.IO) { container.chat.activate(ref) },selected=null,historyRef=ref)
            } catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
            finally { replace(busy=false,error=screen.value.error) }
        }
    }
    fun connect(browser:(String)->Unit) {
        if(screen.value.busy || screen.value.providerId!=ProviderIds.CHATGPT) return
        replace(busy=true)
        authJob=viewModelScope.launch {
            replace(busy=true)
            try { container.oauth.connect(browser); replace(models=emptyList(),selected=null) }
            catch(cancel:CancellationException) { throw cancel }
            catch(failure:AuthFailure) { replace(error=failure.reason.name) }
            catch(_:Exception) { replace(error="AUTH_UNAVAILABLE") }
            finally { replace(busy=false,error=screen.value.error,ready=localReady(ProviderIds.CHATGPT)) }
        }
    }
    fun loadModels() {
        if(screen.value.busy) return
        replace(busy=true,catalogDiagnostic=null)
        loadJob=viewModelScope.launch {
            replace(busy=true,catalogDiagnostic=null)
            val id=screen.value.providerId
            try { val models=container.providers.get(id).listModels(); replace(models=models,selected=null) }
            catch(cancel:CancellationException) { throw cancel }
            catch(failure:ProviderFailure) { replace(error=failure.error.kind.name) }
            catch(_:Exception) { replace(error="MODEL_CATALOG_UNAVAILABLE") }
            finally { replace(busy=false,error=screen.value.error,ready=localReady(id),
                catalogDiagnostic=if(id==ProviderIds.CHATGPT) container.provider.catalogDiagnostic else container.deepSeek.catalogDiagnostic) }
        }
    }
    fun select(id:String) {
        if(screen.value.busy || screen.value.models.none { it.id==id }) return
        val ref=ModelRef(screen.value.providerId,id);replace(busy=true)
        viewModelScope.launch {
            try { replace(messages=withContext(Dispatchers.IO) { container.chat.activate(ref) },selected=ref,historyRef=ref) }
            catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
            finally { replace(busy=false,error=screen.value.error) }
        }
    }
    fun send(text:String) {
        if(screen.value.busy || text.isBlank()) return
        val model=screen.value.selected ?: return
        replace(busy=true)
        chatJob=viewModelScope.launch {
            replace(busy=true)
            var messageId:String?=null; val output=StringBuilder(); var final=MessageState.INCOMPLETE
            var periodic:Job?=null
            var storageFailed=false
            suspend fun persist(state:MessageState) {
                messageId?.let { id -> val messages=withContext(Dispatchers.IO) { container.chat.update(id,output.toString(),state) }; replace(messages=messages) }
            }
            try {
                if(!container.providers.ready(model)) throw ProviderFailure(LlmError(ErrorKind.AUTHENTICATION))
                require(model==container.chat.activeRef())
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
                container.providers.get(model.providerId).streamResponse(LlmRequest(model.modelId,begin.second)).collect { event ->
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
            catch(f:ProviderFailure) { final=MessageState.FAILED;replace(error=f.error.kind.name) }
            catch(_:Exception) { final=MessageState.INCOMPLETE; replace(error="LOCAL_STORAGE_OR_STREAM_FAILURE") }
            finally {
                withContext(NonCancellable) {
                    periodic?.cancelAndJoin()
                    val error=screen.value.error
                    try { persist(final) } catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
                    replace(busy=false,error=screen.value.error ?: error,ready=localReady(model.providerId))
                }
            }
        }
    }
    fun cancel() { chatJob?.cancel(); authJob?.cancel(); loadJob?.cancel() }
    fun foregroundStopped() { chatJob?.cancel() } // Browser authorization intentionally survives the browser handoff.
    fun disconnect() {
        if(screen.value.busy || screen.value.providerId!=ProviderIds.CHATGPT) return
        replace(busy=true)
        viewModelScope.launch { try { container.tokens.clearLocalConnection(); replace(models=emptyList(),selected=null,ready=false) }
            catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
            finally { replace(busy=false,error=screen.value.error) } }
    }
}
