package io.github.xiaomeng2568.meldwise.data

import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.util.concurrent.atomic.AtomicReference

@Serializable enum class CompareLaneState { Pending, Waiting, Thinking, Streaming, Completed, Cancelled, Incomplete, Failed;
    val active get()=this in setOf(Pending,Waiting,Thinking,Streaming)
}
@Serializable enum class CompareRunState { Pending, Running, Completed, Partial, Cancelled, Failed }
@Serializable enum class CompareOutputRole { Candidate, Summary }
@Serializable data class ComparePromptItem(val id:String,val content:String) {
    override fun toString()="ComparePromptItem(content=[REDACTED])"
}
/** Ordered grouping only. This does not schedule stages or synthesize a summary. */
@Serializable data class CompareStage(val id:String,val order:Int,val outputIds:List<String>)
@Serializable data class CompareLaneRecord(val laneId:String,val modelRef:ModelRef,
    val output:String="",val state:CompareLaneState=CompareLaneState.Pending,val error:ErrorKind?=null,
    val reasoning:ReasoningRecord=ReasoningRecord(),val preference:ReasoningPreference=ReasoningPreference.Auto,
    val processingDuration:Long?=null,
    val providerDisplayName:String=when(modelRef.providerId) {ProviderIds.CHATGPT->"ChatGPT";ProviderIds.DEEPSEEK->"DeepSeek";else->"未知提供方"},
    val modelDisplayName:String?=null,val role:CompareOutputRole=CompareOutputRole.Candidate) {
    val reasoningAvailable get()=reasoning.text.isNotBlank() && reasoning.phase!=ReasoningPhase.Unavailable
    override fun toString()="CompareLaneRecord(state=$state, content=[REDACTED])"
}
/** One prompt with an ordered set of assistant outputs: the single source of persisted content. */
@Serializable data class CompareRun(val id:String,val userPrompt:ComparePromptItem,val outputs:List<CompareLaneRecord>,
    val lifecycle:CompareRunState=CompareRunState.Pending,val createdAt:Long=System.currentTimeMillis(),
    val stages:List<CompareStage> = listOf(CompareStage("parallel",0,outputs.map {it.laneId}))) {
    constructor(id:String,prompt:String,laneA:CompareLaneRecord,laneB:CompareLaneRecord,
        lifecycle:CompareRunState=CompareRunState.Pending,createdAt:Long=System.currentTimeMillis()):
        this(id,ComparePromptItem("prompt",prompt),listOf(laneA,laneB),lifecycle,createdAt)
    val prompt get()=userPrompt.content
    // Two-lane MVP accessors are execution conveniences, never a second persisted copy.
    val laneA get()=outputs.single {it.laneId=="A"}
    val laneB get()=outputs.single {it.laneId=="B"}
    fun withLanes(laneA:CompareLaneRecord=this.laneA,laneB:CompareLaneRecord=this.laneB,
        lifecycle:CompareRunState=this.lifecycle)=copy(outputs=outputs.map {when(it.laneId) {"A"->laneA;"B"->laneB;else->it}},lifecycle=lifecycle)
    val orderedOutputs get()=stages.sortedBy {it.order}.flatMap {stage ->stage.outputIds.map {id ->outputs.single {it.laneId==id}}}
    override fun toString()="CompareRun(lifecycle=$lifecycle, content=[REDACTED])"
}
fun compareLifecycle(a:CompareLaneState,b:CompareLaneState)=compareLifecycle(listOf(a,b))
fun compareLifecycle(states:List<CompareLaneState>):CompareRunState = when {
    states.any {it.active}->CompareRunState.Running
    states.isNotEmpty() && states.all {it==CompareLaneState.Completed}->CompareRunState.Completed
    states.isNotEmpty() && states.all {it==CompareLaneState.Cancelled}->CompareRunState.Cancelled
    states.isNotEmpty() && states.all {it==CompareLaneState.Failed}->CompareRunState.Failed
    else->CompareRunState.Partial
}
fun restoreCompare(run:CompareRun):CompareRun {
    fun lane(l:CompareLaneRecord)=l.copy(state=if(l.state.active) CompareLaneState.Incomplete else l.state,
        reasoning=if(l.reasoning.phase in setOf(ReasoningPhase.Waiting,ReasoningPhase.Streaming))
            ReasoningRecord(l.reasoning.text,l.reasoning.kind,ReasoningPhase.Interrupted) else l.reasoning)
    val outputs=run.outputs.map(::lane)
    return run.copy(outputs=outputs,lifecycle=compareLifecycle(outputs.map {it.state}))
}
@Serializable private data class CompareJournal(val version:Int=2,val runs:List<CompareRun> = emptyList())
@Serializable private data class LegacyCompareRun(val id:String,val prompt:String,val laneA:CompareLaneRecord,val laneB:CompareLaneRecord,
    val lifecycle:CompareRunState=CompareRunState.Pending,val createdAt:Long)
@Serializable private data class LegacyCompareJournal(val version:Int,val runs:List<LegacyCompareRun>)
/** Separate authenticated, atomic journal. Restore has no provider dependency and cannot send. */
class CompareRepository(private val blob:AtomicBlob,private val box:AesGcmBox) {
    private val json=Json {encodeDefaults=true}
    private var loaded=false;private var records=emptyList<CompareRun>()
    @Synchronized fun load():List<CompareRun> {
        if(!loaded) {
            var migrated=false
            val original=blob.read()?.let { val plain=box.open(it)
                try {
                    val text=utf8(plain)
                    when(json.parseToJsonElement(text).jsonObject.getValue("version").jsonPrimitive.int) {
                        1->{migrated=true;json.decodeFromString<LegacyCompareJournal>(text).runs.map {
                            require(it.laneA.laneId=="A" && it.laneB.laneId=="B")
                            CompareRun(it.id,it.prompt,it.laneA,it.laneB,it.lifecycle,it.createdAt)
                        }}
                        2->json.decodeFromString<CompareJournal>(text).runs
                        else->throw IllegalArgumentException("Unsupported compare journal")
                    }
                }
                finally {plain.fill(0)} } ?: emptyList()
            validate(original)
            val restored=original.map(::restoreCompare)
            if(migrated || original!=restored) write(restored) else records=restored
            loaded=true
        }
        return records.toList()
    }
    @Synchronized fun upsert(run:CompareRun) {load();write(if(records.any {it.id==run.id}) records.map {if(it.id==run.id) run else it} else records+run)}
    @Synchronized fun delete(id:String) {load();require(records.any {it.id==id});write(records.filterNot {it.id==id})}
    @Synchronized fun move(id:String,direction:Int) {
        load();val byId=records.associateBy {it.id};val order=movedHistory(records.reversed().map {it.id},id,direction)
        write(order.reversed().map {byId.getValue(it)})
    }
    private fun validate(value:List<CompareRun>) {
        require(value.size<=16 && value.map {it.id}.distinct().size==value.size)
        value.forEach {run ->
            require(run.id.length in 1..128 && run.prompt.isNotBlank() && run.prompt.length<=32768)
            require(run.userPrompt.id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            require(run.outputs.size in 2..8 && run.outputs.map {it.laneId}.distinct().size==run.outputs.size)
            require(run.stages.size in 1..4 && run.stages.map {it.id}.distinct().size==run.stages.size &&
                run.stages.map {it.order}.sorted()==run.stages.indices.toList())
            val orderedIds=run.stages.sortedBy {it.order}.flatMap {it.outputIds}
            require(orderedIds.size==run.outputs.size && orderedIds.toSet()==run.outputs.map {it.laneId}.toSet())
            require(run.stages.all {it.id.matches(Regex("[A-Za-z0-9_-]{1,128}")) && it.outputIds.isNotEmpty()})
            require(run.outputs.count {it.role==CompareOutputRole.Summary}<=1)
            require(run.outputs.none {it.role==CompareOutputRole.Summary} || run.orderedOutputs.last().role==CompareOutputRole.Summary)
            run.stages.forEach {stage ->val refs=stage.outputIds.map {id ->run.outputs.single {it.laneId==id}.modelRef};require(refs.distinct().size==refs.size)}
            run.outputs.forEach {l ->
                require(l.laneId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                require(l.modelRef.providerId in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK))
                require(l.modelRef.modelId.matches(Regex("[A-Za-z0-9._:/-]{1,128}")))
                require(l.providerDisplayName==when(l.modelRef.providerId) {ProviderIds.CHATGPT->"ChatGPT";else->"DeepSeek"})
                require(l.modelDisplayName==null || l.modelDisplayName.isNotBlank() && l.modelDisplayName.length<=256 && l.modelDisplayName.none {it.isISOControl()})
                require(l.output.length<=524288 && l.reasoning.text.length<=ReasoningReader.MAX_CHARS)
                require(l.processingDuration==null || l.processingDuration in 0..604800)
                require(ReasoningPolicy.supported(l.modelRef,l.preference))
                require(l.modelRef.providerId!=ProviderIds.CHATGPT || l.reasoning.kind==ReasoningContent.Summary)
            }
            require(run.lifecycle==CompareRunState.Pending && run.outputs.all {it.state==CompareLaneState.Pending} ||
                run.lifecycle==compareLifecycle(run.outputs.map {it.state}))
        }
    }
    private fun write(value:List<CompareRun>) {
        validate(value)
        val plain=json.encodeToString(CompareJournal(runs=value)).toByteArray(Charsets.UTF_8)
        try {require(plain.size<=8_388_608);blob.write(box.seal(plain));records=value.toList()} finally {plain.fill(0)}
    }
}

/** Exactly two child attempts. A lane catches its own failure; external cancellation joins both. */
class CompareExecutor(private val registry:ProviderRegistry,private val repository:CompareRepository,
    private val clock:()->Long={System.nanoTime()/1_000_000}) {
    suspend fun execute(draft:CompareRun,onUpdate:(CompareRun)->Unit):CompareRun = supervisorScope {
        // Future stages are storage/presentation only. The MVP never schedules them implicitly.
        require(draft.outputs.size==2 && draft.stages==listOf(CompareStage("parallel",0,listOf("A","B"))) &&
            draft.outputs.all {it.role==CompareOutputRole.Candidate})
        require(draft.laneA.modelRef!=draft.laneB.modelRef && draft.prompt.isNotBlank() && draft.prompt.length<=32768)
        require(listOf(draft.laneA,draft.laneB).all {it.state==CompareLaneState.Pending && ReasoningPolicy.supported(it.modelRef,it.preference)})
        var current=draft.copy(lifecycle=CompareRunState.Running)
        withContext(Dispatchers.IO) {repository.upsert(current)};onUpdate(current)
        val mutex=Mutex();var lastSaved=clock()
        suspend fun update(id:String,force:Boolean=false,transform:(CompareLaneRecord)->CompareLaneRecord) {
            mutex.withLock {
                val old=if(id=="A") current.laneA else current.laneB
                val lane=transform(old)
                val a=if(id=="A") lane else current.laneA;val b=if(id=="B") lane else current.laneB
                val next=current.withLanes(laneA=a,laneB=b,lifecycle=compareLifecycle(a.state,b.state))
                if(force || clock()-lastSaved>=200) {withContext(Dispatchers.IO) {repository.upsert(next)};lastSaved=clock()}
                current=next
                onUpdate(current)
            }
        }
        suspend fun attempt(initial:CompareLaneRecord):Unit = coroutineScope {
            val httpAt=AtomicReference<Long?>(null);val firstTextAt=AtomicReference<Long?>(null)
            fun interrupted(r:ReasoningRecord)=if(r.phase==ReasoningPhase.Completed || r.text.isEmpty()) r else
                ReasoningRecord(r.text,r.kind,ReasoningPhase.Interrupted)
            val timer=launch {
                while(isActive) {
                    delay(1000)
                    val started=httpAt.get()
                    if(started!=null && firstTextAt.get()==null) update(initial.laneId) {
                        if(it.state.active && firstTextAt.get()==null) it.copy(processingDuration=((clock()-started).coerceAtLeast(0)/1000).coerceAtMost(604800)) else it
                    }
                }
            }
            try {
                update(initial.laneId,true) {it.copy(state=CompareLaneState.Waiting)}
                if(!registry.ready(initial.modelRef)) throw ProviderFailure(LlmError(ErrorKind.AUTHENTICATION))
                registry.get(initial.modelRef.providerId).streamResponse(LlmRequest(initial.modelRef.modelId,
                    listOf(LlmMessage(MessageRole.USER,draft.prompt)),reasoning=initial.preference,observeHttp=true)).collect {event ->
                    update(initial.laneId,event is LlmEvent.Completed || event is LlmEvent.Failed || event is LlmEvent.Incomplete) {lane ->
                        // Terminal lanes are immutable, including when cancel-all arrives later.
                        if(!lane.state.active) return@update lane
                        fun end(phase:ReasoningPhase)=if(lane.reasoning.text.isEmpty() || lane.reasoning.phase==ReasoningPhase.Completed) lane.reasoning else
                            ReasoningRecord(lane.reasoning.text,lane.reasoning.kind,phase)
                        when(event) {
                            LlmEvent.HttpReady->{httpAt.compareAndSet(null,clock());lane}
                            is LlmEvent.TextDelta->{
                                if(lane.output.length+event.text.length>524288) throw ProviderFailure(LlmError(ErrorKind.PROTOCOL))
                                firstTextAt.compareAndSet(null,clock())
                                lane.copy(output=lane.output+event.text,state=CompareLaneState.Streaming,
                                    processingDuration=httpAt.get()?.let {((firstTextAt.get()!!-it).coerceAtLeast(0)/1000).coerceAtMost(604800)})
                            }
                            is LlmEvent.ReasoningDelta->{
                                if(initial.modelRef.providerId!=ProviderIds.DEEPSEEK && event.kind!=ReasoningContent.Summary ||
                                    lane.reasoning.text.length+event.text.length>ReasoningReader.MAX_CHARS) throw ProviderFailure(LlmError(ErrorKind.PROTOCOL))
                                lane.copy(state=if(lane.output.isEmpty()) CompareLaneState.Thinking else lane.state,
                                    reasoning=ReasoningRecord(lane.reasoning.text+event.text,event.kind,ReasoningPhase.Streaming))
                            }
                            is LlmEvent.ReasoningDone->lane.copy(reasoning=end(ReasoningPhase.Completed))
                            is LlmEvent.Completed->lane.copy(state=CompareLaneState.Completed,reasoning=end(ReasoningPhase.Completed))
                            is LlmEvent.Incomplete->lane.copy(state=CompareLaneState.Incomplete,error=event.error.kind,reasoning=end(ReasoningPhase.Interrupted))
                            is LlmEvent.Failed->lane.copy(state=CompareLaneState.Failed,error=event.error.kind,reasoning=end(ReasoningPhase.Interrupted))
                            LlmEvent.Cancelled->lane.copy(state=CompareLaneState.Cancelled,reasoning=end(ReasoningPhase.Interrupted))
                            else->lane
                        }
                    }
                }
                update(initial.laneId,true) {if(it.state.active) it.copy(state=CompareLaneState.Incomplete,error=ErrorKind.STREAM_INTERRUPTED,
                    reasoning=interrupted(it.reasoning)) else it}
            } catch(cancel:CancellationException) {
                withContext(NonCancellable) {update(initial.laneId,true) {if(it.state.active) it.copy(state=CompareLaneState.Cancelled,
                    reasoning=interrupted(it.reasoning)) else it}}
                throw cancel
            } catch(f:Exception) {
                update(initial.laneId,true) {if(it.state.active) it.copy(state=CompareLaneState.Failed,
                    error=if(f is ProviderFailure) f.error.kind else ErrorKind.STORAGE,
                    reasoning=interrupted(it.reasoning)) else it}
            } finally {withContext(NonCancellable) {timer.cancelAndJoin()}}
        }
        val a=async {attempt(draft.laneA)};val b=async {attempt(draft.laneB)}
        try {awaitAll(a,b)} finally {
            withContext(NonCancellable) {a.cancelAndJoin();b.cancelAndJoin();withContext(Dispatchers.IO) {repository.upsert(current)};onUpdate(current)}
        }
        current
    }
}
