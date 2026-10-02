package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.security.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*

class DeepSeekCatalogTests {
    private val marker="PRIVATE_SYNTHETIC_KEY"
    private val model="""{"object":"model","id":"synthetic-model","name":"Synthetic","input_modalities":["text"],"output_modalities":["text"]}"""
    private fun body(item:String=model)="""{"object":"list","data":[$item]}"""
    private fun provider()=DeepSeekProvider(DeepSeekCredentials(MemoryBlob(),testBox()),NetworkClient())
    private fun invalid(body:String) {assertThrows(CatalogParseFailure::class.java) {provider().parseModels(body)}}
    @Test fun validDataCatalog() {assertEquals("synthetic-model",provider().parseModels(body()).single().id)}
    @Test fun returnedDisplayName() {assertEquals("Synthetic",provider().parseModels(body()).single().displayName)}
    @Test fun absentNameUsesId() {assertEquals("synthetic-model",provider().parseModels(body(model.replace(",\"name\":\"Synthetic\"",""))).single().displayName)}
    @Test fun malformedJson() {invalid("{bad")}
    @Test fun absentDataArray() {invalid("""{"object":"list","models":[]}""")}
    @Test fun wrongTopObject() {invalid(body().replace("\"list\"","\"other\""))}
    @Test fun wrongModelObject() {invalid(body(model.replace("\"model\"","\"other\"")))}
    @Test fun duplicateIds() {invalid(body("$model,$model"))}
    @Test fun invalidId() {invalid(body(model.replace("synthetic-model","invalid id")))}
    @Test fun emptyNameRejected() {invalid(body(model.replace("Synthetic","")))}
    @Test fun nonStringNameRejected() {invalid(body(model.replace("\"Synthetic\"","4")))}
    @Test fun oversizedIdRejected() {invalid(body(model.replace("synthetic-model","a".repeat(129))))}
    @Test fun itemCountBounded() {invalid(body(List(1025) {model}.joinToString(",")))}
    @Test fun textIneligibleModelsExcluded() {assertTrue(provider().parseModels(body(model.replace("[\"text\"]","[\"image\"]"))).isEmpty())}
    @Test fun absentOptionalModalitiesUseDocumentedTextBaseline() {assertEquals(1,provider().parseModels("""{"object":"list","data":[{"object":"model","id":"synthetic"}]}""").size)}
    @Test fun malformedModalitiesRejected() {invalid(body(model.replace("[\"text\"]","2")))}
    @Test fun providerOrderingPreserved() {
        assertEquals(listOf("z","a"),provider().parseModels(body(model.replace("synthetic-model","z")+","+model.replace("synthetic-model","a"))).map {it.id})
    }
    private fun http(status:Int,body:String,expected:ErrorKind?)=runBlocking {
        MockWebServer().use { s ->s.start();s.enqueue(MockResponse().setResponseCode(status).setBody(body))
            val key=DeepSeekCredentials(MemoryBlob(),testBox());key.replace(marker);val network=NetworkClient(allowLocalTestHttp=true)
            val p=DeepSeekProvider(key,network,s.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'))
            if(expected==null) assertEquals(1,p.listModels().size) else try {p.listModels();fail("expected failure")} catch(f:ProviderFailure) {assertEquals(expected,f.error.kind)}
            assertEquals(1,s.requestCount);assertEquals("/models",s.takeRequest().path)
            val d=p.catalogDiagnostic!!;assertEquals(status,d.httpStatus)
            assertFalse((d.toString()+d.summary()+network.diagnostics.snapshot()).contains(marker))
            assertFalse(d.summary().contains("private-body"))
        }
    }
    @Test fun realTransportCatalogSuccess() {http(200,body(),null)}
    @Test fun http401() {http(401,"private-body",ErrorKind.AUTHENTICATION)}
    @Test fun http403() {http(403,"private-body",ErrorKind.AUTHORIZATION)}
    @Test fun http402Balance() {http(402,"private-body",ErrorKind.BILLING)}
    @Test fun http429() {http(429,"private-body",ErrorKind.RATE_LIMIT)}
    @Test fun http500() {http(500,"private-body",ErrorKind.SERVER)}
    @Test fun http503DoesNotReplay() {http(503,"private-body",ErrorKind.SERVER)}
    @Test fun aboveOld256KiBBudgetStillParses() {http(200,body().dropLast(1)+",\"unused\":\""+"x".repeat(300000)+"\"}",null)}
    @Test fun new2MiBBudgetRejectsOversizedCatalog() {http(200,body().dropLast(1)+",\"unused\":\""+"x".repeat(2*1024*1024)+"\"}",ErrorKind.PROTOCOL)}
    @Test fun largeMalformedJsonIsProtocol() {http(200,"{"+"x".repeat(300000),ErrorKind.PROTOCOL)}
    @Test fun errorsRetainConservative256KiBBound() {http(401,"x".repeat(256*1024+1),ErrorKind.PROTOCOL)}
    @Test fun absentKeyStartsZeroRequests()=runBlocking {
        val network=NetworkClient();val p=DeepSeekProvider(DeepSeekCredentials(MemoryBlob(),testBox()),network)
        try {p.listModels();fail("expected failure")} catch(f:ProviderFailure) {assertEquals(ErrorKind.AUTHENTICATION,f.error.kind)}
        assertEquals(0,network.startedCallCount);assertFalse(p.catalogDiagnostic!!.requestStarted)
    }
}
