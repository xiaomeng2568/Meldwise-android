package io.github.xiaomeng2568.meldwise.data

import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.security.SecureRandom

@Serializable enum class MessageState { PENDING, STREAMING, COMPLETED, INCOMPLETE, CANCELLED, FAILED }
@Serializable class ChatMessage(val id:String,val parentMessageId:String?,val role:MessageRole,
    val text:String,val state:MessageState) {
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
/** Small encrypted single-conversation journal; no process-restart request replay. */
class ChatRepository(private val blob:AtomicBlob,private val box:AesGcmBox) {
    private val json=Json
    private var messages=emptyList<ChatMessage>()
    private var loaded=false
    @Synchronized fun load():List<ChatMessage> {
        if(!loaded) {
            messages=blob.read()?.let { sealed -> val plain=box.open(sealed)
                try { json.decodeFromString<List<ChatMessage>>(utf8(plain)) } finally { plain.fill(0) } } ?: emptyList()
            require(messages.size<=200)
            val recovered=messages.map { if(it.state in setOf(MessageState.PENDING,MessageState.STREAMING))
                ChatMessage(it.id,it.parentMessageId,it.role,it.text,MessageState.INCOMPLETE) else it }
            if(recovered.any { it.state==MessageState.INCOMPLETE } && recovered!=messages) save(recovered)
            loaded=true
        }
        return messages.toList()
    }
    @Synchronized fun begin(userText:String):Pair<String,List<LlmMessage>> {
        load(); require(userText.isNotBlank() && userText.length<=32768 && messages.size<=196)
        val user=ChatMessage(MessageIds.create(),messages.lastOrNull()?.id,MessageRole.USER,userText,MessageState.COMPLETED)
        val assistant=ChatMessage(MessageIds.create(),user.id,MessageRole.ASSISTANT,"",MessageState.PENDING)
        // Do not silently include failed/incomplete assistant text or orphaned prior user messages.
        val history=messages.chunked(2).filter { it.size==2 && it[0].state==MessageState.COMPLETED && it[1].state==MessageState.COMPLETED }
            .flatten().map { LlmMessage(it.role,it.text) } + LlmMessage(MessageRole.USER,userText)
        save(messages+listOf(user,assistant)); return assistant.id to history
    }
    @Synchronized fun update(id:String,text:String,state:MessageState):List<ChatMessage> {
        require(text.length<=4_194_304)
        save(messages.map { if(it.id==id) ChatMessage(it.id,it.parentMessageId,it.role,text,state) else it })
        return messages.toList()
    }
    private fun save(value:List<ChatMessage>) {
        val plain=json.encodeToString(value).toByteArray(Charsets.UTF_8)
        try { require(plain.size<=8_388_608); blob.write(box.seal(plain)); messages=value } finally { plain.fill(0) }
    }
}
