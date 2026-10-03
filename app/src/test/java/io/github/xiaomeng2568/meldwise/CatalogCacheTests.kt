package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CatalogCacheTests {
    private val model=LlmModel("real-returned-id","Returned name","provider-catalog",ProviderCapability(setOf(Capability.STREAMING)))
    private val catalogBox=testBox("catalog")
    private fun cache(blob:MemoryBlob=MemoryBlob())=ModelCatalogCache(blob,catalogBox)
    private fun provider(providerId:String="deepseek",status:ProviderStatus=ProviderStatus.READY,load:suspend ()->List<LlmModel> = {listOf(model)}):LlmProvider=object:LlmProvider {
        override val id=providerId;override val displayName=providerId;override val capabilities=ProviderCapability(emptySet())
        override suspend fun validateConnection()=status
        override suspend fun listModels()=load()
        override fun streamResponse(request:LlmRequest):Flow<LlmEvent> = error("INFERENCE_MUST_NOT_START")
    }
    @Test fun encryptedCatalogRoundtripPreservesModelIdentityAndCapabilities() {
        val blob=MemoryBlob();val c=cache(blob);c.save("deepseek",listOf(model),42)
        assertFalse(String(blob.bytes!!).contains(model.id));val entry=cache(blob).load().getValue("deepseek")
        assertEquals(model,entry.models.single());assertEquals(42L,entry.updatedAt);assertEquals("deepseek",entry.providerId)
    }
    @Test fun providerCachesAreIndependent() {val c=cache();c.save("chatgpt",listOf(model),1);c.save("deepseek",listOf(model),2);c.remove("deepseek");assertEquals(setOf("chatgpt"),c.load().keys)}
    @Test fun emptyCatalogRequiresRefresh() {assertTrue(CatalogSnapshot("deepseek",100,emptyList()).needsRefresh(100))}
    @Test fun freshCatalogDoesNotRefresh() {assertFalse(CatalogSnapshot("deepseek",100,listOf(model)).needsRefresh(101))}
    @Test fun exactExpiryRequiresRefresh() {assertTrue(CatalogSnapshot("deepseek",100,listOf(model)).needsRefresh(100+CatalogSnapshot.MAX_AGE_MS))}
    @Test fun clockRollbackDoesNotMakeCatalogPermanentlyFresh() {assertTrue(CatalogSnapshot("deepseek",100,listOf(model)).needsRefresh(99))}
    @Test fun duplicateAndMalformedModelsRejectedBeforePersisting() {
        val c=cache();assertThrows(IllegalArgumentException::class.java) {c.save("deepseek",listOf(model,model),1)}
        assertThrows(IllegalArgumentException::class.java) {c.save("deepseek",listOf(model.copy(id="bad id")),1)}
        assertThrows(IllegalArgumentException::class.java) {c.save("deepseek",listOf(model.copy(displayName="a\nb")),1)}
        assertTrue(c.load().isEmpty())
    }
    @Test fun modelCountBoundRetained() {assertThrows(IllegalArgumentException::class.java) {cache().save("deepseek",(0..1024).map {model.copy(id="m$it")},1)}}
    @Test fun combinedCatalogByteBoundRejectsOversizeWithoutReplacingOldCache() {
        val c=cache();val many=(0 until 1024).map {model.copy(id="m$it",displayName="模".repeat(256))}
        c.save("chatgpt",many,1)
        assertThrows(IllegalArgumentException::class.java) {c.save("deepseek",many,2)}
        assertEquals(setOf("chatgpt"),c.load().keys);assertEquals(1024,c.load().getValue("chatgpt").models.size)
    }
    @Test fun forgedOriginIsNotAnAcceptedCache() {assertThrows(IllegalArgumentException::class.java) {cache().save("deepseek",listOf(model.copy(origin="hardcoded")),1)}}
    @Test fun corruptCacheFailsClosed() {val blob=MemoryBlob();val c=cache(blob);c.save("deepseek",listOf(model),1);blob.bytes!![10]=(blob.bytes!![10].toInt() xor 1).toByte();assertThrows(Exception::class.java) {cache(blob).load()}}
    @Test fun restoreImmediatelyMakesFreshCacheUsableWithoutNetwork()=runBlocking {
        val c=cache();c.save("deepseek",listOf(model),100);val calls=AtomicInteger();var hydrated:List<LlmModel>?=null
        val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider(load={calls.incrementAndGet();listOf(model)}))),c,hydrate={_,m->hydrated=m},now={101})
        loader.restore();assertEquals(listOf(model),loader.state.value.catalogs["deepseek"]);assertEquals(listOf(model),hydrated)
        loader.refreshStaleOnce();assertEquals(0,calls.get())
    }
    @Test fun staleCacheIsUsableWhileRefreshIsInFlight()=runBlocking {
        val c=cache();c.save("deepseek",listOf(model),0);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider(load={entered.complete(Unit);release.await();listOf(model.copy(id="new"))}))),c,now={CatalogSnapshot.MAX_AGE_MS})
        loader.restore();val job=launch {loader.refreshStaleOnce()};withTimeout(5000) {entered.await()}
        assertEquals(listOf(model),loader.state.value.catalogs["deepseek"]);assertTrue("deepseek" in loader.state.value.loading)
        release.complete(Unit);job.join();assertEquals("new",loader.state.value.catalogs.getValue("deepseek").single().id)
    }
    @Test fun failedRefreshKeepsOldCacheAndReportsSafeHttp403()=runBlocking {
        val c=cache();c.save("deepseek",listOf(model),0);val calls=AtomicInteger()
        val p=provider(load={calls.incrementAndGet();throw ProviderFailure(LlmError(ErrorKind.AUTHORIZATION))})
        val diag=ModelCatalogDiagnostic(httpStatus=403,failureCategory=CatalogFailure.AUTHORIZATION)
        val loader=ModelCatalogLoader(ProviderRegistry(listOf(p)),c,diagnostic={diag},now={CatalogSnapshot.MAX_AGE_MS})
        loader.restore();loader.refreshStaleOnce();loader.refreshStaleOnce()
        assertEquals(1,calls.get());assertEquals(listOf(model),loader.state.value.catalogs["deepseek"]);assertEquals(listOf(model),c.load().getValue("deepseek").models)
        assertEquals("HTTP 403 · 权限不足",loader.state.value.notices.single().detail)
    }
    @Test fun serverFailureDoesNotClearCachedModels()=runBlocking {
        val c=cache();c.save("deepseek",listOf(model),0)
        val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider(load={throw ProviderFailure(LlmError(ErrorKind.SERVER))}))),c,now={CatalogSnapshot.MAX_AGE_MS})
        loader.restore();loader.refreshStaleOnce();assertEquals(listOf(model),loader.state.value.catalogs["deepseek"])
    }
    @Test fun missingCredentialsNeverLoadCatalog()=runBlocking {
        val calls=AtomicInteger();val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider(status=ProviderStatus.DISCONNECTED,load={calls.incrementAndGet();listOf(model)}))),cache())
        loader.restore();loader.refreshStaleOnce();assertEquals(0,calls.get());assertTrue(loader.state.value.notices.isEmpty())
    }
    @Test fun twoConfiguredProvidersLoadIndependently()=runBlocking {
        val a=AtomicInteger();val b=AtomicInteger()
        val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider("chatgpt",load={a.incrementAndGet();throw ProviderFailure(LlmError(ErrorKind.NETWORK))}),provider(load={b.incrementAndGet();listOf(model)}))),cache())
        loader.restore();loader.refreshStaleOnce();assertEquals(1,a.get());assertEquals(1,b.get());assertEquals(setOf("deepseek"),loader.state.value.catalogs.keys)
    }
    @Test fun manualAndStartupLoadsShareOneAttempt()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider(load={calls.incrementAndGet();entered.complete(Unit);release.await();listOf(model)}))),cache())
        val job=launch {loader.load("deepseek")};withTimeout(5000) {entered.await()};loader.load("deepseek");assertEquals(1,calls.get())
        release.complete(Unit);job.join()
    }
    @Test fun credentialInvalidationDiscardsLateOldCatalog()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val c=cache();var hydrated=emptyList<LlmModel>()
        val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider(load={entered.complete(Unit);release.await();listOf(model)}))),c,hydrate={_,m->hydrated=m})
        val job=launch {loader.load("deepseek")};withTimeout(5000) {entered.await()};loader.invalidate("deepseek")
        release.complete(Unit);job.join();assertTrue(loader.state.value.catalogs.isEmpty());assertTrue(c.load().isEmpty());assertTrue(hydrated.isEmpty());assertTrue(loader.state.value.notices.isEmpty())
    }
    @Test fun cancellationNeverErasesCacheOrRetries()=runBlocking {
        val c=cache();c.save("deepseek",listOf(model),1);val entered=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider(load={calls.incrementAndGet();entered.complete(Unit);awaitCancellation()}))),c)
        loader.restore();val job=launch {loader.load("deepseek")};withTimeout(5000) {entered.await()};job.cancelAndJoin()
        assertEquals(1,calls.get());assertEquals(listOf(model),c.load().getValue("deepseek").models);assertTrue(loader.state.value.loading.isEmpty())
    }
    @Test fun arbitraryExceptionMessageDoesNotReachBanner()=runBlocking {
        val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider(load={error("SECRET_API_KEY Authorization raw body")}))),cache())
        loader.load("deepseek");val text=loader.state.value.notices.single().toString()
        assertFalse(text.contains("SECRET_API_KEY"));assertFalse(text.contains("Authorization"));assertFalse(text.contains("raw body"))
    }
    @Test fun successNoticeCanBeDismissedWithoutAnotherRequest()=runBlocking {
        val calls=AtomicInteger();val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider(load={calls.incrementAndGet();listOf(model)}))),cache())
        loader.load("deepseek");assertEquals(CatalogNoticeKind.Success,loader.state.value.notices.single().kind)
        loader.dismiss(loader.state.value.notices.single().id);assertTrue(loader.state.value.notices.isEmpty());assertEquals(1,calls.get())
    }
    @Test fun failedCacheWriteKeepsUsableAdmissionListAndOldDiskCache()=runBlocking {
        val backing=MemoryBlob();var fail=false
        val blob=object:io.github.xiaomeng2568.meldwise.security.AtomicBlob {
            override fun read()=backing.read()
            override fun write(value:ByteArray) {if(fail) error("SYNTHETIC_DISK_FAILURE");backing.write(value)}
        }
        val c=ModelCatalogCache(blob,catalogBox);c.save("deepseek",listOf(model),1);var admitted=emptyList<LlmModel>()
        val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider(load={listOf(model.copy(id="new"))}))),c,hydrate={_,m->admitted=m})
        loader.restore();fail=true;loader.load("deepseek")
        assertEquals(listOf(model),admitted);assertEquals(listOf(model),loader.state.value.catalogs["deepseek"])
        assertEquals(listOf(model),c.load().getValue("deepseek").models);assertEquals(ErrorKind.STORAGE,loader.state.value.notices.single().error)
    }
    @Test fun corruptMetadataIsDiscardedAndStartupCanRefreshOnce()=runBlocking {
        val blob=MemoryBlob();val c=cache(blob);c.save("deepseek",listOf(model),1);blob.bytes!![10]=(blob.bytes!![10].toInt() xor 1).toByte()
        val calls=AtomicInteger();val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider(load={calls.incrementAndGet();listOf(model)}))),c)
        loader.restore();assertTrue(loader.state.value.catalogs.isEmpty());assertEquals(CatalogNoticeKind.Warning,loader.state.value.notices.single().kind)
        loader.refreshStaleOnce();assertEquals(1,calls.get());assertEquals(listOf(model),c.load().getValue("deepseek").models)
    }
    @Test fun missingConnectionDoesNotReuseHistoricalHttpStatus()=runBlocking {
        val loader=ModelCatalogLoader(ProviderRegistry(listOf(provider(status=ProviderStatus.DISCONNECTED))),cache(),
            diagnostic={ModelCatalogDiagnostic(httpStatus=200)})
        loader.load("deepseek");val notice=loader.state.value.notices.single()
        assertNull(notice.diagnostic);assertFalse(notice.detail!!.contains("HTTP"));assertEquals(ErrorKind.AUTHENTICATION,notice.error)
    }
    @Test fun oversizedEncryptedReadIsRejectedBeforeDecrypting() {
        val blob=MemoryBlob();blob.bytes=ByteArray(ModelCatalogCache.MAX_BYTES+30)
        assertThrows(IllegalArgumentException::class.java) {cache(blob).load()}
    }
}
