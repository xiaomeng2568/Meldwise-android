package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual adapters and HTTP transport, exclusively against local synthetic servers. */
class CompareTransportTests {
    private suspend fun chat(server:MockWebServer):ChatGptProvider {
        var record=StoredSession(Registration(Siwc.ISSUER,Secret("synthetic-client"),Secret("synthetic-subject"),"synthetic-host"),
            CredentialSet(Secret("synthetic-access"),Secret("synthetic-refresh"),Secret("synthetic-id"),1000000,Scopes.requested,0),1,CredentialPhase.ACTIVE)
        val manager=TokenManager(object:CredentialStore {override fun read()=record;override fun write(value:StoredSession) {record=value}},
            object:RefreshEndpoint {override suspend fun refresh(registration:Registration,current:CredentialSet):CredentialSet=error("UNEXPECTED_REFRESH")},TimeSource {0})
        manager.initialize()
        return ChatGptProvider(manager,NetworkClient(allowLocalTestHttp=true),server.url("/v1").newBuilder().host("127.0.0.1").build().toString())
    }
    private fun deep(server:MockWebServer):DeepSeekProvider {
        val credentials=DeepSeekCredentials(MemoryBlob(),testBox());credentials.replace("PRIVATE_SYNTHETIC_KEY")
        return DeepSeekProvider(credentials,NetworkClient(allowLocalTestHttp=true),server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'))
    }
    private fun stream()=listOf(
        """{"type":"response.created"}""",
        """{"type":"response.output_item.added","output_index":0,"item":{"id":"synthetic-item","type":"message","role":"assistant"}}""",
        """{"type":"response.output_text.delta","output_index":0,"content_index":0,"item_id":"synthetic-item","delta":"synthetic answer"}""",
        """{"type":"response.completed","response":{"id":"synthetic-response","status":"completed","output":[]}}"""
    ).joinToString("") {"data: $it\n\n"}
    private fun run()=CompareRun("synthetic-run","synthetic prompt",CompareLaneRecord("A",ModelRef("chatgpt","model-a")),
        CompareLaneRecord("B",ModelRef("deepseek","model-b"),preference=ReasoningPreference.Off))
    private fun exercise(fail:String?=null)=runBlocking {
        MockWebServer().use {a ->MockWebServer().use {b ->
            val entered=CountDownLatch(2);val release=CountDownLatch(1);val postA=AtomicInteger();val postB=AtomicInteger()
            fun dispatcher(provider:String,count:AtomicInteger)=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.method=="GET") return MockResponse().setBody(if(provider=="chatgpt")
                        """{"models":[{"slug":"model-a","display_name":"A","visibility":"list"}]}""" else
                        """{"object":"list","data":[{"object":"model","id":"model-b"}]}""")
                    count.incrementAndGet();entered.countDown()
                    check(release.await(8,TimeUnit.SECONDS))
                    return if(fail==provider) MockResponse().setResponseCode(500).setBody("{}") else MockResponse().setBody(stream())
                }
            }
            a.dispatcher=dispatcher("chatgpt",postA);b.dispatcher=dispatcher("deepseek",postB);a.start();b.start()
            val pa=chat(a);val pb=deep(b);pa.listModels();pb.listModels()
            val task=async(Dispatchers.Default) {CompareExecutor(ProviderRegistry(listOf(pa,pb)),CompareRepository(MemoryBlob(),testBox())).execute(run()) {}}
            try {
                assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
                assertFalse(task.isCompleted);assertEquals(1,postA.get());assertEquals(1,postB.get())
            } finally {release.countDown()}
            val result=withTimeout(8000) {task.await()}
            assertEquals(if(fail==null) CompareRunState.Completed else CompareRunState.Partial,result.lifecycle)
            assertEquals(if(fail=="chatgpt") CompareLaneState.Failed else CompareLaneState.Completed,result.laneA.state)
            assertEquals(if(fail=="deepseek") CompareLaneState.Failed else CompareLaneState.Completed,result.laneB.state)
            assertEquals(2,a.requestCount);assertEquals(2,b.requestCount)
            a.takeRequest();b.takeRequest()
            val postARequest=a.takeRequest();val postBRequest=b.takeRequest()
            assertEquals("POST",postARequest.method);assertEquals("POST",postBRequest.method)
            assertTrue(postARequest.body.readUtf8().contains("\"store\":false"))
            val deepBody=postBRequest.body.readUtf8();assertFalse(deepBody.contains("store"));assertTrue(deepBody.contains("\"effort\":\"none\""))
        }}
    }
    @Test fun twoActualAdaptersEnterOnePostEachBeforeEitherIsReleased() {exercise()}
    @Test fun actualChatgptFailureDoesNotCancelDeepseekOrReplay() {exercise("chatgpt")}
    @Test fun actualDeepseekFailureDoesNotCancelChatgptOrReplay() {exercise("deepseek")}
    @Test fun cancelActualPendingTransportKeepsCompletedLaneAndNoSecondPost()=runBlocking {
        MockWebServer().use {a ->MockWebServer().use {b ->a.start();b.start()
            a.enqueue(MockResponse().setBody("""{"models":[{"slug":"model-a","display_name":"A","visibility":"list"}]}"""));a.enqueue(MockResponse().setBody(stream()))
            b.enqueue(MockResponse().setBody("""{"object":"list","data":[{"object":"model","id":"model-b"}]}"""));b.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pa=chat(a);val pb=deep(b);pa.listModels();pb.listModels();a.takeRequest();b.takeRequest()
            val completed=CompletableDeferred<Unit>();var latest=run()
            val task=launch(Dispatchers.Default) {CompareExecutor(ProviderRegistry(listOf(pa,pb)),CompareRepository(MemoryBlob(),testBox())).execute(run()) {
                latest=it;if(it.laneA.state==CompareLaneState.Completed) completed.complete(Unit)
            }}
            assertNotNull(withContext(Dispatchers.IO) {b.takeRequest(5,TimeUnit.SECONDS)})
            withTimeout(5000) {completed.await();task.cancelAndJoin()}
            assertEquals(CompareLaneState.Completed,latest.laneA.state);assertEquals(CompareLaneState.Cancelled,latest.laneB.state)
            assertEquals(CompareRunState.Partial,latest.lifecycle);assertEquals(2,a.requestCount);assertEquals(2,b.requestCount)
        }}
    }
}
