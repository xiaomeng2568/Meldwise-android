// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.data

import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal data class DebateStageChange(val expected:DebateStage,val next:DebateStage)

/** One admitted attempt, three fixed waves, no resume/fallback/retry loop.
 * All stream collectors are children of execute. A failed worker returns its
 * terminal proposal; the coordinator cancels AND joins sibling transports before atomic settlement.
 * Successful terminals are forced to disk before a wave can advance. Cancellation never revokes
 * an already accepted durable terminal. Readiness is local for both current production providers.
 */
internal class DebateExecutor(private val registry:ProviderRegistry,private val repository:ChatRepository,
    private val inputBuilder:DebateInputBuilder=DebateInputBuilder(),
    private val monotonic:()->Long={System.nanoTime()/1_000_000},
    private val wallClock:()->Long=System::currentTimeMillis,
    private val persistenceDispatcher:CoroutineDispatcher=Dispatchers.IO) {
    private class StageFinished:RuntimeException()
    private class StaleWrite:RuntimeException()
    private data class Result(val change:DebateStageChange?=null,val storage:Boolean=false)
    private val waves=listOf(listOf(DebateStageType.INITIAL_A,DebateStageType.INITIAL_B),
        listOf(DebateStageType.REVIEW_A_OF_B,DebateStageType.REVIEW_B_OF_A),listOf(DebateStageType.JUDGE))

    suspend fun execute(plan:PreparedDebate,allowSharing:Boolean=false,onUpdate:(Conversation)->Unit={}):Conversation = coroutineScope {
        ensureActive()
        repository.validateDebateAdmission(plan,allowSharing)
        // No token refresh, model fetch, or POST in this preflight. Rechecked just before dispatch.
        listOf(plan.config.modelA,plan.config.modelB,plan.config.judge).map {it.ref}.distinct().forEach {ready(it)}
        ensureActive()
        val admitted=durable {repository.beginDebateExecution(plan,allowSharing)}
        val cid=admitted.conversationId;val rid=admitted.debateRounds.last().roundId
        val notifications=Mutex()
        suspend fun notifyUpdate(@Suppress("UNUSED_PARAMETER") c:Conversation) {
            // Concurrent writers must not deliver a stale whole-round observer snapshot out of order.
            notifications.withLock {onUpdate(repository.debateSnapshot(cid,rid))}
        }
        try {
            notifyUpdate(admitted)
            for(types in waves) {
                ensureActive()
                val before=repository.debateSnapshot(cid,rid).debateRounds.last()
                if(!before.lifecycle.active) break
                val started=try {durable {repository.startDebateWave(cid,rid,types)}}
                    catch(_:Exception) {throw ProviderFailure(LlmError(ErrorKind.STORAGE))}
                ensureActive();notifyUpdate(started)
                runWave(cid,rid,types,::notifyUpdate)
            }
            repository.debateSnapshot(cid,rid)
        } catch(cancel:CancellationException) {
            // runWave's coroutineScope has already joined every collector and its HTTP awaitClose.
            withContext(NonCancellable) {
                val settled=try {durable {repository.cancelDebateExecution(cid,rid)}}
                    catch(_:Exception) {throw ProviderFailure(LlmError(ErrorKind.STORAGE))}
                notifyUpdate(settled)
            }
            throw cancel
        } catch(_:StaleWrite) {
            // A competing local terminal is preserved. A competing Running writer stops this
            // attempt as Interrupted rather than returning a runnable orphan or replaying a wave.
            try {durable {repository.interruptDebateExecution(cid,rid)}}
                catch(_:Exception) {throw ProviderFailure(LlmError(ErrorKind.STORAGE))}
        }
    }

    private suspend fun ready(ref:ModelRef) {
        val available=try {registry.ready(ref)} catch(c:CancellationException) {throw c}
            catch(_:Exception) {false}
        if(!available) throw ProviderFailure(LlmError(ErrorKind.AUTHENTICATION))
    }
    private suspend fun <T> durable(block:()->T):T=withContext(NonCancellable) {
        withContext(persistenceDispatcher) {block()}
    }

    private suspend fun runWave(cid:String,rid:String,types:List<DebateStageType>,onUpdate:suspend (Conversation)->Unit)=coroutineScope {
        val results=ConcurrentHashMap<DebateStageType,Result>()
        val finished=Channel<DebateStageType>(Channel.UNLIMITED)
        val jobs=types.map {type ->launch {
            val result=runStage(cid,rid,type,onUpdate)
            results[type]=result;finished.send(type)
        }}
        try {
            repeat(types.size) {
                val result=results.getValue(finished.receive())
                if(result.change!=null || result.storage) {
                    // NonCancellable owns cleanup, but the requests themselves retain cancellable jobs.
                    withContext(NonCancellable) {jobs.forEach {it.cancel()};jobs.joinAll()}
                    val changes=types.mapNotNull {results[it]?.change}
                    if(changes.isNotEmpty()) {
                        val settled=try {durable {repository.commitDebateStages(cid,rid,changes)}}
                            catch(_:Exception) {throw ProviderFailure(LlmError(ErrorKind.STORAGE))}
                        if(settled==null) throw StaleWrite()
                        onUpdate(settled)
                    }
                    if(results.values.any {it.storage}) throw ProviderFailure(LlmError(ErrorKind.STORAGE))
                    return@coroutineScope
                }
            }
        } finally {
            withContext(NonCancellable) {jobs.forEach {it.cancel()};jobs.joinAll();finished.close()}
        }
    }

    private suspend fun runStage(cid:String,rid:String,type:DebateStageType,onUpdate:suspend (Conversation)->Unit):Result=coroutineScope {
        val round=repository.debateSnapshot(cid,rid).debateRounds.last()
        var saved=round.stage(type);var stage=saved
        val mutex=Mutex();var lastSaved=monotonic();var httpAt:Long?=null;var firstTextAt:Long?=null
        var result:Result?=null
        fun duration()=httpAt?.let {((firstTextAt ?: monotonic())-it).coerceIn(0,604800000).div(1000)}
        fun end(state:DebateStageState,error:ErrorKind?=null)=stage.copy(state=state,error=error,
            endedAt=maxOf(wallClock(),stage.startedAt ?: 0),processingDuration=duration(),
            reasoning=if(state==DebateStageState.Complete) ReasoningRecord(stage.reasoning.text,stage.reasoning.kind,
                if(stage.reasoning.text.isEmpty()) ReasoningPhase.Unavailable else ReasoningPhase.Completed)
                else interruptedDebateReasoning(stage.reasoning))
        suspend fun persist(next:DebateStage,terminal:Boolean=false) {
            val c=try {
                if(terminal) durable {repository.commitDebateStages(cid,rid,listOf(DebateStageChange(saved,next)))}
                else withContext(persistenceDispatcher) {repository.commitDebateStages(cid,rid,listOf(DebateStageChange(saved,next)))}
            } catch(c:CancellationException) {throw c}
            catch(_:Exception) {
                // Recover the exact durable CAS token if IO committed just before cancellation/throw.
                saved=repository.debateSnapshot(cid,rid).debateRounds.last().stage(type);stage=saved
                throw ProviderFailure(LlmError(ErrorKind.STORAGE))
            }
            if(c==null) throw StaleWrite()
            saved=c.debateRounds.last().stage(type);stage=next;lastSaved=monotonic();onUpdate(c)
        }
        try {
            ensureActive()
            val input=inputBuilder.build(round,type)
            ready(stage.model.ref);ensureActive()
            registry.get(stage.model.ref.providerId).streamResponse(LlmRequest(stage.model.ref.modelId,input,
                reasoning=stage.model.preference,observeHttp=true)).collect {event ->
                ensureActive()
                mutex.withLock {
                    if(result!=null) throw StageFinished()
                    when(event) {
                        LlmEvent.HttpReady->if(httpAt==null) httpAt=monotonic()
                        is LlmEvent.TextDelta->{
                            if(event.text.length>524288-stage.output.length) throw ProviderFailure(LlmError(ErrorKind.PROTOCOL))
                            if(event.text.isNotEmpty() && firstTextAt==null) firstTextAt=monotonic()
                            stage=stage.copy(output=stage.output+event.text,processingDuration=duration())
                        }
                        is LlmEvent.ReasoningDelta->{
                            if(stage.model.ref.providerId!=ProviderIds.DEEPSEEK && event.kind!=ReasoningContent.Summary ||
                                stage.reasoning.text.isNotEmpty() && stage.reasoning.kind!=event.kind ||
                                event.text.length>ReasoningReader.MAX_CHARS-stage.reasoning.text.length)
                                throw ProviderFailure(LlmError(ErrorKind.PROTOCOL))
                            stage=stage.copy(reasoning=ReasoningRecord(stage.reasoning.text+event.text,event.kind,ReasoningPhase.Streaming))
                        }
                        is LlmEvent.ReasoningDone->{
                            if(stage.model.ref.providerId!=ProviderIds.DEEPSEEK && event.kind!=ReasoningContent.Summary ||
                                stage.reasoning.text.isNotEmpty() && event.kind!=stage.reasoning.kind)
                                throw ProviderFailure(LlmError(ErrorKind.PROTOCOL))
                            stage=stage.copy(reasoning=ReasoningRecord(stage.reasoning.text,event.kind,ReasoningPhase.Completed))
                        }
                        is LlmEvent.Completed->{
                            if(stage.output.isBlank()) result=Result(DebateStageChange(saved,end(DebateStageState.Failed,ErrorKind.PROTOCOL)))
                            else {persist(end(DebateStageState.Complete),true);result=Result()}
                        }
                        is LlmEvent.Failed->result=Result(DebateStageChange(saved,end(DebateStageState.Failed,event.error.kind)))
                        is LlmEvent.Incomplete->result=Result(DebateStageChange(saved,end(
                            if(event.error.kind==ErrorKind.STREAM_INTERRUPTED) DebateStageState.Interrupted else DebateStageState.Failed,event.error.kind)))
                        LlmEvent.Cancelled->result=Result(DebateStageChange(saved,end(DebateStageState.Cancelled,ErrorKind.CANCELLED)))
                        else->Unit // Usage, identifiers and diagnostic metadata are never conversation text.
                    }
                    if(result!=null) throw StageFinished()
                    if(monotonic()-lastSaved>=200) persist(stage)
                }
            }
            mutex.withLock {if(result==null) result=Result(DebateStageChange(saved,end(DebateStageState.Interrupted,ErrorKind.STREAM_INTERRUPTED)))}
        } catch(_:StageFinished) { /* First validated terminal stops collection, including buffered late events. */ }
        catch(c:CancellationException) {
            // Only flush local visible partials. Terminal cancellation belongs to the joined wave coordinator.
            withContext(NonCancellable) {mutex.withLock {
                if(result==null && stage.state==DebateStageState.Running) try {
                    saved=repository.debateSnapshot(cid,rid).debateRounds.last().stage(type)
                    if(saved.state==DebateStageState.Running) persist(stage)
                } catch(_:Exception) { /* Last durable partial remains authoritative. */ }
            }}
            throw c
        } catch(_:StaleWrite) {throw StaleWrite()}
        catch(f:Exception) {
            mutex.withLock {result=Result(DebateStageChange(saved,end(
                if(f is ProviderFailure && f.error.kind==ErrorKind.STREAM_INTERRUPTED) DebateStageState.Interrupted else DebateStageState.Failed,
                if(f is ProviderFailure) f.error.kind else ErrorKind.UNKNOWN)),storage=f is ProviderFailure && f.error.kind==ErrorKind.STORAGE)}
        }
        requireNotNull(result)
    }
}
