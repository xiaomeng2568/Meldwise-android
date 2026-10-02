package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class DeepSeekInferenceTests {
    private val marker="PRIVATE_SYNTHETIC_INPUT_OUTPUT"
    private val keyMarker="PRIVATE_SYNTHETIC_API_KEY"
    private val catalog="""{"object":"list","data":[{"object":"model","id":"synthetic-model"}]}"""
    private val added="""{"type":"response.output_item.added","output_index":1,"item":{"id":"synthetic-item","type":"message","role":"assistant"}}"""
    private val delta="""{"type":"response.output_text.delta","output_index":1,"content_index":0,"item_id":"synthetic-item","delta":"$marker"}"""
    private val done="""{"type":"response.output_text.done","output_index":1,"content_index":0,"item_id":"synthetic-item","text":"$marker"}"""
    private val completed="""{"type":"response.completed","response":{"id":"private-response-id","status":"completed","output":[],"usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5}}}"""
    private fun frames(vararg data:String)=data.joinToString("") {"data: $it\n\n"}
    private fun input(model:String="synthetic-model")=LlmRequest(model,listOf(LlmMessage(MessageRole.USER,marker)))
    private fun provider(s:MockWebServer):DeepSeekProvider {
        val key=DeepSeekCredentials(MemoryBlob(),testBox());key.replace(keyMarker)
        return DeepSeekProvider(key,NetworkClient(allowLocalTestHttp=true),s.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'))
    }
    private fun run(response:MockResponse,verify:(List<LlmEvent>,InferenceDiagnostic)->Unit)=runBlocking {
        MockWebServer().use {s ->s.start();s.enqueue(MockResponse().setBody(catalog));s.enqueue(response)
            val p=provider(s);p.listModels();val events=withTimeout(10000) {p.streamResponse(input()).toList()}
            val d=p.inferenceDiagnostic.value!!;verify(events,d)
            assertEquals(2,s.requestCount);assertEquals(1,d.networkExchangeCount)
            assertEquals("GET",s.takeRequest().method);val post=s.takeRequest();assertEquals("POST",post.method);assertEquals("/responses",post.path)
            val payload=Json.parseToJsonElement(post.body.readUtf8()).jsonObject
            assertEquals(setOf("model","stream","input"),payload.keys)
            assertNull(s.takeRequest(30,TimeUnit.MILLISECONDS))
            val exported=d.toString()+d.summary()
            listOf(marker,keyMarker,"private-response-id","synthetic-item","Authorization","Bearer").forEach {assertFalse(exported.contains(it))}
        }
    }
    private fun success(vararg data:String) {run(MockResponse().setBody(frames(*data))) { e,d ->
        assertTrue(e.last() is LlmEvent.Completed);assertEquals(marker,e.filterIsInstance<LlmEvent.TextDelta>().joinToString("") {it.text})
        assertTrue(d.terminalSuccessValidated);assertEquals(5L,(e.last() as LlmEvent.Completed).usage!!.totalTokens)
    }}
    @Test fun normalDeltaDoneAndCompletion() {success("""{"type":"response.created"}""",added,delta,done,completed)}
    @Test fun namedSseEventsSupported() {
        val body="event: response.output_item.added\ndata: $added\n\nevent: response.output_text.delta\ndata: $delta\n\nevent: response.completed\ndata: $completed\n\n"
        run(MockResponse().setBody(body)) {e,d ->assertTrue(e.last() is LlmEvent.Completed);assertTrue(d.terminalSuccessValidated)}
    }
    @Test fun finalTextOnly() {success(added,done,completed)}
    @Test fun finalizedContentPart() {success(added,"""{"type":"response.content_part.done","output_index":1,"content_index":0,"item_id":"synthetic-item","part":{"type":"output_text","text":"$marker"}}""",completed)}
    @Test fun finalizedOutputItem() {success("""{"type":"response.output_item.done","output_index":1,"item":{"type":"message","role":"assistant","id":"synthetic-item","content":[{"type":"output_text","text":"$marker"}]}}""",completed)}
    @Test fun finalEnvelopeOnly() {success(completed.replace("\"output\":[]","\"output\":[{\"id\":\"synthetic-item\",\"role\":\"assistant\",\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"$marker\"}]}]"))}
    @Test fun reasoningNeverBecomesAssistantText() {
        success("""{"type":"response.output_item.added","output_index":0,"item":{"id":"reasoning-private","type":"reasoning"}}""",
            """{"type":"response.reasoning_text.delta","output_index":0,"delta":"PRIVATE_REASONING"}""",added,delta,completed)
    }
    @Test fun reasoningOnlyCannotComplete() {
        run(MockResponse().setBody(frames("""{"type":"response.reasoning_text.delta","delta":"PRIVATE_REASONING"}""",completed))) {e,d ->
            assertFalse(e.any {it is LlmEvent.Completed});assertEquals(InferenceProtocol.ASSISTANT_OUTPUT_TEXT_NOT_DETECTED,d.protocolCategory)
        }
    }
    @Test fun duplicateFinalizerDoesNotDuplicateText() {success(added,delta,done,done,completed)}
    @Test fun responseFailedWithoutText() {
        run(MockResponse().setBody(frames("""{"type":"response.failed","response":{"error":{"code":"server_error","message":"$marker"}}}"""))) {e,d ->
            assertEquals(ErrorKind.SERVER,(e.single() as LlmEvent.Failed).error.kind);assertFalse(d.terminalSuccessValidated)
        }
    }
    @Test fun responseFailedAfterPartialIsIncomplete() {
        run(MockResponse().setBody(frames(added,delta,"""{"type":"response.failed","response":{"error":{"code":"insufficient_quota"}}}"""))) {e,d ->
            assertEquals(ErrorKind.BILLING,(e.last() as LlmEvent.Incomplete).error.kind);assertEquals(ProviderCode.BILLING,d.providerCode)
        }
    }
    @Test fun responseIncompleteWithoutText() {
        run(MockResponse().setBody(frames("""{"type":"response.incomplete","response":{"status":"incomplete"}}"""))) {e,d ->
            assertTrue(e.single() is LlmEvent.Incomplete);assertFalse(d.terminalSuccessValidated)
        }
    }
    @Test fun responseIncompleteAfterPartial() {
        run(MockResponse().setBody(frames(added,delta,"""{"type":"response.incomplete","response":{"status":"incomplete"}}"""))) {e,d ->
            assertTrue(e.last() is LlmEvent.Incomplete);assertFalse(e.any {it is LlmEvent.Completed});assertTrue(d.assistantTextProduced)
        }
    }
    @Test fun eofWithPartialIsIncomplete() {
        run(MockResponse().setBody(frames(added,delta))) {e,d ->assertTrue(e.last() is LlmEvent.Incomplete);assertEquals(InferenceStage.EOF_BEFORE_TERMINAL,d.failureStage)}
    }
    @Test fun emptyEofIsIncomplete() {run(MockResponse().setBody("")) {e,d ->assertTrue(e.single() is LlmEvent.Incomplete);assertFalse(d.terminalSuccessValidated)}}
    @Test fun terminalStatusMustMatch() {run(MockResponse().setBody(frames(added,delta,completed.replace("\"status\":\"completed\"","\"status\":\"in_progress\"")))) {e,d ->assertFalse(e.any {it is LlmEvent.Completed});assertEquals(InferenceProtocol.TERMINAL_STATUS_INVALID,d.protocolCategory)}}
    @Test fun malformedEventIsProtocol() {run(MockResponse().setBody("data: {bad\n\n")) {e,d ->assertEquals(ErrorKind.PROTOCOL,(e.single() as LlmEvent.Failed).error.kind);assertEquals(InferenceProtocol.JSON_INVALID,d.protocolCategory)}}
    @Test fun conflictingItemStillRejected() {run(MockResponse().setBody(frames(added,delta.replace("synthetic-item","other")))) {e,d ->assertFalse(e.any {it is LlmEvent.Completed});assertEquals(InferenceProtocol.ITEM_CONFLICT,d.protocolCategory)}}
    @Test fun finalizedTextConflictRejected() {run(MockResponse().setBody(frames(added,delta,done.replace(marker,"other")))) {e,d ->assertFalse(e.any {it is LlmEvent.Completed});assertEquals(InferenceProtocol.TEXT_CONFLICT,d.protocolCategory)}}
    private fun http(status:Int,kind:ErrorKind) {run(MockResponse().setResponseCode(status).setHeader("Retry-After","0").setBody("private raw body")) {e,d ->assertEquals(kind,(e.single() as LlmEvent.Failed).error.kind);assertEquals(status,d.httpStatus);assertFalse(d.sseParserStarted)}}
    @Test fun http401NoRefreshOrReplay() {http(401,ErrorKind.AUTHENTICATION)}
    @Test fun http403NoFallback() {http(403,ErrorKind.AUTHORIZATION)}
    @Test fun http402Billing() {http(402,ErrorKind.BILLING)}
    @Test fun http429NoRetry() {http(429,ErrorKind.RATE_LIMIT)}
    @Test fun http503NoSecondPost() {http(503,ErrorKind.SERVER)}
    @Test fun unknownErrorIsNotExported() {run(MockResponse().setBody(frames("""{"type":"error","code":"$marker","message":"$marker"}"""))) {e,d ->assertEquals(ErrorKind.PROTOCOL,(e.single() as LlmEvent.Failed).error.kind);assertEquals(ProviderCode.UNKNOWN,d.providerCode)}}
    @Test fun knownContextCodeNormalized() {run(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"context_length_exceeded","message":"$marker"}}""")) {e,_ ->assertEquals(ErrorKind.CONTEXT_OVERFLOW,(e.single() as LlmEvent.Failed).error.kind)}}
    @Test fun cancellationAfterPartialClosesOnePost()=runBlocking {
        MockWebServer().use {s ->s.start();s.enqueue(MockResponse().setBody(catalog))
            val partial=frames(added,delta)
            // Deliberately unfinished HTTP body: EOF cannot settle the attempt before cancellation.
            // The consumer's explicit barrier confirms text arrived; no delay-based coordination.
            s.enqueue(MockResponse().setBody(partial).setHeader("Content-Length",partial.toByteArray().size+100))
            val p=provider(s);p.listModels()
            val seen=CompletableDeferred<Unit>()
            val job=launch {p.streamResponse(input()).collect {if(it is LlmEvent.TextDelta) {seen.complete(Unit);awaitCancellation()}}}
            withTimeout(5000) {seen.await();job.cancelAndJoin()}
            assertEquals(2,s.requestCount);val d=p.inferenceDiagnostic.value!!
            assertEquals(ErrorKind.CANCELLED,d.resultCategory);assertTrue(d.assistantTextProduced);assertFalse(d.terminalSuccessValidated)
        }
    }
    @Test fun cancellationBeforeResponseClosesOnePost()=runBlocking {
        MockWebServer().use {s ->s.start();s.enqueue(MockResponse().setBody(catalog));s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val p=provider(s);p.listModels();s.takeRequest()
            val job=launch(Dispatchers.Default) {p.streamResponse(input()).collect()}
            assertNotNull(s.takeRequest(5,TimeUnit.SECONDS));withTimeout(5000) {job.cancelAndJoin()}
            assertEquals(2,s.requestCount);assertEquals(ErrorKind.CANCELLED,p.inferenceDiagnostic.value!!.resultCategory)
        }
    }
    @Test fun missingKeyNeverSendsPost()=runBlocking {
        val network=NetworkClient();val p=DeepSeekProvider(DeepSeekCredentials(MemoryBlob(),testBox()),network)
        val e=p.streamResponse(input()).toList();assertEquals(ErrorKind.AUTHENTICATION,(e.single() as LlmEvent.Failed).error.kind)
        assertEquals(0,network.startedCallCount)
    }
    @Test fun unknownModelNeverSendsPost()=runBlocking {
        MockWebServer().use {s ->s.start();s.enqueue(MockResponse().setBody(catalog));val p=provider(s);p.listModels()
            val e=p.streamResponse(input("unknown")).toList();assertEquals(ErrorKind.MODEL_UNAVAILABLE,(e.single() as LlmEvent.Failed).error.kind);assertEquals(1,s.requestCount)
        }
    }
}
