package io.github.xiaomeng2568.meldwise.security

import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable private class ApiKeyRecord(val version:Int=1,val key:String?=null)
class ApiCredential internal constructor(internal val value:String) {
    override fun toString()="ApiCredential([REDACTED])"
}
enum class ApiKeyState { MISSING, CONFIGURED, UNAVAILABLE }

/** Own encrypted file, alias and AAD. No OAuth record or refresh semantics. */
class DeepSeekCredentials(private val blob:AtomicBlob,private val box:AesGcmBox) {
    @Synchronized fun state():ApiKeyState = try {
        if(read()==null) ApiKeyState.MISSING else ApiKeyState.CONFIGURED
    } catch(_:Exception) { ApiKeyState.UNAVAILABLE }
    @Synchronized fun read():ApiCredential? = guarded {
        val encrypted=blob.read() ?: return@guarded null
        val plain=box.open(encrypted)
        val record=try { Json.decodeFromString<ApiKeyRecord>(utf8(plain)) } finally { plain.fill(0) }
        require(record.version==1)
        record.key?.let { validate(it);ApiCredential(it) }
    }
    @Synchronized fun replace(key:String) = guarded { validate(key);write(key) }
    // An authenticated atomic tombstone removes the key without touching other credentials.
    @Synchronized fun remove() = guarded { write(null) }
    private fun write(key:String?) {
        val plain=Json.encodeToString(ApiKeyRecord(key=key)).toByteArray(Charsets.UTF_8)
        val sealed=try { require(plain.size<=8192-29);box.seal(plain) } finally { plain.fill(0) }
        blob.write(sealed)
        require(read()?.value==key)
    }
    private fun validate(key:String) { require(key.length in 1..4096 && key.all { it.code in 33..126 }) }
    private inline fun <T> guarded(block:()->T):T = try { block() }
        catch(_:Exception) { throw ProviderFailure(LlmError(ErrorKind.STORAGE)) }
    override fun toString()="DeepSeekCredentials([REDACTED])"
}
