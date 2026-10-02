package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.CredentialStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class InferenceProtocolTests {
    private val marker="PRIVATE_SYNTHETIC_TEXT_DO_NOT_EXPORT"
    private val added="""{"type":"response.output_item.added","output_index":0,"item":{"id":"synthetic-item","type":"message","role":"assistant"}}"""
    private val delta="""{"type":"response.output_text.delta","output_index":0,"content_index":0,"item_id":"synthetic-item","delta":"$marker"}"""
    private val done="""{"type":"response.output_text.done","output_index":0,"content_index":0,"item_id":"synthetic-item","text":"$marker"}"""
    private val partDone="""{"type":"response.content_part.done","output_index":0,"content_index":0,"item_id":"synthetic-item","part":{"type":"output_text","text":"$marker"}}"""
    private val itemDone="""{"type":"response.output_item.done","output_index":0,"item":{"id":"synthetic-item","type":"message","role":"assistant","content":[{"type":"output_text","text":"$marker"}]}}"""
    private val completed="""{"type":"response.completed","response":{"id":"private-synthetic-response-id","status":"completed","output":[]}}"""
    private fun frames(vararg json:String)=json.joinToString("") { "data: $it\n\n" }
    private suspend fun provider(server:MockWebServer):ChatGptProvider {
        var record=StoredSession(Registration(Siwc.ISSUER,Secret("private-client"),Secret("private-account"),"private-host"),
            CredentialSet(Secret("private-access"),Secret("private-refresh"),Secret("private-id"),1000000,Scopes.requested,0),1,CredentialPhase.ACTIVE)
        val manager=TokenManager(object:CredentialStore {override fun read()=record;override fun write(value:StoredSession) {record=value}},
            object:RefreshEndpoint {override suspend fun refresh(registration:Registration,current:CredentialSet):CredentialSet=error("UNEXPECTED_REFRESH")},TimeSource {0})
        manager.initialize()
        return ChatGptProvider(manager,NetworkClient(allowLocalTestHttp=true),server.url("/v1").newBuilder().host("127.0.0.1").build().toString())
    }
    private suspend fun runResponse(response:MockResponse,verify:(List<LlmEvent>,InferenceDiagnostic)->Unit) {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"models":[{"slug":"synthetic-model","display_name":"Synthetic","visibility":"list"}]}"""))
            server.enqueue(response)
            val p=provider(server);p.listModels()
            val events=withTimeout(10000) {p.streamResponse(LlmRequest("synthetic-model",listOf(LlmMessage(MessageRole.USER,marker)))).toList()}
            val diagnostic=requireNotNull(p.inferenceDiagnostic.value)
            assertEquals(1,diagnostic.networkExchangeCount)
            verify(events,diagnostic)
            assertEquals(2,server.requestCount)
            assertEquals("GET",server.takeRequest().method)
            assertEquals("POST",server.takeRequest().method)
            assertNull(server.takeRequest(50,TimeUnit.MILLISECONDS))
            val export=diagnostic.toString()+diagnostic.summary()
            listOf(marker,"private-access","private-refresh","private-id","private-account","private-host","private-client",
                "synthetic-item","private-synthetic-response-id","Authorization","Bearer").forEach { assertFalse(export.contains(it)) }
        }
    }
    private suspend fun http(status:Int,body:String,shape:ProviderBodyShape,kind:ErrorKind,code:ProviderCode=ProviderCode.NONE) {
        runResponse(MockResponse().setResponseCode(status).setBody(body)) {events,d ->
            assertEquals(kind,(events.single() as LlmEvent.Failed).error.kind)
            assertTrue(d.credentialAvailable && d.requestBuilt && d.requestStarted && d.httpReceived)
            assertEquals(status,d.httpStatus);assertEquals(InferenceStage.HTTP,d.failureStage)
            assertEquals(shape,d.providerBodyShape);assertEquals(code,d.providerCode)
            assertFalse(d.sseParserStarted);assertFalse(d.terminalSuccessValidated)
            assertEquals(InferenceProtocol.NONE,d.protocolCategory)
        }
    }
    @Test fun http400StandardError()=runBlocking { http(400,"""{"error":{"code":"subscription_sharing_unsupported_capability","message":"$marker"}}""",
        ProviderBodyShape.STANDARD_ERROR_OBJECT,ErrorKind.UNSUPPORTED_CAPABILITY,ProviderCode.UNSUPPORTED_CAPABILITY) }
    @Test fun http400Detail()=runBlocking { http(400,"""{"detail":"$marker"}""",ProviderBodyShape.DETAIL_OBJECT,ErrorKind.UNKNOWN) }
    @Test fun http400UnknownCodeIsNotRaw()=runBlocking { http(400,"""{"error":{"code":"$marker"}}""",ProviderBodyShape.STANDARD_ERROR_OBJECT,ErrorKind.UNKNOWN,ProviderCode.UNKNOWN) }
    @Test fun http401()=runBlocking { http(401,"{}",ProviderBodyShape.OTHER_JSON,ErrorKind.AUTHENTICATION) }
    @Test fun http403Detail()=runBlocking { http(403,"""{"detail":"$marker"}""",ProviderBodyShape.DETAIL_OBJECT,ErrorKind.AUTHORIZATION) }
    @Test fun http429()=runBlocking { http(429,"{}",ProviderBodyShape.OTHER_JSON,ErrorKind.RATE_LIMIT) }
    @Test fun http429PlanQuota()=runBlocking { http(429,"""{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"$marker"}}""",
        ProviderBodyShape.STANDARD_ERROR_OBJECT,ErrorKind.PLAN_USAGE_LIMIT,ProviderCode.PLAN_USAGE_LIMIT) }
    @Test fun httpNonJsonDoesNotBecomeSseError()=runBlocking { http(400,marker,ProviderBodyShape.NON_JSON,ErrorKind.UNKNOWN) }
    private suspend fun success(vararg json:String) {
        runResponse(MockResponse().setBody(frames(*json))) {events,d ->
            assertTrue(events.last() is LlmEvent.Completed)
            assertEquals(marker,events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") {it.text})
            assertTrue(d.assistantTextProduced && d.assistantOutputItemSeen && d.completedSeen && d.terminalSuccessValidated)
            assertEquals(json.size,d.parsedEventCount);assertEquals(ResponseEvent.COMPLETED,d.lastEvent)
            assertEquals(InferenceStage.NONE,d.failureStage);assertEquals(InferenceProtocol.NONE,d.protocolCategory)
        }
    }
    @Test fun ordinaryDeltaDoneCompletedAndEmptyTerminal()=runBlocking {
        success("""{"type":"response.created"}""",added,delta,done,completed)
    }
    @Test fun onlyContentPartDoneCarriesFinalText()=runBlocking { success(added,partDone,completed) }
    @Test fun onlyOutputItemDoneCarriesFinalText()=runBlocking { success(itemDone,completed) }
    @Test fun repeatedFinalizersDoNotDuplicateText()=runBlocking { success(added,delta,done,partDone,itemDone,completed) }
    @Test fun finalEnvelopeOnlyCanExtract()=runBlocking {
        success("""{"type":"response.completed","response":{"id":"private-synthetic-response-id","status":"completed","output":[{"type":"message","role":"assistant","id":"synthetic-item","content":[{"type":"output_text","text":"$marker"}]}]}}""")
    }
    @Test fun responseFailedIsProviderFailure()=runBlocking {
        runResponse(MockResponse().setBody(frames("""{"type":"response.failed","response":{"error":{"code":"server_error","message":"$marker"}}}"""))) {events,d ->
            assertTrue(events.single() is LlmEvent.Failed);assertTrue(d.failedSeen)
            assertEquals(InferenceStage.PROVIDER_FAILED,d.failureStage);assertFalse(d.terminalSuccessValidated)
            assertEquals(ProviderCode.SERVER,d.providerCode)
        }
    }
    @Test fun responseIncompleteAfterOutputIsNotSuccess()=runBlocking {
        runResponse(MockResponse().setBody(frames(added,delta,"""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"$marker"}}}"""))) {events,d ->
            assertTrue(events.last() is LlmEvent.Incomplete);assertTrue(d.incompleteSeen && d.assistantTextProduced)
            assertEquals(InferenceStage.PROVIDER_FAILED,d.failureStage);assertFalse(d.terminalSuccessValidated)
        }
    }
    private suspend fun protocol(body:String,stage:InferenceStage,category:InferenceProtocol) {
        runResponse(MockResponse().setBody(body)) {events,d ->
            assertFalse(events.any {it is LlmEvent.Completed})
            assertEquals(stage,d.failureStage);assertEquals(category,d.protocolCategory)
            assertFalse(d.terminalSuccessValidated);assertEquals(200,d.httpStatus)
        }
    }
    @Test fun malformedEventJson()=runBlocking { protocol("data: {invalid-$marker\n\n",InferenceStage.EVENT_JSON,InferenceProtocol.JSON_INVALID) }
    @Test fun conflictingEventTypes()=runBlocking { protocol("event: response.completed\ndata: $delta\n\n",InferenceStage.EVENT_STRUCTURE,InferenceProtocol.EVENT_TYPE_CONFLICT) }
    @Test fun malformedSseStaysBounded()=runBlocking { protocol("data: "+"x".repeat(32769)+"\n\n",InferenceStage.SSE_FRAME,InferenceProtocol.SSE_INVALID) }
    @Test fun eofBeforeTerminal()=runBlocking {
        runResponse(MockResponse().setBody(frames(added,delta))) {events,d ->
            assertTrue(events.last() is LlmEvent.Incomplete);assertEquals(InferenceStage.EOF_BEFORE_TERMINAL,d.failureStage)
            assertFalse(d.terminalSuccessValidated)
        }
    }
    @Test fun contentPartTextMustHaveTrustedAssistant()=runBlocking { protocol(frames(partDone),InferenceStage.ASSISTANT_ASSOCIATION,InferenceProtocol.ASSISTANT_ITEM_REQUIRED) }
    @Test fun itemIdConflictRejected()=runBlocking { protocol(frames(added,partDone.replace("synthetic-item","another-item")),InferenceStage.ASSISTANT_ASSOCIATION,InferenceProtocol.ITEM_CONFLICT) }
    @Test fun conflictingFinalizedTextStillRejected()=runBlocking { protocol(frames(added,delta,done.replace(marker,"other")),InferenceStage.TEXT_EXTRACTION,InferenceProtocol.TEXT_CONFLICT) }
    @Test fun terminalStatusMustBeCompleted()=runBlocking { protocol(frames(added,delta,completed.replace("\"status\":\"completed\"","\"status\":\"in_progress\"")),InferenceStage.TERMINAL_VALIDATION,InferenceProtocol.TERMINAL_STATUS_INVALID) }
    @Test fun terminalMustHaveId()=runBlocking { protocol(frames(added,delta,completed.replace("private-synthetic-response-id","")),InferenceStage.TERMINAL_VALIDATION,InferenceProtocol.TERMINAL_ID_MISSING) }
    @Test fun unknownEventNamesNeverExported()=runBlocking {
        runResponse(MockResponse().setBody(frames("""{"type":"$marker","data":"$marker"}""",added,delta,completed))) {_,d ->
            assertEquals(ResponseEvent.OTHER,d.firstEvent);assertTrue(d.terminalSuccessValidated)
        }
    }
    @Test fun terminalTraceCannotBeChangedByLateCancellation() {
        val trace=InferenceTrace();trace.finish(success=true)
        trace.finish(InferenceStage.CANCELLED,result=ErrorKind.CANCELLED)
        trace.update { it.copy(httpStatus=403) }
        assertTrue(trace.snapshot().terminalSuccessValidated);assertNull(trace.snapshot().httpStatus)
    }
    @Test fun diagnosticsHaveOnlyClosedSchemaFieldTypes() {
        val permitted=setOf(Boolean::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaObjectType,
            ResponseEvent::class.java,InferenceStage::class.java,InferenceProtocol::class.java,ProviderBodyShape::class.java,
            ProviderCode::class.java,ErrorKind::class.java,TransportCategory::class.java)
        InferenceDiagnostic::class.java.declaredFields.filterNot {java.lang.reflect.Modifier.isStatic(it.modifiers)}.forEach {
            assertTrue("Unsafe diagnostic field: "+it.name,it.type in permitted)
        }
    }
}
