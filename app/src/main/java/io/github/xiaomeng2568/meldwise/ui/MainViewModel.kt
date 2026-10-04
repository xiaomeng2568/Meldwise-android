package io.github.xiaomeng2568.meldwise.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.xiaomeng2568.meldwise.AppContainer
import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.network.ModelCatalogDiagnostic
import io.github.xiaomeng2568.meldwise.security.ApiKeyState
import io.github.xiaomeng2568.meldwise.ui.presentation.DebateRole
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
    private val catalogLoader=ModelCatalogLoader(container.providers,container.modelCatalogCache,
        diagnostic={id ->if(id==ProviderIds.CHATGPT) container.provider.catalogDiagnostic else container.deepSeek.catalogDiagnostic},
        hydrate={id,models ->if(id==ProviderIds.CHATGPT) container.provider.restoreCatalog(models) else container.deepSeek.restoreCatalog(models)})
    val catalogStatus=catalogLoader.state
    private val cache=MutableStateFlow<Map<String,List<LlmModel>>>(emptyMap())
    val catalogs:StateFlow<Map<String,List<LlmModel>>> = cache.asStateFlow()
    val thinking=MutableStateFlow(ReasoningPreference.Auto)
    val compareRun=MutableStateFlow<CompareRun?>(null)
    val compareHistory=MutableStateFlow<List<CompareRun>>(emptyList())
    val singleHistory=MutableStateFlow<List<SingleSessionInfo>>(emptyList())
    val sharingRequest=MutableStateFlow<String?>(null)
    val conversation=MutableStateFlow<Conversation?>(null)
    val conversationMode=MutableStateFlow(ConversationMode.Single)
    val collaborateConfig=MutableStateFlow<CollaborateConfig?>(null)
    val collaborateSharing=MutableStateFlow<CollaborateConfig?>(null)
    private var pendingCollaborate:PreparedCollaborate?=null
    private var collaborateJob:Job?=null
    private var pendingTurn:PreparedTurn?=null
    private var pendingThinking=ReasoningPreference.Auto
    private fun clearSharing() {pendingTurn=null;sharingRequest.value=null;pendingCollaborate=null;collaborateSharing.value=null;debateWorkflow.dismissSharing()}
    private var compareJob:Job?=null
    private suspend fun refreshHistory(syncDebate:Boolean=true):Unit=withContext(Dispatchers.IO) {
        singleHistory.value=container.chat.sessions();compareHistory.value=container.compare.load().reversed()
        conversation.value=container.chat.activeConversation();conversationMode.value=container.chat.mode();collaborateConfig.value=container.chat.collaborateConfig()
        if(syncDebate) debateWorkflow.restore(container.chat.debateConfig())
    }
    // Presentation-only timing observation; provider, refresh and terminal logic remain unchanged.
    val processingTime=io.github.xiaomeng2568.meldwise.ui.presentation.ProcessingObserver(screen,viewModelScope,container.network.diagnostics).state
    private var chatJob:Job?=null
    private var authJob:Job?=null
    private var loadJob:Job?=null
    private val debateWorkflow:DebateWorkflow=DebateWorkflow(viewModelScope,container.chat,container.providers,{cache.value},
        {screen.value.busy},{replace(busy=it,error=if(it) null else screen.value.error)},
        {replace(error=it)},{c->conversation.value=c;replace(messages=c?.messages ?: emptyList())},
        {refreshHistory(syncDebate=false)},executor={DebateExecutor(container.providers,container.chat,usage=container.usage)})
    val debateState=debateWorkflow.state
    init { viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { container.tokens.initialize() } }
        try {
            val messages=withContext(Dispatchers.IO) { apiState.value=container.deepSeekCredentials.state();container.chat.load() }
            val ref=container.chat.activeRef()
            replace(messages=messages,providerId=ref.providerId,historyRef=ref,ready=localReady(ref.providerId))
            refreshHistory();thinking.value=if(ref.providerId==ProviderIds.DEEPSEEK) ReasoningPreference.Off else ReasoningPreference.Auto
            catalogLoader.restore()
            syncCatalog(catalogLoader.state.value)
        }
        catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
        finally { replace(busy=false,error=screen.value.error);localRestoreFinished.value=true }
        // Local state is usable before stale catalogs refresh. No chat or authorization on startup.
        catalogLoader.refreshStaleOnce()
    } }
    init {viewModelScope.launch {catalogLoader.state.collect {syncCatalog(it)}}}
    private fun syncCatalog(status:CatalogUiState) {
        cache.value=status.catalogs
        debateWorkflow.refreshAvailability()
        val id=screen.value.providerId;val ref=screen.value.historyRef
        val models=status.catalogs[id] ?: emptyList()
        replace(models=models,selected=ref.takeIf {it.providerId==id && models.any {m->m.id==it.modelId}},
            catalogDiagnostic=status.diagnostics[id],error=screen.value.error)
    }
    fun dismissCatalogNotice(id:Long) {viewModelScope.launch {catalogLoader.dismiss(id)}}
    private suspend fun localReady(id:String)=withContext(Dispatchers.IO) { container.providers.get(id).validateConnection()==ProviderStatus.READY }
    private fun replace(models:List<LlmModel> = screen.value.models,selected:ModelRef?=screen.value.selected,
        messages:List<ChatMessage> = screen.value.messages,busy:Boolean=screen.value.busy,error:String?=null,
        catalogDiagnostic:ModelCatalogDiagnostic?=screen.value.catalogDiagnostic,providerId:String=screen.value.providerId,
        historyRef:ModelRef=screen.value.historyRef,ready:Boolean=screen.value.ready) {
        mutable.value=ScreenState(models,selected,messages,busy,error,catalogDiagnostic,providerId,historyRef,ready)
    }
    fun chooseProvider(id:String) {
        if(screen.value.busy || id==screen.value.providerId) return
        clearSharing()
        replace(busy=true)
        viewModelScope.launch {
            try {
                val messages=withContext(Dispatchers.IO) { container.chat.activateProvider(id) }
                thinking.value=if(id==ProviderIds.DEEPSEEK) ReasoningPreference.Off else ReasoningPreference.Auto
                val ref=container.chat.activeRef();val models=cache.value[id] ?: emptyList()
                replace(providerId=id,messages=messages,historyRef=ref,models=models,selected=ref.takeIf {models.any {m->m.id==it.modelId}},
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
                catalogLoader.invalidate(ProviderIds.DEEPSEEK)
                withContext(Dispatchers.IO) { if(key==null) container.deepSeekCredentials.remove() else container.deepSeekCredentials.replace(key) }
                container.deepSeek.invalidateCatalog()
                cache.value=cache.value-ProviderIds.DEEPSEEK
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
                val messages=withContext(Dispatchers.IO) {container.chat.sessions().firstOrNull {it.ref==ref}?.let {container.chat.activateSession(it.id)}}
                if(messages==null) replace(error="LEGACY_HISTORY_UNAVAILABLE") else replace(messages=messages,selected=null,historyRef=ref)
                refreshHistory()
            } catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
            finally { replace(busy=false,error=screen.value.error) }
        }
    }
    fun connect(browser:(String)->Unit) {
        if(screen.value.busy || screen.value.providerId!=ProviderIds.CHATGPT) return
        replace(busy=true)
        authJob=viewModelScope.launch {
            replace(busy=true)
            try {catalogLoader.invalidate(ProviderIds.CHATGPT);container.oauth.connect(browser);container.provider.invalidateCatalog(); replace(models=emptyList(),selected=null) }
            catch(cancel:CancellationException) { throw cancel }
            catch(failure:AuthFailure) { replace(error=failure.reason.name) }
            catch(_:Exception) { replace(error="AUTH_UNAVAILABLE") }
            finally { replace(busy=false,error=screen.value.error,ready=localReady(ProviderIds.CHATGPT)) }
        }
    }
    fun loadModels() {
        refreshProviderModels(screen.value.providerId)
    }
    fun refreshProviderModels(id:String) {
        if(screen.value.busy) return
        if(id !in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK)) return
        loadJob=viewModelScope.launch {catalogLoader.load(id)}
    }
    fun select(id:String) {
        if(screen.value.busy || screen.value.models.none { it.id==id }) return
        clearSharing()
        val ref=ModelRef(screen.value.providerId,id);replace(busy=true)
        viewModelScope.launch {
            try { replace(messages=withContext(Dispatchers.IO) { container.chat.activate(ref) },selected=ref,historyRef=ref) }
            catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
            finally { replace(busy=false,error=screen.value.error) }
        }
    }
    fun selectRef(ref:ModelRef) {
        if(screen.value.busy || cache.value[ref.providerId]?.none {it.id==ref.modelId}!=false) return
        clearSharing()
        replace(busy=true)
        viewModelScope.launch {try {
            thinking.value=if(ref.providerId==ProviderIds.DEEPSEEK) ReasoningPreference.Off else ReasoningPreference.Auto
            replace(messages=withContext(Dispatchers.IO) {container.chat.activate(ref)},selected=ref,historyRef=ref,
                providerId=ref.providerId,models=cache.value[ref.providerId] ?: emptyList(),ready=localReady(ref.providerId),catalogDiagnostic=null)
            refreshHistory()
        } catch(_:Exception) {replace(error="LOCAL_STORAGE_UNAVAILABLE")} finally {replace(busy=false,error=screen.value.error)} }
    }
    fun send(text:String) {
        if(screen.value.busy || text.isBlank()) return
        if(conversationMode.value==ConversationMode.Debate) {debateWorkflow.send(text);return}
        if(conversationMode.value==ConversationMode.Collaborate) {sendCollaborate(text);return}
        val model=screen.value.selected ?: return
        val preference=thinking.value
        if(!ReasoningPolicy.supported(model,preference)) {replace(error="UNSUPPORTED_CAPABILITY");return}
        clearSharing();replace(busy=true)
        chatJob=viewModelScope.launch {
            try {
                val turn=withContext(Dispatchers.IO) {container.chat.prepare(text,screen.value.models.firstOrNull {it.id==model.modelId}?.displayName)}
                if(turn.requiresSharing) {
                    pendingTurn=turn;pendingThinking=preference;sharingRequest.value=model.providerId
                    replace(busy=false)
                } else {currentCoroutineContext().ensureActive();executeTurn(turn,preference,false)}
            } catch(cancel:CancellationException) {replace(busy=false);throw cancel}
            catch(_:Exception) {replace(busy=false,error="LOCAL_STORAGE_UNAVAILABLE")}
        }
    }
    fun cancelSharing() {clearSharing()}
    fun continueSharing() {
        if(screen.value.busy) return
        val turn=pendingTurn ?: return;val preference=pendingThinking
        clearSharing();executeTurn(turn,preference,true)
    }
    private fun executeTurn(turn:PreparedTurn,preference:ReasoningPreference,allowSharing:Boolean) {
        val model=turn.ref
        replace(busy=true)
        chatJob=viewModelScope.launch {
            replace(busy=true)
            var messageId:String?=null; val output=StringBuilder(); var final=MessageState.INCOMPLETE
            var periodic:Job?=null
            var storageFailed=false
            var accounting:UsageOperation?=null
            var reasoning=ReasoningRecord()
            suspend fun persist(state:MessageState) {
                messageId?.let { id -> val messages=withContext(Dispatchers.IO) { container.chat.update(id,output.toString(),state,reasoning) }; replace(messages=messages) }
            }
            try {
                if(!container.providers.ready(model)) throw ProviderFailure(LlmError(ErrorKind.AUTHENTICATION))
                require(model==container.chat.activeRef())
                val begin=withContext(Dispatchers.IO) { container.chat.beginPrepared(turn,allowSharing) }; messageId=begin.first
                accounting=container.usage.begin(UsageOperationId(UsageMode.SINGLE,begin.first,container.chat.conversationId()))
                replace(messages=withContext(Dispatchers.IO) { container.chat.load() })
                periodic=launch {
                    var savedLength=0
                    while(isActive) {
                        delay(200)
                        if(output.length+reasoning.text.length!=savedLength) {
                            val length=output.length+reasoning.text.length
                            try { persist(MessageState.STREAMING); savedLength=length }
                            catch(cancel:CancellationException) { throw cancel }
                            catch(_:Exception) { storageFailed=true;replace(error="LOCAL_STORAGE_UNAVAILABLE");chatJob?.cancel();break }
                        }
                    }
                }
                val provider=container.providers.get(model.providerId)
                val request=LlmRequest(model.modelId,begin.second,reasoning=preference)
                accounting.stream(UsageSlot.SINGLE,model) {provider.streamResponse(request)}.collect { event ->
                    when(event) {
                        is LlmEvent.TextDelta->output.append(event.text)
                        is LlmEvent.ReasoningDelta->{
                            require(model.providerId==ProviderIds.DEEPSEEK || event.kind==ReasoningContent.Summary)
                            require(reasoning.text.length+event.text.length<=ReasoningReader.MAX_CHARS)
                            reasoning=ReasoningRecord(reasoning.text+event.text,event.kind,ReasoningPhase.Streaming)
                        }
                        is LlmEvent.ReasoningDone->reasoning=ReasoningRecord(reasoning.text,reasoning.kind,ReasoningPhase.Completed)
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
                    if(reasoning.text.isNotEmpty()) reasoning=ReasoningRecord(reasoning.text,reasoning.kind,
                        if(final==MessageState.COMPLETED || reasoning.phase==ReasoningPhase.Completed) ReasoningPhase.Completed else ReasoningPhase.Interrupted)
                    try { persist(final) } catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
                    accounting?.finish(UsageSlot.SINGLE,when(final) {
                        MessageState.COMPLETED->RequestOutcome.COMPLETED;MessageState.FAILED->RequestOutcome.FAILED
                        MessageState.CANCELLED->RequestOutcome.CANCELLED;else->RequestOutcome.INTERRUPTED
                    })
                    replace(busy=false,error=screen.value.error ?: error,ready=localReady(model.providerId))
                    runCatching {refreshHistory()}
                }
            }
        }
    }
    fun setThinking(value:ReasoningPreference) {if(!screen.value.busy && ReasoningPolicy.supported(screen.value.selected ?: screen.value.historyRef,value)) thinking.value=value}
    fun newChat() {
        if(screen.value.busy) return
        clearSharing()
        val ref=screen.value.selected ?: screen.value.historyRef
        replace(busy=true)
        viewModelScope.launch {try {replace(messages=withContext(Dispatchers.IO) {container.chat.newSession(ref)},historyRef=ref);compareRun.value=null;refreshHistory()}
            catch(_:Exception) {replace(error="LOCAL_STORAGE_UNAVAILABLE")} finally {replace(busy=false,error=screen.value.error)} }
    }
    fun newCollaborate() {
        if(screen.value.busy) return
        clearSharing();replace(busy=true)
        viewModelScope.launch {try {
            replace(messages=withContext(Dispatchers.IO) {container.chat.newCollaborate()});compareRun.value=null;refreshHistory()
        } catch(_:Exception) {replace(error="LOCAL_STORAGE_UNAVAILABLE")} finally {replace(busy=false,error=screen.value.error)} }
    }
    fun newDebate() {
        if(screen.value.busy) return
        clearSharing();compareRun.value=null;debateWorkflow.newDebate()
    }
    fun chooseDebateModel(role:DebateRole,ref:ModelRef)=debateWorkflow.choose(role,ref)
    fun setDebateThinking(role:DebateRole,value:ReasoningPreference)=debateWorkflow.thinking(role,value)
    fun retryDebate(id:String)=debateWorkflow.retry(id)
    fun continueDebateSharing()=debateWorkflow.continueSharing()
    fun configureCollaborate(selection:CollaborateSubmission) {
        if(screen.value.busy || conversationMode.value!=ConversationMode.Collaborate) return
        fun model(ref:ModelRef,p:ReasoningPreference):CollaborateModel? {
            val item=cache.value[ref.providerId]?.firstOrNull {it.id==ref.modelId} ?: return null
            return CollaborateModel(ref,item.displayName,p)
        }
        val a=model(selection.a,selection.pa) ?: return;val b=model(selection.b,selection.pb) ?: return
        val config=CollaborateConfig(a,b,selection.reviewIntensity,selection.synthesisRole)
        if(a.ref==b.ref || !ReasoningPolicy.supported(a.ref,a.preference) || !ReasoningPolicy.supported(b.ref,b.preference)) return
        clearSharing();replace(busy=true)
        viewModelScope.launch {try {
            withContext(Dispatchers.IO) {container.chat.configureCollaborate(config)};refreshHistory()
        } catch(_:Exception) {replace(error="LOCAL_STORAGE_UNAVAILABLE")} finally {replace(busy=false,error=screen.value.error)} }
    }
    private fun sendCollaborate(text:String,retryId:String?=null) {
        clearSharing();replace(busy=true)
        collaborateJob=viewModelScope.launch {
            try {
                val plan=withContext(Dispatchers.IO) {if(retryId==null) container.chat.prepareCollaborate(text) else container.chat.prepareCollaborateRetry(retryId)}
                if(listOf(plan.config.primary,plan.config.reviewer).any {m ->cache.value[m.ref.providerId]?.none {it.id==m.ref.modelId}!=false}) {
                    replace(busy=false,error="MODEL_UNAVAILABLE");return@launch
                }
                if(plan.requiresSharing) {pendingCollaborate=plan;collaborateSharing.value=plan.config;replace(busy=false)}
                else {currentCoroutineContext().ensureActive();executeCollaborate(plan,false)}
            } catch(cancel:CancellationException) {replace(busy=false);throw cancel}
            catch(_:Exception) {replace(busy=false,error="LOCAL_STORAGE_UNAVAILABLE")}
        }
    }
    fun continueCollaborateSharing() {
        if(screen.value.busy) return
        val plan=pendingCollaborate ?: return
        clearSharing();executeCollaborate(plan,true)
    }
    fun retryCollaborate(id:String) {if(!screen.value.busy) sendCollaborate("",id)}
    private fun executeCollaborate(plan:PreparedCollaborate,allowSharing:Boolean) {
        replace(busy=true)
        collaborateJob=viewModelScope.launch {
            try {
                CollaborateExecutor(container.providers,container.chat,usage=container.usage).execute(plan,allowSharing) {c ->conversation.value=c;replace(messages=c.messages)}
            } catch(cancel:CancellationException) {throw cancel}
            catch(_:Exception) {replace(error="LOCAL_STORAGE_UNAVAILABLE")}
            finally {withContext(NonCancellable) {runCatching {refreshHistory()};replace(busy=false,error=screen.value.error)}}
        }
    }
    fun openSession(id:String) {
        if(screen.value.busy) return;replace(busy=true)
        clearSharing()
        viewModelScope.launch {try {val messages=withContext(Dispatchers.IO) {container.chat.activateSession(id)};val ref=container.chat.activeRef()
            thinking.value=if(ref.providerId==ProviderIds.DEEPSEEK) ReasoningPreference.Off else ReasoningPreference.Auto
            replace(messages=messages,historyRef=ref,providerId=ref.providerId,models=cache.value[ref.providerId] ?: emptyList(),
                selected=ref.takeIf {cache.value[it.providerId]?.any {m ->m.id==it.modelId}==true},ready=localReady(ref.providerId))
            compareRun.value=null;refreshHistory()
        } catch(_:Exception) {replace(error="LOCAL_STORAGE_UNAVAILABLE")} finally {replace(busy=false,error=screen.value.error)} }
    }
    fun startCompare(prompt:String,a:ModelRef,b:ModelRef,pa:ReasoningPreference,pb:ReasoningPreference) {
        if(screen.value.busy || a==b || prompt.isBlank()) return
        if(listOf(a,b).any {ref ->cache.value[ref.providerId]?.none {it.id==ref.modelId}!=false}) return
        if(!ReasoningPolicy.supported(a,pa) || !ReasoningPolicy.supported(b,pb)) return
        fun output(id:String,ref:ModelRef,p:ReasoningPreference)=CompareLaneRecord(id,ref,preference=p,
            modelDisplayName=cache.value[ref.providerId]?.firstOrNull {it.id==ref.modelId}?.displayName)
        val draft=CompareRun(MessageIds.create(),prompt,output("A",a,pa),output("B",b,pb))
        replace(busy=true);compareRun.value=draft
        compareJob=viewModelScope.launch {
            try {CompareExecutor(container.providers,container.compare,usage=container.usage).execute(draft) {compareRun.value=it}}
            catch(cancel:CancellationException) {throw cancel}
            catch(_:Exception) {replace(error="LOCAL_STORAGE_UNAVAILABLE")}
            finally {withContext(NonCancellable) {runCatching {refreshHistory()};replace(busy=false,error=screen.value.error)}}
        }
    }
    fun openCompare(id:String) {if(!screen.value.busy) compareRun.value=compareHistory.value.firstOrNull {it.id==id}}
    fun deleteHistory(id:String,compare:Boolean) {
        if(screen.value.busy) return;replace(busy=true)
        clearSharing()
        viewModelScope.launch {try {
            withContext(Dispatchers.IO) {if(compare) container.compare.delete(id) else container.chat.deleteSession(id)}
            if(compare && compareRun.value?.id==id) compareRun.value=null
            if(!compare) {val messages=withContext(Dispatchers.IO) {container.chat.load()};val ref=container.chat.activeRef()
                if(ref.providerId!=screen.value.providerId) thinking.value=if(ref.providerId==ProviderIds.DEEPSEEK) ReasoningPreference.Off else ReasoningPreference.Auto
                replace(messages=messages,historyRef=ref,providerId=ref.providerId,ready=localReady(ref.providerId));syncCatalog(catalogLoader.state.value)}
            refreshHistory()
        } catch(_:Exception) {replace(error="LOCAL_STORAGE_UNAVAILABLE")} finally {replace(busy=false,error=screen.value.error)} }
    }
    fun moveHistory(id:String,compare:Boolean,direction:Int) {
        if(screen.value.busy) return;replace(busy=true)
        viewModelScope.launch {try {
            withContext(Dispatchers.IO) {if(compare) container.compare.move(id,direction) else {
                val mode=container.chat.sessions().single {it.id==id}.mode
                container.chat.moveSession(id,direction,mode)
            }}
            refreshHistory()
        } catch(_:Exception) {replace(error="LOCAL_STORAGE_UNAVAILABLE")} finally {replace(busy=false,error=screen.value.error)} }
    }
    fun clearCompareDraft() {if(!screen.value.busy) compareRun.value=null}
    fun cancel() { debateWorkflow.cancel();collaborateJob?.cancel();compareJob?.cancel();chatJob?.cancel(); authJob?.cancel(); loadJob?.cancel() }
    fun foregroundStopped() {clearSharing();debateWorkflow.cancel();collaborateJob?.cancel();compareJob?.cancel();chatJob?.cancel() } // Browser authorization intentionally survives the browser handoff.
    fun disconnect() {
        if(screen.value.busy || screen.value.providerId!=ProviderIds.CHATGPT) return
        replace(busy=true)
        viewModelScope.launch { try {catalogLoader.invalidate(ProviderIds.CHATGPT);container.tokens.clearLocalConnection();container.provider.invalidateCatalog(); replace(models=emptyList(),selected=null,ready=false) }
            catch(_:Exception) { replace(error="LOCAL_STORAGE_UNAVAILABLE") }
            finally { replace(busy=false,error=screen.value.error) } }
    }
}
