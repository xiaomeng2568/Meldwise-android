package io.github.xiaomeng2568.meldwise

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xiaomeng2568.meldwise.security.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class KeystoreStorageTest {
    @Test fun actualKeystoreAndAtomicFileRoundTripNoProvider() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.noBackupFilesDir,"instrumentation-only-roundtrip.v1")
        val alias="meldwise.instrumentation-only.v1"
        try {
            val key=AndroidKey(alias);val box=AesGcmBox(key::get,"instrumentation-only")
            val blob=AndroidAtomicBlob(file)
            val plain="synthetic-local-test-only".toByteArray()
            blob.write(box.seal(plain));val first=blob.read()!!
            assertArrayEquals(plain,box.open(first));blob.write(box.seal(plain))
            assertFalse(first.contentEquals(blob.read()!!))
            val tampered=blob.read()!!;tampered[tampered.lastIndex]=(tampered.last()+1).toByte()
            assertThrows(Exception::class.java) {box.open(tampered)}
        } finally {
            // Only this test's named temporary file and key are removed.
            android.util.AtomicFile(file).delete()
            KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
        }
    }
}
