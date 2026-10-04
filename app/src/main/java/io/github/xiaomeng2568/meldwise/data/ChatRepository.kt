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
@Serializable enum class ConversationMode { Single, Collaborate, Debate }
@Serializable data class Conversation(val conversationId:String,val selectedRef:ModelRef,
    val messages:List<ChatMessage>,val title:String,val createdAt:Long?=null,val updatedAt:Long?=null,
    val mode:ConversationMode=ConversationMode.Single,val contextGrants:Set<String> = emptySet(),val schemaVersion:Int=4,
    val collaborate:CollaborateConfig?=null,val rounds:List<CollaborateRound> = emptyList(),
    val collaborateGrants:Set<String> = emptySet(),val debate:DebateConfig?=null,
    val debateRounds:List<DebateRound> = emptyList(),val debateGrants:Set<String> = emptySet()) {
    override fun toString()="Conversation(content=[REDACTED])"
}
@Serializable private data class LegacySession(val providerId:String,val modelId:String,val messages:List<ChatMessage>,val sessionId:String="")
@Serializable private data class LegacyJournal(val version:Int=2,val activeProviderId:String=ProviderIds.CHATGPT,
    val activeModelId:String="UNKNOWN",val sessions:List<LegacySession> = emptyList(),val activeSessionId:String="")
@Serializable private data class ConversationJournal(val version:Int=4,val activeProviderId:String=ProviderIds.CHATGPT,
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
 * Retention stays 32 conversations / 200 logical records / 8 MiB ciphertext. No eviction or network replay.
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
    private var draftDebate:DebateConfig?=null
    private var revision=0L
    @Synchronized fun activeRef():ModelRef {load();return ModelRef(journal.activeProviderId,journal.activeModelId)}
    @Synchronized fun activeConversation():Conversation? {load();return current()}
    @Synchronized fun conversationId():String {load();return current()?.conversationId ?: draftId}
    @Synchronized fun mode():ConversationMode {load();return current()?.mode ?: draftMode}
    @Synchronized fun collaborateConfig():CollaborateConfig? {load();return current()?.collaborate ?: draftConfig}
    @Synchronized internal fun debateConfig():DebateConfig? {load();return current()?.debate ?: draftDebate}
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
        if(active) {draftId=MessageIds.create();draftMode=ConversationMode.Single;draftConfig=null;draftDebate=null}
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
        draftMode=ConversationMode.Single;draftConfig=null;draftDebate=null
        return emptyList()
    }
    @Synchronized fun newCollaborate():List<ChatMessage> {
        newSession(activeRef());draftMode=ConversationMode.Collaborate
        return emptyList()
    }
    // Internal Debate APIs: no UI route until Phase 7C.
    @Synchronized internal fun newDebate():List<ChatMessage> {
        newSession(activeRef());draftMode=ConversationMode.Debate;return emptyList()
    }
    @Synchronized internal fun configureDebate(config:DebateConfig) {
        load();require(mode()==ConversationMode.Debate);validDebateConfig(config)
        require(current()?.debateRounds?.none {it.lifecycle.active}!=false)
        val c=current()
        if(c==null) {draftDebate=config;revision++} else save(c.copy(debate=config,selectedRef=config.modelA.ref))
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
            require(sealed==null || sealed.size<=8_388_608)
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
                        root is JsonObject && root["version"]?.jsonPrimitive?.intOrNull==3 -> {
                            require(root["conversations"] is JsonArray && root.containsKey("activeProviderId") && root.containsKey("activeModelId") && root.containsKey("activeConversationId"))
                            val old=json.decodeFromString<ConversationJournal>(source)
                            validate(old,3)
                            migration=true
                            old.copy(version=4,conversations=old.conversations.map {it.copy(schemaVersion=4)})
                        }
                        root is JsonObject && root["version"]?.jsonPrimitive?.intOrNull==4 -> {
                            require(root["conversations"] is JsonArray && root.containsKey("activeProviderId") && root.containsKey("activeModelId") && root.containsKey("activeConversationId"))
                            json.decodeFromString<ConversationJournal>(source)
                        }
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
            },rounds=c.rounds.map {r ->restoreCollaborate(r).also {if(it!=r) recovery=true}},
                debateRounds=c.debateRounds.map {r ->restoreDebate(r).also {if(it!=r) recovery=true}})})
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
        val recent=contextBuilder.build(conversationContextMessages(current()),userText)
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
    @Synchronized internal fun prepareDebate(userText:String):PreparedDebate = prepareDebate(userText,null)
    private fun prepareDebate(userText:String,retry:DebateRound?):PreparedDebate {
        load();require(mode()==ConversationMode.Debate)
        val config=retry?.config ?: requireNotNull(debateConfig());validDebateConfig(config)
        val context=if(retry==null) contextBuilder.build(conversationContextMessages(current()),userText)
            else VisibleContext(retry.frozenInput.map {LlmMessage(it.role,it.text)},retry.sourceProviders)
        val needs=debateNeedsSharing(config,context.sourceProviders,current()?.debateGrants ?: emptySet())
        return PreparedDebate(conversationId(),messages().lastOrNull()?.id,userText,config,context,needs,revision,retry?.roundId,
            inputRevision=retry?.inputRevision ?: revision)
    }
    @Synchronized internal fun prepareDebateRetry(roundId:String):PreparedDebate {
        load();val c=requireNotNull(current());val r=c.debateRounds.last()
        require(r.roundId==roundId && !r.lifecycle.active && r.lifecycle!=DebateRoundState.Complete)
        return prepareDebate(c.messages.single {it.id==r.userMessageId}.text,r)
    }
    /** Read-only preflight; beginDebate repeats it after suspended credential checks. */
    @Synchronized internal fun validateDebateAdmission(plan:PreparedDebate,allowSharing:Boolean=false) {
        load();require(mode()==ConversationMode.Debate)
        require(plan.revision==revision && plan.conversationId==conversationId() && plan.previousId==messages().lastOrNull()?.id)
        validDebateConfig(plan.config)
        require(plan.retryOf!=null || plan.config==debateConfig())
        val input=plan.context.messages
        require(input.size in 1..41 && input.size%2==1 && input.last().text==plan.text)
        require(input.withIndex().all {(i,m)->m.text.isNotBlank() && m.role==if(i%2==0) MessageRole.USER else MessageRole.ASSISTANT})
        if(input.sumOf {utf8ContentSize(it.text).toLong()}>131072) throw ProviderFailure(LlmError(ErrorKind.CONTEXT_OVERFLOW))
        require(plan.context.sourceProviders.all {it in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK,"UNKNOWN")})
        require(plan.requiresSharing==debateNeedsSharing(plan.config,plan.context.sourceProviders,current()?.debateGrants ?: emptySet()))
        if(plan.requiresSharing && !allowSharing) throw ContextSharingRequired()
        require(current()?.debateRounds?.none {it.lifecycle.active}!=false)
        if(plan.retryOf!=null) {
            val original=requireNotNull(current()).debateRounds.last()
            require(original.roundId==plan.retryOf && !original.lifecycle.active && original.lifecycle!=DebateRoundState.Complete)
            require(plan.config==original.config && plan.inputRevision==original.inputRevision && plan.context.sourceProviders==original.sourceProviders)
            require(input.map {FrozenVisibleInput(it.role,it.text)}==original.frozenInput)
        } else require(plan.inputRevision==plan.revision)
        // Reserve all five stage snapshots, even empty/pending ones. One user + five stages = six records.
        require(recordCount(journal)<=194 && (current()!=null || journal.conversations.size<32))
    }
    @Synchronized internal fun beginDebate(plan:PreparedDebate,allowSharing:Boolean=false):Conversation {
        validateDebateAdmission(plan,allowSharing)
        return admitDebate(plan,allowSharing)
    }
    @Synchronized internal fun beginDebateExecution(plan:PreparedDebate,allowSharing:Boolean=false):Conversation {
        validateDebateAdmission(plan,allowSharing)
        return try {admitDebate(plan,allowSharing)} catch(_:Exception) {throw ProviderFailure(LlmError(ErrorKind.STORAGE))}
    }
    private fun admitDebate(plan:PreparedDebate,allowSharing:Boolean):Conversation {
        val time=clock();val user=ChatMessage(MessageIds.create(),messages().lastOrNull()?.id,MessageRole.USER,plan.text,
            MessageState.COMPLETED,order=messages().size,timestamp=time,modelRef=plan.config.modelA.ref)
        val round=DebateRound(MessageIds.create(),user.id,plan.config,DebateStageType.entries.map {
            DebateStage(MessageIds.create(),it,plan.config.modelFor(it))
        },plan.context.messages.map {FrozenVisibleInput(it.role,it.text)},plan.inputRevision,time,time,
            retryOf=plan.retryOf,sourceProviders=plan.context.sourceProviders.toSet())
        val c=current() ?: Conversation(draftId,plan.config.modelA.ref,emptyList(),conversationTitle(plan.text),time,time,
            mode=ConversationMode.Debate,debate=plan.config)
        val next=c.copy(messages=c.messages+user,debateRounds=c.debateRounds+round,updatedAt=time,
            debateGrants=if(plan.requiresSharing && allowSharing) c.debateGrants+plan.config.providerSetKey else c.debateGrants)
        save(next);return next
    }
    @Synchronized internal fun startDebateStage(roundId:String,type:DebateStageType):Conversation {
        load();val c=requireNotNull(current());require(c.mode==ConversationMode.Debate)
        val r=c.debateRounds.last();require(r.roundId==roundId && type in DebateDag.readyStages(r))
        return saveDebateRound(c,r.copy(lifecycle=DebateRoundState.Running,updatedAt=clock(),stages=r.stages.map {
            if(it.type==type) it.copy(state=DebateStageState.Running,startedAt=clock()) else it
        }))
    }
    @Synchronized internal fun updateDebateStage(roundId:String,stage:DebateStage):Conversation {
        load();val c=requireNotNull(current());require(c.mode==ConversationMode.Debate)
        val r=c.debateRounds.last();require(r.roundId==roundId && r.lifecycle.active)
        val old=r.stages.single {it.stageId==stage.stageId}
        require(old.state==DebateStageState.Running && stage.type==old.type && stage.model==old.model && stage.startedAt==old.startedAt)
        require(stage.output.startsWith(old.output) && stage.reasoning.text.startsWith(old.reasoning.text))
        require(stage.state !in setOf(DebateStageState.Pending,DebateStageState.NotRun))
        return saveDebateRound(c,settleDebateRound(r.copy(stages=r.stages.map {if(it.stageId==stage.stageId) stage else it}),clock()))
    }
    @Synchronized internal fun debateSnapshot(conversationId:String,roundId:String):Conversation {
        load();return journal.conversations.single {it.conversationId==conversationId}.also {c ->
            require(c.mode==ConversationMode.Debate && c.debateRounds.last().roundId==roundId)
        }
    }
    /** Start the entire dependency-eligible wave atomically, using only durable upstream state. */
    @Synchronized internal fun startDebateWave(conversationId:String,roundId:String,types:List<DebateStageType>):Conversation {
        val c=debateSnapshot(conversationId,roundId);val r=c.debateRounds.last()
        val expected=when {
            r.stage(DebateStageType.INITIAL_A).state==DebateStageState.Pending->listOf(DebateStageType.INITIAL_A,DebateStageType.INITIAL_B)
            r.stage(DebateStageType.REVIEW_A_OF_B).state==DebateStageState.Pending->listOf(DebateStageType.REVIEW_A_OF_B,DebateStageType.REVIEW_B_OF_A)
            else->listOf(DebateStageType.JUDGE)
        }
        require(types==expected && r.lifecycle.active && types.all {it in DebateDag.readyStages(r)})
        val now=clock()
        return saveDebateExecution(c,r.copy(lifecycle=DebateRoundState.Running,updatedAt=maxOf(now,r.updatedAt),stages=r.stages.map {
            if(it.type in types) it.copy(state=DebateStageState.Running,startedAt=now) else it
        }))
    }
    /** The exact immutable Running object is the local CAS token (no schema field is added).
     * Merge by stage ID into the CURRENT round, never a worker's stale sibling snapshot.
     * Failure settlement is called only after the executor has joined all active transports.
     * null means stale/terminal: no write, no revival, and no change to another conversation.
     */
    @Synchronized internal fun commitDebateStages(conversationId:String,roundId:String,changes:List<DebateStageChange>):Conversation? {
        val c=debateSnapshot(conversationId,roundId);val r=c.debateRounds.last()
        require(changes.isNotEmpty() && changes.map {it.expected.stageId}.distinct().size==changes.size)
        if(!r.lifecycle.active || changes.any {change ->r.stages.none {it===change.expected && it.state==DebateStageState.Running}}) return null
        changes.forEach {(old,next)->
            require(next.stageId==old.stageId && next.type==old.type && next.model==old.model && next.startedAt==old.startedAt)
            require(next.state !in setOf(DebateStageState.Pending,DebateStageState.NotRun))
            require(next.output.startsWith(old.output) && next.reasoning.text.startsWith(old.reasoning.text))
        }
        val replacements=changes.associate {it.expected.stageId to it.next}
        return saveDebateExecution(c,settleDebateRound(r.copy(stages=r.stages.map {replacements[it.stageId] ?: it}),clock()))
    }
    @Synchronized internal fun cancelDebateExecution(conversationId:String,roundId:String):Conversation {
        val c=debateSnapshot(conversationId,roundId);val r=c.debateRounds.last()
        if(!r.lifecycle.active) return c
        val seed=r.stages.firstOrNull {it.state==DebateStageState.Running} ?: r.stages.first {it.state==DebateStageState.Pending}
        return saveDebateExecution(c,settleDebateRound(r.copy(stages=r.stages.map {s ->
            if(s===seed || s.state==DebateStageState.Running) s.copy(state=DebateStageState.Cancelled,error=ErrorKind.CANCELLED,
                endedAt=maxOf(clock(),s.startedAt ?: 0),reasoning=interruptedDebateReasoning(s.reasoning)) else s
        }),clock()))
    }
    @Synchronized internal fun interruptDebateExecution(conversationId:String,roundId:String):Conversation {
        val c=debateSnapshot(conversationId,roundId);val r=c.debateRounds.last()
        if(!r.lifecycle.active) return c
        val restored=restoreDebate(r).let {it.copy(stages=it.stages.map {s ->
            if(s.state==DebateStageState.Interrupted) s.copy(error=ErrorKind.STREAM_INTERRUPTED,
                endedAt=maxOf(clock(),s.startedAt ?: 0)) else s
        },updatedAt=maxOf(clock(),it.updatedAt))}
        return saveDebateExecution(c,restored)
    }
    /** Execution belongs to its admitted conversation, even if navigation changes the active one. */
    private fun saveDebateExecution(c:Conversation,r:DebateRound):Conversation {
        val next=c.copy(debateRounds=c.debateRounds.map {if(it.roundId==r.roundId) r else it},updatedAt=maxOf(clock(),c.updatedAt ?: 0))
        persist(journal.copy(conversations=journal.conversations.map {if(it.conversationId==c.conversationId) next else it}))
        return next
    }
    @Synchronized internal fun cancelDebateRound(roundId:String):Conversation {
        load();val c=requireNotNull(current());require(c.mode==ConversationMode.Debate)
        val r=c.debateRounds.last();require(r.roundId==roundId)
        if(!r.lifecycle.active) return c
        val seed=r.stages.firstOrNull {it.state==DebateStageState.Running} ?: r.stages.first {it.state==DebateStageState.Pending}
        return saveDebateRound(c,settleDebateRound(r.copy(stages=r.stages.map {s ->
            if(s.stageId==seed.stageId || s.state==DebateStageState.Running) s.copy(state=DebateStageState.Cancelled,
                error=ErrorKind.CANCELLED,endedAt=clock(),reasoning=interruptedDebateReasoning(s.reasoning)) else s
        }),clock()))
    }
    private fun saveDebateRound(c:Conversation,r:DebateRound):Conversation {
        val next=c.copy(debateRounds=c.debateRounds.map {if(it.roundId==r.roundId) r else it},updatedAt=clock())
        save(next);return next
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
    // Reserve pending/empty stages too: Collaborate user + 3 stages = 4; Debate user + 5 stages = 6.
    private fun recordCount(value:ConversationJournal)=value.conversations.sumOf {
        it.messages.size+it.rounds.sumOf {r ->r.stages.size}+it.debateRounds.sumOf {r->r.stages.size}
    }
    private fun validate(value:ConversationJournal,version:Int=4) {
        require(value.version==version && value.conversations.size<=32 && recordCount(value)<=200)
        validateRef(ModelRef(value.activeProviderId,value.activeModelId))
        require(value.conversations.map {it.conversationId}.distinct().size==value.conversations.size)
        value.conversations.forEach {c ->
            require(c.schemaVersion==version && c.conversationId.length in 1..160)
            if(version==3 || c.mode!=ConversationMode.Debate) require(c.debate==null && c.debateRounds.isEmpty() && c.debateGrants.isEmpty())
            if(version==3) require(c.mode!=ConversationMode.Debate)
            when(c.mode) {
                ConversationMode.Single->require(c.rounds.isEmpty() && c.collaborate==null && c.collaborateGrants.isEmpty())
                ConversationMode.Collaborate->validateCollaborate(c)
                ConversationMode.Debate->validateDebate(c)
            }
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
        try {
            require(plain.size<=8_388_608-29)
            val encrypted=box.seal(plain);require(encrypted.size<=8_388_608)
            blob.write(encrypted);journal=value;revision++
        } finally {plain.fill(0)}
    }
}
