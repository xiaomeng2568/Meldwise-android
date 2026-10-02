package io.github.xiaomeng2568.meldwise.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidAtomicBlob(file: File, private val maxBytes: Int = 1_048_576) : AtomicBlob {
    private val atomic = AtomicFile(file)
    @Synchronized override fun read(): ByteArray? {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()
            && !File(atomic.baseFile.path + ".new").exists()) return null
        return atomic.openRead().use { input ->
            val buffer = ByteArrayOutputStream()
            val block = ByteArray(4096)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                require(buffer.size() + count <= maxBytes) { "STORAGE_LIMIT" }
                buffer.write(block,0,count)
            }
            buffer.toByteArray()
        }
    }
    @Synchronized override fun write(value: ByteArray) {
        require(value.size <= maxBytes) { "STORAGE_LIMIT" }
        val stream = atomic.startWrite()
        try { stream.write(value); stream.fd.sync(); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }
}
class AndroidKey(private val alias: String) {
    @Synchronized fun get(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
class InstallationIdentity(private val blob: AtomicBlob) {
    @Synchronized fun load(): String {
        val existing = blob.read()?.let(::utf8)
        if (existing != null) {
            require(existing.matches(Regex("urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))) { "HOST_STORAGE_INVALID" }
            return existing
        }
        val created = "urn:uuid:${UUID.randomUUID()}"
        blob.write(created.toByteArray(Charsets.US_ASCII))
        require(blob.read()?.let(::utf8) == created) { "HOST_STORAGE_INVALID" }
        return created
    }
}
