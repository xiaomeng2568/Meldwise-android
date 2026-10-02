package io.github.xiaomeng2568.meldwise.security

import io.github.xiaomeng2568.meldwise.auth.AuthFailure
import io.github.xiaomeng2568.meldwise.auth.AuthReason
import io.github.xiaomeng2568.meldwise.auth.StoredSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface AtomicBlob {
    fun read(): ByteArray?
    fun write(value: ByteArray)
}
interface CredentialStore { fun read(): StoredSession?; fun write(value: StoredSession) }

/** AES-GCM uses a new provider-generated nonce for every write. All persisted bytes are ciphertext. */
class AesGcmBox(private val key: () -> SecretKey, private val purpose: String, private val maxBytes:Int=1_048_576) {
    private val aad get() = purpose.toByteArray(Charsets.UTF_8)
    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        require(cipher.iv.size == 12) { "INVALID_GCM_NONCE" }
        cipher.updateAAD(aad)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }
    fun open(encrypted: ByteArray): ByteArray {
        require(encrypted.size in 29..maxBytes && encrypted[0] == 1.toByte()) { "INVALID_ENCRYPTED_ENVELOPE" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(1,13)))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted,13,encrypted.size-13)
    }
}
class EncryptedCredentialStore(private val blob: AtomicBlob, private val box: AesGcmBox) : CredentialStore {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }
    @Synchronized override fun read(): StoredSession? = guarded {
        val bytes = blob.read() ?: return@guarded null
        val plain = box.open(bytes)
        try { json.decodeFromString<StoredSession>(utf8(plain)) } finally { plain.fill(0) }
    }
    @Synchronized override fun write(value: StoredSession) = guarded {
        val plain = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        val encrypted = try { box.seal(plain) } finally { plain.fill(0) }
        blob.write(encrypted)
        // AtomicFile completion is not enough: verify decryption and the complete record before success.
        val restored = read() ?: throw AuthFailure(AuthReason.STORAGE_UNAVAILABLE)
        if (json.encodeToString(restored) != json.encodeToString(value)) throw AuthFailure(AuthReason.STORAGE_UNAVAILABLE)
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (_: Exception) { throw AuthFailure(AuthReason.STORAGE_UNAVAILABLE) }
}
internal fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()
