// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.data

import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID
import java.security.SecureRandom

@Serializable enum class MessageState { PENDING, STREAMING, COMPLETED, INCOMPLETE, CANCELLED, FAILED, INTERRUPTED }
/** null timestamp/model metadata means UNKNOWN in migrated records; never reconstructed from today's catalog. */
@Serializable class ChatMessage(val id:String,val parentMessageId:String?,val role:MessageRole,
    val text:String,val state:MessageState,val reasoning:ReasoningRecord=ReasoningRecord(),
    val order:Int=-1,val timestamp:Long?=null,val modelRef:ModelRef?=null,val modelDisplayName:String?=null,
    val stage:Int?=null) {
    override fun toString()="ChatMessage(state="+state+", content=[REDACTED])"
    fun withState(text:String=this.text,state:MessageState=this.state,reasoning:ReasoningRecord=this.reasoning)=
        ChatMessage(id,parentMessageId,role,text,state,reasoning,order,timestamp,modelRef,modelDisplayName,stage)
}
/** UUIDv7: time-ordered local IDs, independent from provider/request IDs. */
object MessageIds {
    fun create():String {
        val bytes=ByteArray(16).also { SecureRandom().nextBytes(it) }; val time=System.currentTimeMillis()
        for(i in 0..5) bytes[i]=(time ushr ((5-i)*8)).toByte()
        bytes[6]=((bytes[6].toInt() and 15) or 0x70).toByte(); bytes[8]=((bytes[8].toInt() and 63) or 0x80).toByte()
        val buffer=java.nio.ByteBuffer.wrap(bytes); return UUID(buffer.long,buffer.long).toString()
    }
}
@Serializable enum class ConversationMode { Single, Collaborate }
@Serializable data class Conversation(val conversationId:String,val selectedRef:ModelRef,
    val messages:List<ChatMessage>,val title:String,val createdAt:Long?=null,val updatedAt:Long?=null,
    val mode:ConversationMode=ConversationMode.Single,val contextGrants:Set<String> = emptySet(),val schemaVersion:Int=3,
    val collaborate:CollaborateConfig?=null,val rounds:List<CollaborateRound> = emptyList(),
    val collaborateGrants:Set<String> = emptySet()) {
    override fun toString()="Conversation(content=[REDACTED])"
}
@Serializable private data class LegacySession(val providerId:String,val modelId:String,val messages:List<ChatMessage>,val sessionId:String="")
@Serializable private data class LegacyJournal(val version:Int=2,val activeProviderId:String=ProviderIds.CHATGPT,
    val activeModelId:String="UNKNOWN",val sessions:List<LegacySession> = emptyList(),val activeSessionId:String="")
@Serializable private data class ConversationJournal(val version:Int=3,val activeProviderId:String=ProviderIds.CHATGPT,
    val activeModelId:String="UNKNOWN",val conversations:List<Conversation> = emptyList(),val activeConversationId:String="")
class SingleSessionInfo(val id:String,val ref:ModelRef,val title:String,val mode:ConversationMode=ConversationMode.Single) {
    override fun toString()="SingleSessionInfo([REDACTED])"
}
fun conversationTitle(text:String):String {
    val normalized=text.replace(Regex("[\\s\\p{Z}\\p{Cc}]+")," ").trim()
    if(normalized.isEmpty()) return "新对话"
    // Code-point bound avoids cutting a surrogate pair.
    return normalized.take(normalized.offsetByCodePoints(0,minOf(48,normalized.codePointCount(0,normalized.length))))
}
/** Evolves the existing encrypted/atomic journal in place; Compare has its own unchanged journal.
 * Retention stays 32 conversations / 200 messages / 8 MiB. No eviction or network replay.
 */
class ChatRepository(private val blob:AtomicBlob,private val box:AesGcmBox,
    private val contextBuilder:ConversationContextBuilder=ConversationContextBuilder(),
    private val clock:()->Long=System::currentTimeMillis) {
    private val json=Json {encodeDefaults=true}
    private var loaded=false
    private var journal=ConversationJournal()
    private var draftId=MessageIds.create()
    private var draftMode=ConversationMode.Single
    private var draftConfig:CollaborateConfig?=null
    private var revision=0L
    @Synchronized fun activeRef():ModelRef {load();return ModelRef(journal.activeProviderId,journal.activeModelId)}
    @Synchronized fun activeConversation():Conversation? {load();return current()}
    @Synchronized fun conversationId():String {load();return current()?.conversationId ?: draftId}
    @Synchronized fun mode():ConversationMode {load();return current()?.mode ?: draftMode}
    @Synchronized fun collaborateConfig():CollaborateConfig? {load();return current()?.collaborate ?: draftConfig}
    private fun current()=journal.conversations.firstOrNull {it.conversationId==journal.activeConversationId}
    private fun messages()=current()?.messages ?: emptyList()
    @Synchronized fun activate(ref:ModelRef):List<ChatMessage> {
        load();validateRef(ref)
        val updated=journal.conversations.map {if(it.conversationId==journal.activeConversationId) it.copy(selectedRef=ref) else it}
        persist(journal.copy(activeProviderId=ref.providerId,activeModelId=ref.modelId,conversations=updated))
        return messages().toList()
    }
    @Synchronized fun activateProvider(id:String):List<ChatMessage> {
        load()
        // A provider selection keeps the conversation; it never silently picks another provider's model.
        val model=if(id==journal.activeProviderId) journal.activeModelId else "UNKNOWN"
        return activate(ModelRef(id,model))
    }
    private fun validateRef(ref:ModelRef) {
        require(ref.providerId in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK))
        require(ref.modelId.matches(Regex("[A-Za-z0-9._:/-]{1,128}")))
    }
    @Synchronized fun sessions():List<SingleSessionInfo> {
        load();return journal.conversations.map {SingleSessionInfo(it.conversationId,it.selectedRef,it.title,it.mode)}.reversed()
    }
    @Synchronized fun deleteSession(id:String):List<ChatMessage> {
        load();require(journal.conversations.any {it.conversationId==id})
        val active=id==journal.activeConversationId
        persist(journal.copy(conversations=journal.conversations.filterNot {it.conversationId==id},
            activeConversationId=if(active) "" else journal.activeConversationId))
        if(active) {draftId=MessageIds.create();draftMode=ConversationMode.Single;draftConfig=null}
        return messages().toList()
    }
    @Synchronized fun moveSession(id:String,direction:Int,mode:ConversationMode?=null) {
        load();val byId=journal.conversations.associateBy {it.conversationId}
        val ids=journal.conversations.reversed().map {it.conversationId}
        val order=if(mode==null) movedHistory(ids,id,direction) else movedCategoryHistory(ids,
            journal.conversations.filter {it.mode==mode}.map {it.conversationId}.toSet(),id,direction)
        persist(journal.copy(conversations=order.reversed().map {byId.getValue(it)}))
    }
    @Synchronized fun newSession(ref:ModelRef):List<ChatMessage> {
        load();validateRef(ref)
        persist(journal.copy(activeProviderId=ref.providerId,activeModelId=ref.modelId,activeConversationId=""))
        draftId=MessageIds.create()
        draftMode=ConversationMode.Single;draftConfig=null
        return emptyList()
    }
    @Synchronized fun newCollaborate():List<ChatMessage> {
        newSession(activeRef());draftMode=ConversationMode.Collaborate
        return emptyList()
    }
    @Synchronized fun configureCollaborate(config:CollaborateConfig) {
        load();require(mode()==ConversationMode.Collaborate);validCollaborateConfig(config)
        require(current()?.rounds?.none {it.lifecycle.active}!=false)
        val c=current()
        if(c==null) {draftConfig=config;revision++}
        else save(c.copy(collaborate=config,selectedRef=config.primary.ref))
    }
    @Synchronized fun activateSession(id:String):List<ChatMessage> {
        load();val c=journal.conversations.single {it.conversationId==id}
        persist(journal.copy(activeProviderId=c.selectedRef.providerId,activeModelId=c.selectedRef.modelId,activeConversationId=id))
        return messages().toList()
    }
    @Synchronized fun load():List<ChatMessage> {
        if(!loaded) {
            val sealed=blob.read()
            var migration=false
            val candidate=if(sealed==null) ConversationJournal() else {
                val plain=box.open(sealed)
                try {
                    require(plain.size<=8_388_608)
                    val source=utf8(plain);val root=json.parseToJsonElement(source)
                    when {
                        root is JsonArray -> {migration=true;migrate(LegacyJournal(sessions=listOf(LegacySession(
                            ProviderIds.CHATGPT,"UNKNOWN",json.decodeFromString<List<ChatMessage>>(source)))))}
                        root is JsonObject && root["version"]?.jsonPrimitive?.intOrNull==2 -> {
                            require(root["sessions"] is JsonArray && root.containsKey("activeProviderId") && root.containsKey("activeModelId"))
                            migration=true;migrate(json.decodeFromString<LegacyJournal>(source))
                        }
                        root is JsonObject && root["version"]?.jsonPrimitive?.intOrNull==3 -> json.decodeFromString<ConversationJournal>(source)
                        else -> error("UNSUPPORTED_CHAT_SCHEMA")
                    }
                } finally {plain.fill(0)}
            }
            validate(candidate)
            var recovery=false
            val recovered=candidate.copy(conversations=candidate.conversations.map {c ->c.copy(messages=c.messages.map {m ->
                if(m.state in setOf(MessageState.PENDING,MessageState.STREAMING)) {
                    recovery=true
                    m.withState(state=MessageState.INTERRUPTED,reasoning=ReasoningRecord(m.reasoning.text,m.reasoning.kind,
                        if(m.reasoning.phase==ReasoningPhase.Completed) ReasoningPhase.Completed
                        else if(m.reasoning.text.isEmpty()) ReasoningPhase.Unavailable else ReasoningPhase.Interrupted))
                } else m
            },rounds=c.rounds.map {r ->restoreCollaborate(r).also {if(it!=r) recovery=true}})})
            // Validate everything before the single atomic write. A failed migration cannot replace old ciphertext with an empty journal.
            if(migration || recovery) persist(recovered) else journal=recovered
            loaded=true
        }
        return messages().toList()
    }
    private fun migrate(old:LegacyJournal):ConversationJournal {
        require(old.version==2 && old.sessions.size<=32 && old.sessions.sumOf {it.messages.size}<=200)
        validateRef(ModelRef(old.activeProviderId,old.activeModelId))
        val rows=old.sessions.map {s ->
            val ref=ModelRef(s.providerId,s.modelId);validateRef(ref);require(s.sessionId.length<=128)
            val id=s.sessionId.ifEmpty {"legacy:"+s.providerId+":"+s.modelId}
            Conversation(id,ref,s.messages.mapIndexed {index,m ->ChatMessage(m.id,m.parentMessageId,m.role,m.text,m.state,
                m.reasoning,index,m.timestamp,ref,m.modelDisplayName,m.stage)},
                conversationTitle(s.messages.firstOrNull {it.role==MessageRole.USER}?.text ?: ""))
        }
        val active=rows.firstOrNull {c ->c.selectedRef==ModelRef(old.activeProviderId,old.activeModelId) &&
            c.conversationId==(old.activeSessionId.ifEmpty {"legacy:"+old.activeProviderId+":"+old.activeModelId})}
        require(old.activeSessionId.isEmpty() || active!=null)
        return ConversationJournal(activeProviderId=old.activeProviderId,activeModelId=old.activeModelId,
            conversations=rows,activeConversationId=active?.conversationId ?: "")
    }
    @Synchronized fun prepare(userText:String,displayName:String?=null):PreparedTurn {
        load()
        require(mode()==ConversationMode.Single)
        val context=contextBuilder.build(messages(),userText)
        val ref=activeRef();val c=current()
        val needs=context.sourceProviders.any {it!=ref.providerId} && ref.providerId !in (c?.contextGrants ?: emptySet())
        return PreparedTurn(conversationId(),messages().lastOrNull()?.id,ref,userText,context,needs,displayName?.take(256),revision)
    }
    @Synchronized fun begin(userText:String):Pair<String,List<LlmMessage>> = beginPrepared(prepare(userText))
    @Synchronized fun beginPrepared(turn:PreparedTurn,allowSharing:Boolean=false):Pair<String,List<LlmMessage>> {
        load()
        require(turn.revision==revision && turn.conversationId==conversationId() && turn.previousId==messages().lastOrNull()?.id && turn.ref==activeRef())
        if(turn.requiresSharing && !allowSharing) throw ContextSharingRequired()
        require(messages().none {it.state==MessageState.PENDING || it.state==MessageState.STREAMING})
        require(recordCount(journal)<=198)
        require(current()!=null || journal.conversations.size<32)
        val time=clock()
        val user=ChatMessage(MessageIds.create(),messages().lastOrNull()?.id,MessageRole.USER,turn.text,MessageState.COMPLETED,
            order=messages().size,timestamp=time,modelRef=turn.ref,modelDisplayName=turn.displayName)
        val assistant=ChatMessage(MessageIds.create(),user.id,MessageRole.ASSISTANT,"",MessageState.PENDING,
            order=messages().size+1,timestamp=time,modelRef=turn.ref,modelDisplayName=turn.displayName)
        val c=current() ?: Conversation(draftId,turn.ref,emptyList(),conversationTitle(turn.text),time,time)
        val next=c.copy(messages=c.messages+listOf(user,assistant),updatedAt=time,
            contextGrants=if(turn.requiresSharing && allowSharing) c.contextGrants+turn.ref.providerId else c.contextGrants)
        save(next)
        return assistant.id to turn.context.messages
    }
    @Synchronized fun prepareCollaborate(userText:String):PreparedCollaborate = prepareCollaborate(userText,null)
    private fun prepareCollaborate(userText:String,retry:CollaborateRound?):PreparedCollaborate {
        load();require(mode()==ConversationMode.Collaborate)
        val config=retry?.config ?: requireNotNull(collaborateConfig());validCollaborateConfig(config)
        val recent=contextBuilder.build(contextMessages(current()),userText)
        val context=if(retry==null) recent else VisibleContext(retry.frozenInput.map {LlmMessage(it.role,it.text)},recent.sourceProviders)
        val needs=(config.providers.size>1 || context.sourceProviders.any {it !in config.providers}) &&
            config.providerSetKey !in (current()?.collaborateGrants ?: emptySet())
        return PreparedCollaborate(conversationId(),messages().lastOrNull()?.id,userText,config,context,needs,revision,retry?.roundId)
    }
    @Synchronized fun prepareCollaborateRetry(roundId:String):PreparedCollaborate {
        load();val c=requireNotNull(current());val r=c.rounds.last()
        require(r.roundId==roundId && !r.lifecycle.active && r.lifecycle!=CollaborateRoundState.Complete)
        return prepareCollaborate(c.messages.single {it.id==r.userMessageId}.text,r)
    }
    @Synchronized fun beginCollaborate(plan:PreparedCollaborate,allowSharing:Boolean=false):Conversation {
        load();require(mode()==ConversationMode.Collaborate)
        require(plan.revision==revision && plan.conversationId==conversationId() && plan.previousId==messages().lastOrNull()?.id)
        require(plan.retryOf!=null || plan.config==collaborateConfig())
        if(plan.requiresSharing && !allowSharing) throw ContextSharingRequired()
        require(current()?.rounds?.none {it.lifecycle.active}!=false)
        require(recordCount(journal)<=196 && (current()!=null || journal.conversations.size<32))
        val time=clock();val user=ChatMessage(MessageIds.create(),messages().lastOrNull()?.id,MessageRole.USER,plan.text,
            MessageState.COMPLETED,order=messages().size,timestamp=time,modelRef=plan.config.primary.ref)
        val stages=CollaborateStageType.entries.mapIndexed {index,type ->CollaborateStage(MessageIds.create(),type,index,
            plan.config.modelFor(type))}
        val round=CollaborateRound(MessageIds.create(),user.id,stages,plan.context.messages.map {FrozenVisibleInput(it.role,it.text)},
            revision,time,time,retryOf=plan.retryOf,reviewIntensity=plan.config.reviewIntensity,synthesisRole=plan.config.synthesisRole)
        val c=current() ?: Conversation(draftId,plan.config.primary.ref,emptyList(),conversationTitle(plan.text),time,time,
            mode=ConversationMode.Collaborate,collaborate=plan.config)
        val next=c.copy(messages=c.messages+user,rounds=c.rounds+round,updatedAt=time,
            collaborateGrants=if(plan.requiresSharing && allowSharing) c.collaborateGrants+plan.config.providerSetKey else c.collaborateGrants)
        save(next);return next
    }
    @Synchronized fun startCollaborateStage(roundId:String,index:Int):Conversation {
        load();val c=requireNotNull(current());val r=c.rounds.last();require(r.roundId==roundId && r.lifecycle.active && index in 0..2)
        require(r.stages[index].state==CollaborateStageState.Pending && r.stages.take(index).all {it.state==CollaborateStageState.Complete && it.output.isNotBlank()})
        require(r.stages.none {it.state==CollaborateStageState.Running})
        val next=r.copy(stages=r.stages.mapIndexed {i,s ->if(i==index) s.copy(state=CollaborateStageState.Running,startedAt=clock()) else s},updatedAt=clock())
        return saveRound(c,next)
    }
    @Synchronized fun updateCollaborateStage(roundId:String,stage:CollaborateStage):Conversation {
        load();val c=requireNotNull(current());val r=c.rounds.last();require(r.roundId==roundId && r.lifecycle.active)
        val previous=r.stages.single {it.stageId==stage.stageId}
        require(previous.state==CollaborateStageState.Running && stage.type==previous.type && stage.order==previous.order &&
            stage.model==previous.model && stage.startedAt==previous.startedAt && stage.state!=CollaborateStageState.Pending && stage.state!=CollaborateStageState.NotRun)
        return saveRound(c,settleRound(r.copy(stages=r.stages.map {if(it.stageId==stage.stageId) stage else it}),clock()))
    }
    @Synchronized fun cancelCollaborateRound(roundId:String):Conversation {
        load();val c=requireNotNull(current());val r=c.rounds.last();require(r.roundId==roundId)
        if(!r.lifecycle.active) return c
        val active=r.stages.indexOfFirst {it.state==CollaborateStageState.Running || it.state==CollaborateStageState.Pending}
        return saveRound(c,settleRound(r.copy(stages=r.stages.mapIndexed {index,s ->if(index==active)
            s.copy(state=CollaborateStageState.Cancelled,error=ErrorKind.CANCELLED,endedAt=clock(),reasoning=interruptReasoning(s.reasoning)) else s}),clock()))
    }
    private fun saveRound(c:Conversation,r:CollaborateRound):Conversation {
        val next=c.copy(rounds=c.rounds.map {if(it.roundId==r.roundId) r else it},updatedAt=clock())
        save(next);return next
    }
    /** Only synthesis becomes the ordinary answer. Intermediate stages stay in local history. */
    private fun contextMessages(c:Conversation?):List<ChatMessage> {
        if(c==null) return emptyList()
        if(c.mode==ConversationMode.Single) return c.messages
        return c.messages.flatMap {u ->
            val s=c.rounds.single {it.userMessageId==u.id}.stages.last()
            if(s.state==CollaborateStageState.Complete) listOf(u,ChatMessage(s.stageId,u.id,MessageRole.ASSISTANT,s.output,
                MessageState.COMPLETED,modelRef=s.model.ref)) else listOf(u)
        }
    }
    @Synchronized fun update(id:String,text:String,state:MessageState,reasoning:ReasoningRecord?=null):List<ChatMessage> {
        load();require(text.length<=4_194_304)
        val c=requireNotNull(current());require(c.mode==ConversationMode.Single);val target=c.messages.single {it.id==id}
        require(target.role==MessageRole.ASSISTANT)
        save(c.copy(messages=c.messages.map {if(it.id==id) it.withState(text,state,reasoning ?: it.reasoning) else it},updatedAt=clock()))
        return messages().toList()
    }
    private fun save(c:Conversation) {
        val found=journal.conversations.any {it.conversationId==c.conversationId}
        val rows=if(found) journal.conversations.map {if(it.conversationId==c.conversationId) c else it} else journal.conversations+c
        persist(journal.copy(conversations=rows,activeConversationId=c.conversationId,activeProviderId=c.selectedRef.providerId,activeModelId=c.selectedRef.modelId))
    }
    // A Collaborate user + three stage outputs count as four records, including pending/empty stages.
    private fun recordCount(value:ConversationJournal)=value.conversations.sumOf {it.messages.size+it.rounds.sumOf {r ->r.stages.size}}
    private fun validate(value:ConversationJournal) {
        require(value.version==3 && value.conversations.size<=32 && recordCount(value)<=200)
        validateRef(ModelRef(value.activeProviderId,value.activeModelId))
        require(value.conversations.map {it.conversationId}.distinct().size==value.conversations.size)
        value.conversations.forEach {c ->
            require(c.schemaVersion==3 && c.conversationId.length in 1..160)
            if(c.mode==ConversationMode.Single) require(c.rounds.isEmpty() && c.collaborate==null && c.collaborateGrants.isEmpty())
            else validateCollaborate(c)
            validateRef(c.selectedRef)
            require(c.title.length<=192 && c.contextGrants.all {it in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK)})
            require(c.createdAt==null || c.createdAt>=0);require(c.updatedAt==null || c.updatedAt>=0)
            require(c.messages.map {it.id}.distinct().size==c.messages.size)
            c.messages.forEachIndexed {index,m ->
                require(m.id.length in 1..128 && m.order==index && m.text.length<=4_194_304)
                require(m.timestamp==null || m.timestamp>=0);m.modelRef?.let(::validateRef)
                require(m.modelDisplayName==null || m.modelDisplayName.length<=256)
                require(m.stage==null || m.stage>=0)
                require(m.reasoning.text.length<=ReasoningReader.MAX_CHARS)
                require(m.modelRef?.providerId!=ProviderIds.CHATGPT || m.reasoning.kind==ReasoningContent.Summary)
            }
        }
        require(value.activeConversationId.isEmpty() || value.conversations.any {it.conversationId==value.activeConversationId &&
            it.selectedRef==ModelRef(value.activeProviderId,value.activeModelId)})
    }
    private fun validateCollaborate(c:Conversation) {
        validCollaborateConfig(requireNotNull(c.collaborate))
        require(c.collaborateGrants.all {it in setOf("chatgpt","deepseek","chatgpt|deepseek")})
        require(c.messages.all {it.role==MessageRole.USER && it.state==MessageState.COMPLETED && it.reasoning.text.isEmpty()})
        require(c.rounds.size==c.messages.size && c.rounds.map {it.roundId}.distinct().size==c.rounds.size)
        val stageIds=c.rounds.flatMap {it.stages}.map {it.stageId}
        require(stageIds.distinct().size==stageIds.size)
        require(c.rounds.map {it.userMessageId}==c.messages.map {it.id})
        require(c.rounds.count {it.lifecycle.active}<=1 && c.rounds.dropLast(1).none {it.lifecycle.active})
        c.rounds.forEachIndexed {roundIndex,r ->
            require(r.roundId.length in 1..128 && r.stages.size==3 && r.inputRevision>=0 && r.createdAt>=0 && r.updatedAt>=r.createdAt)
            require(r.retryOf==null || c.rounds.take(roundIndex).any {it.roundId==r.retryOf && !it.lifecycle.active && it.lifecycle!=CollaborateRoundState.Complete})
            require(r.stages.map {it.type}==CollaborateStageType.entries && r.stages.map {it.order}==listOf(0,1,2))
            require(r.stages.map {it.stageId}.distinct().size==3 && r.stages.none {it.stageId in c.messages.map {m ->m.id}})
            validCollaborateConfig(r.config);require(r.stages.all {it.model==r.config.modelFor(it.type)})
            require(r.frozenInput.size in 1..41 && r.frozenInput.last().role==MessageRole.USER && r.frozenInput.last().text==c.messages[roundIndex].text)
            require(r.frozenInput.withIndex().all {(i,m) ->m.role==if(i%2==0) MessageRole.USER else MessageRole.ASSISTANT})
            require(r.frozenInput.sumOf {utf8ContentSize(it.text).toLong()}<=131072)
            require(r.lifecycle==roundLifecycle(r.stages))
            r.stages.forEachIndexed {index,s ->
                require(s.stageId.length in 1..128 && s.output.length<=524288 && s.reasoning.text.length<=ReasoningReader.MAX_CHARS)
                require(s.model.ref.providerId!=ProviderIds.CHATGPT || s.reasoning.kind==ReasoningContent.Summary)
                require(s.processingDuration==null || s.processingDuration in 0..604800)
                require(s.startedAt==null || s.startedAt>=0);require(s.endedAt==null || s.endedAt>=0)
                if(s.state==CollaborateStageState.Complete) require(s.output.isNotBlank() && s.error==null)
                if(s.state in setOf(CollaborateStageState.Running,CollaborateStageState.Complete,CollaborateStageState.Failed,CollaborateStageState.Cancelled,CollaborateStageState.Interrupted))
                    require(r.stages.take(index).all {it.state==CollaborateStageState.Complete})
                if(!r.lifecycle.active) require(s.state !in setOf(CollaborateStageState.Pending,CollaborateStageState.Running))
            }
        }
    }
    private fun persist(value:ConversationJournal) {
        validate(value)
        val plain=json.encodeToString(value).toByteArray(Charsets.UTF_8)
        try {require(plain.size<=8_388_608);blob.write(box.seal(plain));journal=value;revision++} finally {plain.fill(0)}
    }
}
