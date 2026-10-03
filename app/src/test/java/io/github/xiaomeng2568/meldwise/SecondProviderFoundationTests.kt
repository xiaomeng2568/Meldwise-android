package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.network.NetworkClient
import io.github.xiaomeng2568.meldwise.ui.modelLabel
import io.github.xiaomeng2568.meldwise.auth.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*
import javax.crypto.KeyGenerator

internal class MemoryBlob:AtomicBlob {
    var bytes:ByteArray?=null
    override fun read()=bytes?.copyOf()
    override fun write(value:ByteArray) {bytes=value.copyOf()}
}
internal fun testBox(purpose:String="synthetic-key"):AesGcmBox {
    val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey()
    return AesGcmBox({key},purpose,8_388_640)
}
class SecondProviderFoundationTests {
    private val marker="PRIVATE_SYNTHETIC_API_KEY"
    private fun fake(id:String)=object:LlmProvider {
        override val id=id;override val displayName=id
        override val capabilities=ProviderCapability(emptySet())
        override suspend fun listModels()=emptyList<LlmModel>()
        override suspend fun validateConnection()=ProviderStatus.READY
        override fun streamResponse(request:LlmRequest)=flowOf<LlmEvent>(LlmEvent.Completed(null))
    }
    @Test fun registryContainsBothExplicitProviders() {
        val registry=ProviderRegistry(listOf(fake("chatgpt"),fake("deepseek")))
        assertEquals(listOf("chatgpt","deepseek"),registry.all.map {it.id})
    }
    @Test fun sameModelNameDifferentProvidersAreDistinct() {
        assertNotEquals(ModelRef("chatgpt","same"),ModelRef("deepseek","same"))
    }
    @Test fun missingProviderHasNoFallback() {
        val registry=ProviderRegistry(listOf(fake("chatgpt")))
        assertThrows(ProviderFailure::class.java) {registry.get("deepseek")}
    }
    @Test fun duplicateProviderRejected() {
        assertThrows(IllegalArgumentException::class.java) {ProviderRegistry(listOf(fake("chatgpt"),fake("chatgpt")))}
    }
    @Test fun unknownProviderRejected() {
        assertThrows(IllegalArgumentException::class.java) {ProviderRegistry(listOf(fake("other")))}
    }
    @Test fun encryptedApiKeyRoundTrip() {
        val blob=MemoryBlob();val box=testBox();DeepSeekCredentials(blob,box).replace(marker)
        assertFalse(String(blob.bytes!!,Charsets.UTF_8).contains(marker))
        assertEquals(marker,DeepSeekCredentials(blob,box).read()!!.value)
    }
    @Test fun replaceKeyIsAtomicRecordAndReadback() {
        val blob=MemoryBlob();val box=testBox();val store=DeepSeekCredentials(blob,box)
        store.replace(marker);store.replace("synthetic-replacement")
        assertEquals("synthetic-replacement",DeepSeekCredentials(blob,box).read()!!.value)
    }
    @Test fun removalSurvivesReopen() {
        val blob=MemoryBlob();val box=testBox();val store=DeepSeekCredentials(blob,box)
        store.replace(marker);store.remove()
        assertNull(DeepSeekCredentials(blob,box).read());assertEquals(ApiKeyState.MISSING,store.state())
    }
    @Test fun corruptionFailsClosed() {
        val blob=MemoryBlob();val store=DeepSeekCredentials(blob,testBox());store.replace(marker)
        blob.bytes!![20]=(blob.bytes!![20].toInt() xor 1).toByte()
        assertEquals(ApiKeyState.UNAVAILABLE,store.state());assertThrows(ProviderFailure::class.java) {store.read()}
    }
    @Test fun replacementFailureHasNoPlaintextFallback() {
        val blob=MemoryBlob()
        val store=DeepSeekCredentials(blob,AesGcmBox({error("synthetic")},"synthetic"))
        assertThrows(ProviderFailure::class.java) {store.replace(marker)};assertNull(blob.bytes)
    }
    @Test fun oauthCredentialBlobUnaffectedByKeyOperations() {
        val oauth=MemoryBlob();oauth.write(byteArrayOf(3,4,5));val before=oauth.read()
        val blob=MemoryBlob();val store=DeepSeekCredentials(blob,testBox())
        store.replace(marker);store.replace("synthetic-other");store.remove();blob.bytes!![0]=0
        assertEquals(ApiKeyState.UNAVAILABLE,store.state());assertArrayEquals(before,oauth.read())
    }
    @Test fun purposeAndKeyIsolationRejectCrossReading() {
        val blob=MemoryBlob();DeepSeekCredentials(blob,testBox("deepseek")).replace(marker)
        assertEquals(ApiKeyState.UNAVAILABLE,DeepSeekCredentials(blob,testBox("oauth")).state())
    }
    @Test fun readbackFailureBlocksConfiguration() {
        val broken=object:AtomicBlob {override fun read():ByteArray?=null;override fun write(value:ByteArray) {}}
        assertThrows(ProviderFailure::class.java) {DeepSeekCredentials(broken,testBox()).replace(marker)}
    }
    @Test fun credentialsAndStateDoNotExportKey() {
        val store=DeepSeekCredentials(MemoryBlob(),testBox());store.replace(marker)
        assertFalse((store.toString()+store.read().toString()+store.state()).contains(marker))
    }
    @Test fun invalidKeysRejectedBeforeWrite() {
        listOf("","x\n","x y","密钥","x".repeat(4097)).forEach { key ->
            val blob=MemoryBlob();assertThrows(ProviderFailure::class.java) {DeepSeekCredentials(blob,testBox()).replace(key)};assertNull(blob.bytes)
        }
    }
    @Test fun oldJournalMigratesToChatgptUnknownWithoutLoss() {
        val blob=MemoryBlob();val box=testBox();val old=listOf(ChatMessage("old",null,MessageRole.USER,"synthetic-old",MessageState.COMPLETED))
        blob.write(box.seal(Json.encodeToString(old).toByteArray()))
        val repo=ChatRepository(blob,box);assertEquals("synthetic-old",repo.load().single().text)
        assertEquals(ModelRef("chatgpt","UNKNOWN"),repo.activeRef())
        assertEquals("synthetic-old",ChatRepository(blob,box).load().single().text)
    }
    @Test fun deepseekIdentityAndMessagesSurviveRestart() {
        val blob=MemoryBlob();val box=testBox();val repo=ChatRepository(blob,box)
        repo.activate(ModelRef("deepseek","catalog-model"));val (id,_)=repo.begin("synthetic input")
        repo.update(id,"synthetic answer",MessageState.COMPLETED)
        val restored=ChatRepository(blob,box);assertEquals(ModelRef("deepseek","catalog-model"),restored.activeRef())
        assertEquals(2,restored.load().size);assertEquals(MessageState.COMPLETED,restored.load().last().state)
    }
    @Test fun providerSwitchNeedsConversationLocalConsent() {
        val repo=ChatRepository(MemoryBlob(),testBox())
        repo.activate(ModelRef("chatgpt","same"));val (a,_)=repo.begin("synthetic private chatgpt")
        repo.update(a,"synthetic reply",MessageState.COMPLETED)
        repo.activate(ModelRef("deepseek","same"));val turn=repo.prepare("synthetic deepseek")
        assertTrue(turn.requiresSharing)
        assertThrows(ContextSharingRequired::class.java) {repo.beginPrepared(turn)}
        assertEquals(2,repo.load().size)
        assertEquals(3,repo.beginPrepared(turn,true).second.size)
    }
    @Test fun modelSwitchPreservesConversationAndOutputSnapshot() {
        val repo=ChatRepository(MemoryBlob(),testBox());val first=ModelRef("deepseek","first")
        repo.activate(first);val (id,_)=repo.begin("synthetic first");repo.update(id,"answer",MessageState.COMPLETED)
        assertEquals(2,repo.activate(ModelRef("deepseek","second")).size)
        assertEquals(first,repo.load().last().modelRef)
        assertEquals(3,repo.begin("next").second.size)
        assertEquals(1,repo.sessions().size)
    }
    @Test fun incompleteSessionRestoresTruthfully() {
        val blob=MemoryBlob();val box=testBox();val repo=ChatRepository(blob,box)
        repo.activate(ModelRef("deepseek","m"));repo.begin("synthetic")
        assertEquals(MessageState.INTERRUPTED,ChatRepository(blob,box).load().last().state)
    }
    @Test fun missingCredentialRetainsHistoryAndBlocksAdmission()=runBlocking {
        val blob=MemoryBlob();val box=testBox();val repo=ChatRepository(blob,box)
        repo.activate(ModelRef("deepseek","m"));repo.begin("synthetic")
        val network=NetworkClient();val p=DeepSeekProvider(DeepSeekCredentials(MemoryBlob(),testBox()),network)
        assertFalse(ProviderRegistry(listOf(fake("chatgpt"),p)).ready(repo.activeRef()))
        assertEquals(2,ChatRepository(blob,box).load().size);assertEquals(0,network.startedCallCount)
    }
    @Test fun savingRestoringRemovingAndCheckingKeyStartsNoNetwork()=runBlocking {
        val network=NetworkClient();val store=DeepSeekCredentials(MemoryBlob(),testBox());val p=DeepSeekProvider(store,network)
        store.replace(marker);assertEquals(ProviderStatus.READY,p.validateConnection());store.remove()
        assertEquals(ProviderStatus.DISCONNECTED,p.validateConnection());assertEquals(0,network.startedCallCount)
    }
    @Test fun affiliationLabelsDoNotChangeModelIds() {
        val ref=ModelRef("chatgpt","gpt-synthetic")
        assertEquals("ChatGPT-5.5",modelLabel(ref,"GPT-5.5"));assertEquals("gpt-synthetic",ref.modelId)
        assertEquals("DeepSeek-V4.1",modelLabel(ModelRef("deepseek","synthetic"),"DeepSeek-V4.1"))
    }
    @Test fun actualEncryptedOauthRecordUnaffected() {
        val blob=MemoryBlob();val oauth=EncryptedCredentialStore(blob,testBox("oauth"))
        oauth.write(StoredSession(Registration(Siwc.ISSUER,Secret("synthetic-client"),Secret("synthetic-subject"),"synthetic-host"),
            CredentialSet(Secret("synthetic-access"),Secret("synthetic-refresh"),Secret("synthetic-id"),1000000,Scopes.requested,0),1,CredentialPhase.ACTIVE))
        val before=blob.read()
        val dsBlob=MemoryBlob();val ds=DeepSeekCredentials(dsBlob,testBox("deepseek"));ds.replace(marker);ds.remove();dsBlob.bytes!![0]=0
        assertEquals(ApiKeyState.UNAVAILABLE,ds.state());assertEquals("synthetic-access",oauth.read()!!.credentials!!.accessToken.value)
        assertArrayEquals(before,blob.read())
    }
    @Test fun oldMigrationPreservesCancelledPartialText() {
        val blob=MemoryBlob();val box=testBox()
        val old=listOf(ChatMessage("a",null,MessageRole.USER,"synthetic",MessageState.COMPLETED),
            ChatMessage("b","a",MessageRole.ASSISTANT,"synthetic partial",MessageState.CANCELLED))
        blob.write(box.seal(Json.encodeToString(old).toByteArray()))
        val repo=ChatRepository(blob,box);assertEquals(MessageState.CANCELLED,repo.load().last().state)
        assertEquals("synthetic partial",repo.load().last().text)
    }
    @Test fun corruptJournalFailsClosedWithoutDeletingCiphertext() {
        val blob=MemoryBlob();val box=testBox();ChatRepository(blob,box).begin("synthetic")
        blob.bytes!![20]=(blob.bytes!![20].toInt() xor 1).toByte();val before=blob.read()
        assertThrows(Exception::class.java) {ChatRepository(blob,box).load()};assertArrayEquals(before,blob.read())
    }
    @Test fun unknownProviderCannotOwnConversation() {
        val repo=ChatRepository(MemoryBlob(),testBox())
        assertThrows(IllegalArgumentException::class.java) {repo.activate(ModelRef("unregistered","m"))}
    }
    @Test fun malformedEnvelopeCannotEraseExistingBlob() {
        val blob=MemoryBlob();val box=testBox();blob.write(box.seal("{}".toByteArray()));val before=blob.read()
        assertThrows(Exception::class.java) {ChatRepository(blob,box).load()};assertArrayEquals(before,blob.read())
    }
    @Test fun failedMigrationWriteLeavesOldRecordReadable() {
        val box=testBox();val old=listOf(ChatMessage("old",null,MessageRole.USER,"synthetic-old",MessageState.COMPLETED))
        val ciphertext=box.seal(Json.encodeToString(old).toByteArray())
        val broken=object:AtomicBlob {override fun read()=ciphertext.copyOf();override fun write(value:ByteArray) {error("synthetic storage failure")}}
        assertThrows(Exception::class.java) {ChatRepository(broken,box).load()}
        assertEquals("synthetic-old",Json.decodeFromString<List<ChatMessage>>(utf8(box.open(broken.read()))).single().text)
    }
    @Test fun restoredProviderSelectionDoesNotSwitchToCredentialedAlternative()=runBlocking {
        val blob=MemoryBlob();val box=testBox();val repo=ChatRepository(blob,box)
        repo.activate(ModelRef("deepseek","m"));repo.begin("synthetic")
        val restored=ChatRepository(blob,box);val p=DeepSeekProvider(DeepSeekCredentials(MemoryBlob(),testBox()),NetworkClient())
        val registry=ProviderRegistry(listOf(fake("chatgpt"),p))
        assertEquals("deepseek",restored.activeRef().providerId);assertFalse(registry.ready(restored.activeRef()))
        assertEquals(2,restored.load().size)
    }
    @Test fun failedSelectedProviderNeverInvokesOtherProvider()=runBlocking {
        var alternateCalls=0
        val other=object:LlmProvider by fake("chatgpt") {
            override fun streamResponse(request:LlmRequest):Flow<LlmEvent> {alternateCalls++;return flowOf(LlmEvent.Completed(null))}
        }
        val ds=DeepSeekProvider(DeepSeekCredentials(MemoryBlob(),testBox()),NetworkClient())
        val registry=ProviderRegistry(listOf(other,ds))
        val events=registry.get("deepseek").streamResponse(LlmRequest("synthetic",listOf(LlmMessage(MessageRole.USER,"synthetic")))).toList()
        assertTrue(events.single() is LlmEvent.Failed);assertEquals(0,alternateCalls)
    }
}
