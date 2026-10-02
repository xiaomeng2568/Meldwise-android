package io.github.xiaomeng2568.meldwise.data

import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.UUID
import java.security.SecureRandom

@Serializable enum class MessageState { PENDING, STREAMING, COMPLETED, INCOMPLETE, CANCELLED, FAILED }
@Serializable class ChatMessage(val id:String,val parentMessageId:String?,val role:MessageRole,
    val text:String,val state:MessageState,val reasoning:ReasoningRecord=ReasoningRecord()) {
    override fun toString()="ChatMessage(state=$state, text=[REDACTED])"
}
/** UUIDv7: time-ordered local message IDs, independent from provider/request IDs. */
object MessageIds {
    fun create():String {
        val bytes=ByteArray(16).also { SecureRandom().nextBytes(it) }; val time=System.currentTimeMillis()
        for(i in 0..5) bytes[i]=(time ushr ((5-i)*8)).toByte()
        bytes[6]=((bytes[6].toInt() and 15) or 0x70).toByte(); bytes[8]=((bytes[8].toInt() and 63) or 0x80).toByte()
        val buffer=java.nio.ByteBuffer.wrap(bytes); return UUID(buffer.long,buffer.long).toString()
    }
}
@Serializable private data class ChatSession(val providerId:String,val modelId:String,val messages:List<ChatMessage>,val sessionId:String="")
@Serializable private data class ChatJournal(val version:Int=2,val activeProviderId:String=ProviderIds.CHATGPT,
    val activeModelId:String="UNKNOWN",val sessions:List<ChatSession> = emptyList(),val activeSessionId:String="")
class SingleSessionInfo(val id:String,val ref:ModelRef,val title:String) {
    override fun toString()="SingleSessionInfo([REDACTED])"
}
/** Bounded encrypted journal. Exact provider/model sessions never share request history. */
class ChatRepository(private val blob:AtomicBlob,private val box:AesGcmBox) {
    private val json=Json { encodeDefaults=true }
    private var messages=emptyList<ChatMessage>()
    private var loaded=false
    private var journal=ChatJournal()
    @Synchronized fun activeRef():ModelRef { load();return ModelRef(journal.activeProviderId,journal.activeModelId) }
    @Synchronized fun activate(ref:ModelRef):List<ChatMessage> {
        load();validateRef(ref)
        val session=if(ref==activeRef()) journal.sessions.firstOrNull {it.sessionId==journal.activeSessionId && it.providerId==ref.providerId && it.modelId==ref.modelId}
            else journal.sessions.lastOrNull {it.providerId==ref.providerId && it.modelId==ref.modelId}
        val next=journal.copy(activeProviderId=ref.providerId,activeModelId=ref.modelId,activeSessionId=session?.sessionId ?: "")
        persist(next)
        return messages.toList()
    }
    @Synchronized fun activateProvider(id:String):List<ChatMessage> {
        load()
        val session=journal.sessions.lastOrNull { it.providerId==id }
        return activate(ModelRef(id,session?.modelId ?: "UNKNOWN"))
    }
    private fun validateRef(ref:ModelRef) {
        require(ref.providerId in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK))
        require(ref.modelId.matches(Regex("[A-Za-z0-9._:/-]{1,128}")))
    }
    @Synchronized fun sessions():List<SingleSessionInfo> {
        load();return journal.sessions.map {SingleSessionInfo(rowId(it),ModelRef(it.providerId,it.modelId),
            it.messages.firstOrNull {m ->m.role==MessageRole.USER}?.text?.take(80) ?: "新对话")}.reversed()
    }
    private fun rowId(s:ChatSession)=s.sessionId.ifEmpty {"legacy:${s.providerId}:${s.modelId}"}
    @Synchronized fun newSession(ref:ModelRef):List<ChatMessage> {
        load();validateRef(ref)
        val id=MessageIds.create()
        persist(journal.copy(activeProviderId=ref.providerId,activeModelId=ref.modelId,activeSessionId=id,
            sessions=journal.sessions+ChatSession(ref.providerId,ref.modelId,emptyList(),id)))
        return messages.toList()
    }
    @Synchronized fun activateSession(id:String):List<ChatMessage> {
        load();val s=journal.sessions.single {rowId(it)==id}
        persist(journal.copy(activeProviderId=s.providerId,activeModelId=s.modelId,activeSessionId=s.sessionId))
        return messages.toList()
    }
    @Synchronized fun load():List<ChatMessage> {
        if(!loaded) {
            var migrated=false
            journal=blob.read()?.let { sealed -> val plain=box.open(sealed)
                try {
                    val source=utf8(plain)
                    val root=json.parseToJsonElement(source)
                    if(root is JsonArray) {
                        migrated=true
                        ChatJournal(sessions=listOf(ChatSession(ProviderIds.CHATGPT,"UNKNOWN",json.decodeFromString<List<ChatMessage>>(source))))
                    } else {
                        require(root is JsonObject && root["version"]?.jsonPrimitive?.intOrNull==2)
                        require(root["sessions"] is JsonArray && root.containsKey("activeProviderId") && root.containsKey("activeModelId"))
                        json.decodeFromString<ChatJournal>(source)
                    }
                } finally { plain.fill(0) } } ?: ChatJournal()
            require(journal.version==2 && journal.sessions.size<=32 && journal.sessions.sumOf { it.messages.size }<=200)
            validateRef(ModelRef(journal.activeProviderId,journal.activeModelId))
            journal.sessions.forEach { validateRef(ModelRef(it.providerId,it.modelId)) }
            require(journal.sessions.all {it.sessionId.length<=128})
            require(journal.sessions.map { Triple(it.providerId,it.modelId,it.sessionId) }.distinct().size==journal.sessions.size)
            require(journal.sessions.map(::rowId).distinct().size==journal.sessions.size)
            require(journal.activeSessionId.isEmpty() || journal.sessions.any {it.sessionId==journal.activeSessionId && it.providerId==journal.activeProviderId && it.modelId==journal.activeModelId})
            journal.sessions.forEach {s ->s.messages.forEach {m ->require(m.reasoning.text.length<=ReasoningReader.MAX_CHARS)
                require(s.providerId!=ProviderIds.CHATGPT || m.reasoning.kind==ReasoningContent.Summary)} }
            var changed=migrated
            val recovered=journal.copy(sessions=journal.sessions.map { s -> s.copy(messages=s.messages.map {
                if(it.state in setOf(MessageState.PENDING,MessageState.STREAMING)) {
                    changed=true;ChatMessage(it.id,it.parentMessageId,it.role,it.text,MessageState.INCOMPLETE,
                        ReasoningRecord(it.reasoning.text,it.reasoning.kind,if(it.reasoning.phase==ReasoningPhase.Completed) ReasoningPhase.Completed
                            else if(it.reasoning.text.isEmpty()) ReasoningPhase.Unavailable else ReasoningPhase.Interrupted))
                } else it
            }) })
            if(changed) persist(recovered) else setJournal(recovered)
            loaded=true
        }
        return messages.toList()
    }
    @Synchronized fun begin(userText:String):Pair<String,List<LlmMessage>> {
        load(); require(userText.isNotBlank() && userText.length<=32768 && journal.sessions.sumOf { it.messages.size }<=196)
        val user=ChatMessage(MessageIds.create(),messages.lastOrNull()?.id,MessageRole.USER,userText,MessageState.COMPLETED)
        val assistant=ChatMessage(MessageIds.create(),user.id,MessageRole.ASSISTANT,"",MessageState.PENDING)
        // Do not silently include failed/incomplete assistant text or orphaned prior user messages.
        val history=messages.chunked(2).filter { it.size==2 && it[0].state==MessageState.COMPLETED && it[1].state==MessageState.COMPLETED }
            .flatten().map { LlmMessage(it.role,it.text) } + LlmMessage(MessageRole.USER,userText)
        save(messages+listOf(user,assistant)); return assistant.id to history
    }
    @Synchronized fun update(id:String,text:String,state:MessageState,reasoning:ReasoningRecord?=null):List<ChatMessage> {
        require(text.length<=4_194_304)
        save(messages.map { if(it.id==id) ChatMessage(it.id,it.parentMessageId,it.role,text,state,reasoning ?: it.reasoning) else it })
        return messages.toList()
    }
    private fun save(value:List<ChatMessage>) {
        val others=journal.sessions.filterNot { it.providerId==journal.activeProviderId && it.modelId==journal.activeModelId && it.sessionId==journal.activeSessionId }
        persist(journal.copy(sessions=others+ChatSession(journal.activeProviderId,journal.activeModelId,value,journal.activeSessionId)))
    }
    private fun setJournal(value:ChatJournal) {
        journal=value
        messages=value.sessions.firstOrNull { it.providerId==value.activeProviderId && it.modelId==value.activeModelId && it.sessionId==value.activeSessionId }?.messages ?: emptyList()
    }
    private fun persist(value:ChatJournal) {
        require(value.sessions.size<=32 && value.sessions.sumOf { it.messages.size }<=200)
        value.sessions.forEach {s ->s.messages.forEach {m ->require(m.reasoning.text.length<=ReasoningReader.MAX_CHARS)
            require(s.providerId!=ProviderIds.CHATGPT || m.reasoning.kind==ReasoningContent.Summary) } }
        val plain=json.encodeToString(value).toByteArray(Charsets.UTF_8)
        try { require(plain.size<=8_388_608); blob.write(box.seal(plain)); setJournal(value) } finally { plain.fill(0) }
    }
}
