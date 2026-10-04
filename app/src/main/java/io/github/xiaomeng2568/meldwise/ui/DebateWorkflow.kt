// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** ViewModel-owned intent coordinator. Uses only the frozen repository/executor APIs.
 * Incomplete forms are ephemeral; a valid configuration is committed before Send is enabled.
 * Testable without Android auth, startup catalog refresh, or a real provider transport.
 */
internal class DebateWorkflow(private val scope:CoroutineScope,private val repository:ChatRepository,
    private val registry:ProviderRegistry,private val catalogs:()->Map<String,List<LlmModel>>,
    private val isBusy:()->Boolean,private val onBusy:(Boolean)->Unit,private val onError:(String)->Unit,
    private val onConversation:(Conversation?)->Unit,private val onSettled:suspend ()->Unit,
    private val io:CoroutineDispatcher=Dispatchers.IO,
    private val executor:()->DebateExecutor={DebateExecutor(registry,repository)}) {
    private val mutable=MutableStateFlow(DebateUiState())
    val state=mutable.asStateFlow()
    private var pending:PreparedDebate?=null
    private var job:Job?=null
    private var readinessJob:Job?=null

    fun restore(config:DebateConfig?) {
        dismissSharing()
        mutable.value=DebateUiState(DebateSelection.from(config),config)
        refreshAvailability()
    }
    fun refreshAvailability() {
        readinessJob?.cancel()
        val selection=state.value.selection
        readinessJob=scope.launch {
            val unavailable=withContext(io) {unavailable(selection)}
            if(state.value.selection==selection) mutable.value=state.value.copy(unavailable=unavailable)
        }
    }
    private suspend fun unavailable(selection:DebateSelection):Set<DebateRole> {
        val providerReady=mutableMapOf<String,Boolean>()
        return DebateRole.entries.filter {role ->
            val m=selection.model(role)
            if(m==null) true else {
                val listed=catalogs()[m.ref.providerId]?.firstOrNull {it.id==m.ref.modelId}
                val ready=providerReady[m.ref.providerId] ?: try {registry.ready(m.ref)}
                    catch(c:CancellationException) {throw c} catch(_:Exception) {false}
                providerReady[m.ref.providerId]=ready
                !ready || listed==null || listed.availability !in setOf(ModelAvailability.AVAILABLE,ModelAvailability.UNKNOWN)
            }
        }.toSet()
    }
    private fun operation(block:suspend ()->Unit) {
        if(isBusy() || job?.isActive==true) return
        dismissSharing();onBusy(true)
        job=scope.launch {
            try {block()}
            catch(c:CancellationException) {throw c}
            catch(f:ProviderFailure) {onError(f.error.kind.name)}
            catch(_:Exception) {onError("LOCAL_STORAGE_UNAVAILABLE")}
            finally {withContext(NonCancellable) {
                runCatching {onSettled()}.onFailure {onError("LOCAL_STORAGE_UNAVAILABLE")}
                onBusy(false)
                refreshAvailability()
            }}
        }
    }
    fun newDebate()=operation {
        withContext(io) {repository.newDebate()}
        restore(null);onConversation(null)
    }
    fun choose(role:DebateRole,ref:ModelRef) {
        if(isBusy() || job?.isActive==true || repository.mode()!=ConversationMode.Debate) return
        val item=catalogs()[ref.providerId]?.firstOrNull {it.id==ref.modelId} ?: return
        change(role,DebateModel(ref,item.displayName,if(ref.providerId==ProviderIds.DEEPSEEK) ReasoningPreference.Off else ReasoningPreference.Auto))
    }
    fun thinking(role:DebateRole,value:ReasoningPreference) {
        if(isBusy() || job?.isActive==true || repository.mode()!=ConversationMode.Debate) return
        val model=state.value.selection.model(role) ?: return
        if(ReasoningPolicy.supported(model.ref,value)) change(role,model.copy(preference=value))
    }
    private fun change(role:DebateRole,model:DebateModel) {
        dismissSharing()
        val selection=state.value.selection.with(role,model)
        mutable.value=state.value.copy(selection=selection,unavailable=DebateRole.entries.toSet())
        val config=selection.config()
        if(config==null) {refreshAvailability();return}
        operation {
            withContext(io) {repository.configureDebate(config)}
            mutable.value=state.value.copy(configured=config,unavailable=withContext(io) {unavailable(selection)})
        }
    }
    fun send(text:String) {
        if(text.isBlank() || !state.value.sendReady) return
        prepare {repository.prepareDebate(text)}
    }
    fun retry(roundId:String)=prepare {repository.prepareDebateRetry(roundId)}
    private fun prepare(build:()->PreparedDebate)=operation {
        val plan=withContext(io) {build()}
        // Catalog availability and local credentials, never provider HTTP, before confirmation/admission.
        if(withContext(io) {unavailable(DebateSelection.from(plan.config))}.isNotEmpty()) {
            onError("MODEL_UNAVAILABLE");return@operation
        }
        if(plan.requiresSharing) {
            pending=plan;mutable.value=state.value.copy(sharingProviders=debateSharingProviders(plan))
        } else execute(plan,false)
    }
    fun continueSharing() {
        val plan=pending ?: return
        operation {execute(plan,true)} // clears pending synchronously: duplicate Continue cannot execute twice.
    }
    private suspend fun execute(plan:PreparedDebate,allowSharing:Boolean) {
        // Confirmation may remain open while cached availability/credentials change locally.
        if(withContext(io) {unavailable(DebateSelection.from(plan.config))}.isNotEmpty()) {
            onError("MODEL_UNAVAILABLE");return
        }
        executor().execute(plan,allowSharing,onConversation)
    }
    fun dismissSharing() {pending=null;mutable.value=state.value.copy(sharingProviders=null)}
    fun cancel() {dismissSharing();job?.cancel()}
}
