// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import java.util.Collections

/** Scripted local transport: queued events, EOF/throw, rendezvous gates and observable cleanup.
 * No account, HTTP, real credential, timing sleep, or automatically advancing provider work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DebateHarness(val scope:TestScope,val config:DebateConfig=DebateFixtures.config,
    val blob:AtomicBlob=MemoryBlob(),val box:AesGcmBox=testBox()) {
    val repository=ChatRepository(blob,box,clock={scope.testScheduler.currentTime}).also {it.newDebate();it.configureDebate(config)}
    data class Call(val type:DebateStageType,val request:LlmRequest,val provider:String,
        val events:Channel<Any> = Channel(Channel.UNLIMITED),val closed:CompletableDeferred<Unit> = CompletableDeferred()) {
        fun emit(vararg values:LlmEvent) {values.forEach {check(events.trySend(it).isSuccess)}}
        fun complete(text:String="visible-$type") {emit(LlmEvent.TextDelta(text),LlmEvent.Completed(null))}
        fun fail(kind:ErrorKind) {emit(LlmEvent.Failed(LlmError(kind)))}
        fun eof() {events.close()}
        fun throwing(f:Exception) {check(events.trySend(f).isSuccess)}
    }
    val calls=Collections.synchronizedList(mutableListOf<Call>())
    var automatic:((Call)->Unit)?=null
    var readiness:((ModelRef,Int)->Boolean)?=null
    private val readyCounts=mutableMapOf<String,Int>()
    private inner class Fake(override val id:String):LlmProvider {
        override val displayName="synthetic"
        override val capabilities=ProviderCapability(emptySet())
        override suspend fun listModels():List<LlmModel> = error("NO_CATALOG_CALL")
        override suspend fun validateConnection():ProviderStatus {
            val count=readyCounts.getOrDefault(id,0)+1;readyCounts[id]=count
            val ref=listOf(config.modelA,config.modelB,config.judge).first {it.ref.providerId==id}.ref
            return if(readiness?.invoke(ref,count)!=false) ProviderStatus.READY else ProviderStatus.DISCONNECTED
        }
        override fun streamResponse(request:LlmRequest)=flow {
            val type=when {
                request.messages.any {it.text==DebateInstructions.JUDGE}->DebateStageType.JUDGE
                request.messages.any {it.text==DebateInstructions.REVIEW_A_OF_B}->DebateStageType.REVIEW_A_OF_B
                request.messages.any {it.text==DebateInstructions.REVIEW_B_OF_A}->DebateStageType.REVIEW_B_OF_A
                request.model==config.modelA.ref.modelId && id==config.modelA.ref.providerId->DebateStageType.INITIAL_A
                else->DebateStageType.INITIAL_B
            }
            val call=Call(type,request,id);calls.add(call)
            try {
                automatic?.invoke(call)
                for(value in call.events) when(value) {is LlmEvent->emit(value);is Exception->throw value}
            } finally {call.closed.complete(Unit)}
        }
    }
    val registry=ProviderRegistry(listOf(Fake("chatgpt"),Fake("deepseek")))
    fun executor()=DebateExecutor(registry,repository,monotonic={scope.testScheduler.currentTime},
        wallClock={scope.testScheduler.currentTime},persistenceDispatcher=StandardTestDispatcher(scope.testScheduler))
    fun start(allow:Boolean=true,onUpdate:(Conversation)->Unit={}):Deferred<Conversation> = scope.async {
        executor().execute(repository.prepareDebate("CURRENT_QUESTION"),allow,onUpdate)
    }
    fun call(type:DebateStageType)=calls.last {it.type==type}
    fun round()=repository.activeConversation()!!.debateRounds.last()
    fun finishWave(types:List<DebateStageType>) {types.forEach {call(it).complete()};scope.runCurrent()}
    fun toReviews() {scope.runCurrent();finishWave(DebateStageType.entries.take(2))}
    fun toJudge() {toReviews();finishWave(DebateStageType.entries.slice(2..3))}
    fun finishAll() {toJudge();call(DebateStageType.JUDGE).complete();scope.runCurrent()}
    fun readyChecks()=readyCounts.values.sum()
}
