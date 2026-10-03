package io.github.xiaomeng2568.meldwise

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xiaomeng2568.meldwise.security.*
import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class KeystoreStorageTest {
    private fun fixture(generation:Long)=StoredSession(
        Registration(Siwc.ISSUER,Secret("synthetic-client"),Secret("synthetic-subject"),"synthetic-host"),
        CredentialSet(Secret("synthetic-access-$generation"),Secret("synthetic-refresh-$generation"),
            Secret("synthetic-id"),1000000,Scopes.requested,0),generation,CredentialPhase.ACTIVE)
    private fun isolated(name:String,block:(AndroidAtomicBlob,AndroidKey)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.noBackupFilesDir,"instrumentation-only-$name.v1")
        val alias="meldwise.instrumentation-only.$name.v1"
        try { block(AndroidAtomicBlob(file),AndroidKey(alias)) }
        finally {
            android.util.AtomicFile(file).delete()
            KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
        }
    }
    @Test fun encryptedCredentialRecordRoundTripAndCorruptionFailClosed()=isolated("record") {blob,key ->
        val box=AesGcmBox(key::get,"instrumentation-only-record")
        val store=EncryptedCredentialStore(blob,box)
        assertNull(store.read());store.write(fixture(1))
        val first=requireNotNull(blob.read())
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("synthetic-access"))
        assertTrue(store.read()?.generation==1L)
        store.write(fixture(2))
        val reopened=EncryptedCredentialStore(blob,AesGcmBox(AndroidKey("meldwise.instrumentation-only.record.v1")::get,"instrumentation-only-record"))
        val restored=requireNotNull(reopened.read())
        assertTrue(restored.generation==2L && restored.phase==CredentialPhase.ACTIVE)
        assertTrue(restored.credentials?.accessToken?.value=="synthetic-access-2")
        assertFalse(first.contentEquals(requireNotNull(blob.read())))
        val corrupted=requireNotNull(blob.read());corrupted[corrupted.lastIndex]=(corrupted.last()+1).toByte()
        blob.write(corrupted)
        assertTrue(assertThrows(AuthFailure::class.java) {reopened.read()}.reason==AuthReason.STORAGE_UNAVAILABLE)
    }
    @Test fun keyFailureCannotPersistPlaintext()=isolated("key-failure") {blob,_ ->
        val store=EncryptedCredentialStore(blob,AesGcmBox({throw IllegalStateException("SYNTHETIC_KEY_UNAVAILABLE")},"instrumentation-only-key-failure"))
        assertTrue(assertThrows(AuthFailure::class.java) {store.write(fixture(1))}.reason==AuthReason.STORAGE_UNAVAILABLE)
        assertNull(blob.read())
    }
    @Test fun encryptedChatReopenPreservesCompletionAndRecoversPartialState()=isolated("journal") {blob,key ->
        fun repository()=ChatRepository(blob,AesGcmBox(key::get,"instrumentation-only-journal"))
        val first=repository();val completed=first.begin("synthetic-local-input")
        first.update(completed.first,"synthetic-local-output",MessageState.COMPLETED)
        val partial=first.begin("synthetic-local-second-input")
        first.update(partial.first,"synthetic-local-partial",MessageState.STREAMING)
        val reopened=repository().load()
        assertTrue(reopened.size==4 && reopened[1].state==MessageState.COMPLETED && reopened[3].state==MessageState.INTERRUPTED)
        assertFalse(requireNotNull(blob.read()).toString(Charsets.ISO_8859_1).contains("synthetic-local"))
    }
    @Test fun oldJournalMigratesOnActualKeystoreWithoutProvider()=isolated("conversation-migration") {blob,key ->
        val box=AesGcmBox(key::get,"instrumentation-only-conversation")
        val legacy="""{"version":2,"activeProviderId":"chatgpt","activeModelId":"synthetic","activeSessionId":"legacy","sessions":[{"providerId":"chatgpt","modelId":"synthetic","sessionId":"legacy","messages":[{"id":"u","parentMessageId":null,"role":"USER","text":"synthetic old","state":"COMPLETED"},{"id":"a","parentMessageId":"u","role":"ASSISTANT","text":"synthetic answer","state":"COMPLETED"}]}]}"""
        blob.write(box.seal(legacy.toByteArray()))
        val r=ChatRepository(blob,box)
        assertEquals(2,r.load().size);assertEquals("legacy",r.conversationId())
        val next=r.begin("synthetic next");r.update(next.first,"next answer",MessageState.COMPLETED)
        val restored=ChatRepository(blob,box);assertEquals(4,restored.load().size);assertEquals(1,restored.sessions().size)
        val bytes=blob.read();restored.load();assertArrayEquals(bytes,blob.read())
    }
    @Test fun elevenLocalTurnsRestoreAsOneEncryptedConversation()=isolated("conversation-turns") {blob,key ->
        val box=AesGcmBox(key::get,"instrumentation-only-conversation-turns")
        val r=ChatRepository(blob,box)
        repeat(10) {val id=r.begin("synthetic question $it").first;r.update(id,"synthetic answer",MessageState.COMPLETED)}
        val conversationId=r.conversationId();val restored=ChatRepository(blob,box)
        assertEquals(20,restored.load().size);val id=restored.begin("synthetic turn 11").first;restored.update(id,"answer 11",MessageState.COMPLETED)
        assertEquals(conversationId,restored.conversationId());assertEquals(22,restored.load().size);assertEquals(1,restored.sessions().size)
        assertFalse(blob.read()!!.toString(Charsets.ISO_8859_1).contains("synthetic question"))
    }
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
    @Test fun collaborateStagesRestoreWithActualKeystoreAndNoRequests()=isolated("collaborate") {blob,key ->
        val box=AesGcmBox(key::get,"instrumentation-only-collaborate")
        val a=io.github.xiaomeng2568.meldwise.provider.ModelRef("chatgpt","synthetic-a")
        val b=io.github.xiaomeng2568.meldwise.provider.ModelRef("deepseek","synthetic-b")
        val r=ChatRepository(blob,box);r.newCollaborate();r.configureCollaborate(CollaborateConfig(CollaborateModel(a),CollaborateModel(b)))
        val round=r.beginCollaborate(r.prepareCollaborate("synthetic prompt"),true).rounds.single()
        val initial=r.startCollaborateStage(round.roundId,0).rounds.last().stages[0]
        r.updateCollaborateStage(round.roundId,initial.copy(state=CollaborateStageState.Complete,output="synthetic visible answer"))
        val review=r.startCollaborateStage(round.roundId,1).rounds.last().stages[1]
        r.updateCollaborateStage(round.roundId,review.copy(output="synthetic partial review"))
        val restored=ChatRepository(blob,box);restored.load()
        val recovered=restored.activeConversation()!!.rounds.single()
        assertEquals(CollaborateRoundState.Interrupted,recovered.lifecycle)
        assertEquals(CollaborateStageState.Complete,recovered.stages[0].state)
        assertEquals("synthetic visible answer",recovered.stages[0].output)
        assertEquals(CollaborateStageState.Interrupted,recovered.stages[1].state)
        assertEquals(CollaborateStageState.NotRun,recovered.stages[2].state)
        assertEquals(1,restored.sessions().size)
        assertFalse(blob.read()!!.toString(Charsets.ISO_8859_1).contains("synthetic visible"))
    }
    @Test fun deepseekKeyReplaceRemoveAndCorruptionNoProvider()=isolated("deepseek") {blob,key ->
        fun store()=DeepSeekCredentials(blob,AesGcmBox(key::get,"instrumentation-only-deepseek"))
        store().replace("synthetic-deepseek-key")
        assertEquals(ApiKeyState.CONFIGURED,store().state())
        assertFalse(requireNotNull(blob.read()).toString(Charsets.ISO_8859_1).contains("synthetic-deepseek-key"))
        store().replace("synthetic-deepseek-replacement");assertEquals(ApiKeyState.CONFIGURED,store().state())
        store().remove();assertEquals(ApiKeyState.MISSING,store().state())
        store().replace("synthetic-deepseek-key")
        val corrupted=requireNotNull(blob.read());corrupted[20]=(corrupted[20].toInt() xor 1).toByte();blob.write(corrupted)
        assertEquals(ApiKeyState.UNAVAILABLE,store().state())
    }
}
