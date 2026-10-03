package io.github.xiaomeng2568.meldwise.data

import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

enum class CatalogNoticeKind { Success, Warning, Failure }
/** Closed-schema UI event. Error details are constructed from fixed enums/status, never exceptions. */
data class CatalogNotice(val id:Long,val providerId:String,val kind:CatalogNoticeKind,val error:ErrorKind?=null,
    val diagnostic:ModelCatalogDiagnostic?=null) {
    init {require(providerId in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK))}
}
data class CatalogUiState(val catalogs:Map<String,List<LlmModel>> = emptyMap(),val loading:Set<String> = emptySet(),
    val diagnostics:Map<String,ModelCatalogDiagnostic> = emptyMap(),val notices:List<CatalogNotice> = emptyList()) {
    override fun toString()="CatalogUiState(loading=$loading, catalogCount=${catalogs.size})"
}
/** Cache first, one bounded refresh per configured provider when empty/stale. No retry or inference. */
class ModelCatalogLoader(private val registry:ProviderRegistry,private val cache:ModelCatalogCache,
    private val diagnostic:(String)->ModelCatalogDiagnostic?={null},
    private val hydrate:(String,List<LlmModel>)->Unit={_,_->},private val now:()->Long=System::currentTimeMillis) {
    private val mutex=Mutex();private val startup=AtomicBoolean(false);private var nextNotice=0L
    private val mutable=MutableStateFlow(CatalogUiState())
    val state:StateFlow<CatalogUiState> = mutable.asStateFlow()
    @Volatile private var snapshots=emptyMap<String,CatalogSnapshot>()
    private var epochs=emptyMap<String,Long>()
    suspend fun restore() {
        try {
            val saved=withContext(Dispatchers.IO) {cache.load()}
            saved.forEach {(id,snapshot)->hydrate(id,snapshot.models)}
            mutex.withLock {snapshots=saved;mutable.value=mutable.value.copy(catalogs=saved.mapValues {it.value.models})}
        } catch(cancel:CancellationException) {throw cancel}
        catch(_:Exception) {withContext(Dispatchers.IO) {runCatching {cache.clear()}};notify(ProviderIds.CHATGPT,CatalogNoticeKind.Warning,ErrorKind.STORAGE)}
    }
    suspend fun refreshStaleOnce() {
        if(!startup.compareAndSet(false,true)) return
        supervisorScope {registry.all.map {provider ->launch {
            try {
                if(withContext(Dispatchers.IO) {provider.validateConnection()}==ProviderStatus.READY && snapshots[provider.id]?.needsRefresh(now())!=false) load(provider.id)
            } catch(cancel:CancellationException) {throw cancel}
            catch(_:Exception) {notify(provider.id,CatalogNoticeKind.Warning,ErrorKind.STORAGE)}
        }}.joinAll()}
    }
    suspend fun load(providerId:String) {
        val epoch=mutex.withLock {
            if(providerId in mutable.value.loading) null else {mutable.value=mutable.value.copy(loading=mutable.value.loading+providerId);epochs[providerId] ?: 0L}
        }
        if(epoch==null) return
        var requestAttempted=false
        try {
            val provider=registry.get(providerId)
            if(withContext(Dispatchers.IO) {provider.validateConnection()}!=ProviderStatus.READY) throw ProviderFailure(LlmError(ErrorKind.AUTHENTICATION))
            requestAttempted=true
            val models=provider.listModels()
            try {validateCachedModels(models)} catch(_:IllegalArgumentException) {throw ProviderFailure(LlmError(ErrorKind.PROTOCOL))}
            val stamp=now()
            val committed=mutex.withLock {
                if((epochs[providerId] ?: 0L)!=epoch) {
                    hydrate(providerId,mutable.value.catalogs[providerId] ?: emptyList());return@withLock false
                }
                withContext(Dispatchers.IO) {cache.save(providerId,models,stamp)}
                snapshots=snapshots+(providerId to CatalogSnapshot(providerId,stamp,models))
                mutable.value=mutable.value.copy(catalogs=mutable.value.catalogs+(providerId to models))
                true
            }
            if(committed) notify(providerId,CatalogNoticeKind.Success,expectedEpoch=epoch)
        } catch(cancel:CancellationException) {throw cancel}
        catch(f:ProviderFailure) {notify(providerId,CatalogNoticeKind.Failure,f.error.kind,if(requestAttempted) diagnostic(providerId) else null,epoch)}
        catch(_:Exception) {notify(providerId,CatalogNoticeKind.Failure,ErrorKind.STORAGE,if(requestAttempted) diagnostic(providerId) else null,epoch)}
        finally {withContext(NonCancellable) {mutex.withLock {
            // The provider admission list must match the usable UI cache, including failed writes
            // and late responses from a replaced credential. Never admit an uncommitted catalog.
            hydrate(providerId,mutable.value.catalogs[providerId] ?: emptyList())
            mutable.value=mutable.value.copy(loading=mutable.value.loading-providerId)
        }}}
    }
    suspend fun invalidate(providerId:String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {cache.remove(providerId)}
            epochs=epochs+(providerId to ((epochs[providerId] ?: 0L)+1))
            snapshots=snapshots-providerId;mutable.value=mutable.value.copy(catalogs=mutable.value.catalogs-providerId,
                diagnostics=mutable.value.diagnostics-providerId,notices=mutable.value.notices.filterNot {it.providerId==providerId})
            hydrate(providerId,emptyList())
        }
    }
    private suspend fun notify(id:String,kind:CatalogNoticeKind,error:ErrorKind?=null,detail:ModelCatalogDiagnostic?=diagnostic(id),expectedEpoch:Long?=null)=mutex.withLock {
        if(expectedEpoch!=null && (epochs[id] ?: 0L)!=expectedEpoch) return@withLock
        val notice=CatalogNotice(++nextNotice,id,kind,error,detail)
        mutable.value=mutable.value.copy(diagnostics=if(detail!=null) mutable.value.diagnostics+(id to detail) else mutable.value.diagnostics,
            notices=(mutable.value.notices+notice).takeLast(4))
    }
    suspend fun dismiss(id:Long)=mutex.withLock {mutable.value=mutable.value.copy(notices=mutable.value.notices.filterNot {notice->notice.id==id})}
}
