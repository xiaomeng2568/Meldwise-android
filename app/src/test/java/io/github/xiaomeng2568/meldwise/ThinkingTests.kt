package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.security.*
import io.github.xiaomeng2568.meldwise.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*

class ThinkingTests {
    private val input=listOf(LlmMessage(MessageRole.USER,"SYNTHETIC_PROMPT"))
    private fun deep()=DeepSeekProvider(DeepSeekCredentials(MemoryBlob(),testBox()),NetworkClient())
    private fun effort(p:ReasoningPreference)=Json.parseToJsonElement(deep().payload(LlmRequest("returned-model",input,reasoning=p))).jsonObject
    @Test fun deepSeekOffSendsNone() {assertEquals("none",effort(ReasoningPreference.Off)["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)}
    @Test fun deepSeekOnSendsHigh() {assertEquals("high",effort(ReasoningPreference.High)["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)}
    @Test fun deepSeekLow() {assertEquals("low",ReasoningPolicy.effort("deepseek",ReasoningPreference.Low))}
    @Test fun deepSeekMax() {assertEquals("max",ReasoningPolicy.effort("deepseek",ReasoningPreference.Max))}
    @Test fun autoPreservesExistingPayload() {assertEquals(setOf("model","stream","input"),effort(ReasoningPreference.Auto).keys)}
    @Test fun noIncompatibleSamplingOrStore() {assertEquals(setOf("model","stream","input","reasoning"),effort(ReasoningPreference.High).keys)}
    @Test fun capabilityDoesNotGuessModelName() {listOf("unknown-name","legacy-id","whatever").forEach {assertTrue(ReasoningPolicy.supported(ModelRef("deepseek",it),ReasoningPreference.High))}}
    @Test fun chatgptUsesUnchangedAutoOnly() {assertNull(ReasoningPolicy.effort("chatgpt",ReasoningPreference.Auto))}
    @Test fun unsupportedChatgptReasoningBlocked() {listOf(ReasoningPreference.Off,ReasoningPreference.Low,ReasoningPreference.High,ReasoningPreference.Max).forEach {assertThrows(IllegalArgumentException::class.java) {ReasoningPolicy.effort("chatgpt",it)}}}
    private fun frame(s:String)=SseFrame(null,s)
    private fun added(type:String="reasoning",index:Int=0)="""{"type":"response.output_item.added","output_index":$index,"item":{"id":"item-$index","type":"$type"${if(type=="message") ",\"role\":\"assistant\"" else ""}}}"""
    private fun delta(text:String="PRIVATE_REASONING",type:String="response.reasoning_text.delta",index:Int=0)="""{"type":"$type","output_index":$index,"content_index":0,"item_id":"item-$index","delta":"$text"}"""
    private fun reader(mode:ReasoningReadMode=ReasoningReadMode.DeepSeekVisible)=ResponsesReader(reasoningReader=ReasoningReader(mode))
    @Test fun reasoningSeparatedFromAnswer() {
        val r=reader();r.consume(frame(added()));val e=r.consume(frame(delta()))
        assertEquals("PRIVATE_REASONING",(e.single() as LlmEvent.ReasoningDelta).text);assertFalse(e.any {it is LlmEvent.TextDelta})
        r.consume(frame(added("message",1)));val answer=r.consume(frame(delta("ANSWER","response.output_text.delta",1)))
        assertEquals("ANSWER",(answer.single() as LlmEvent.TextDelta).text);assertFalse(answer.any {it is LlmEvent.ReasoningDelta})
    }
    @Test fun noContentNeverCreatesReasoning() {assertTrue(reader().consume(frame("""{"type":"response.created"}""")).isEmpty())}
    @Test fun offNeverSurfacesReasoning() {assertTrue(reader(ReasoningReadMode.None).consume(frame(delta())).isEmpty())}
    @Test fun openaiSummaryIgnoresRawReasoning() {val r=reader(ReasoningReadMode.OpenAiSummary);r.consume(frame(added()));assertTrue(r.consume(frame(delta())).isEmpty())}
    @Test fun openaiSummarySeparateType() {val r=reader(ReasoningReadMode.OpenAiSummary);r.consume(frame(added()));val e=r.consume(frame(delta("SUMMARY","response.reasoning_summary_text.delta")));assertEquals(ReasoningContent.Summary,(e.single() as LlmEvent.ReasoningDelta).kind)}
    @Test fun deepseekDoesNotInterpretSummaryEvents() {val r=reader();r.consume(frame(added()));assertTrue(r.consume(frame(delta(type="response.reasoning_summary_text.delta"))).isEmpty())}
    @Test fun doneIsNotDuplicatedDelta() {
        val r=reader();r.consume(frame(added()));r.consume(frame(delta()))
        val e=r.consume(frame("""{"type":"response.reasoning_text.done","output_index":0,"content_index":0,"text":"PRIVATE_REASONING"}"""))
        assertTrue(e.single() is LlmEvent.ReasoningDone)
    }
    @Test fun finalizedReasoningWithoutDelta() {
        val r=reader();r.consume(frame(added()));val e=r.consume(frame("""{"type":"response.reasoning_text.done","output_index":0,"text":"FINALIZED"}"""))
        assertEquals("FINALIZED",e.filterIsInstance<LlmEvent.ReasoningDelta>().single().text)
    }
    @Test fun finalReasoningItem() {
        val e=reader().consume(frame("""{"type":"response.output_item.done","output_index":0,"item":{"id":"item-0","type":"reasoning","content":[{"type":"reasoning_text","text":"FINAL"}]}}"""))
        assertEquals("FINAL",e.filterIsInstance<LlmEvent.ReasoningDelta>().single().text)
    }
    @Test fun reasoningContentPartDone() {
        val r=reader();r.consume(frame(added()));val e=r.consume(frame("""{"type":"response.content_part.done","output_index":0,"content_index":0,"part":{"type":"reasoning_text","text":"FINAL"}}"""))
        assertEquals("FINAL",e.filterIsInstance<LlmEvent.ReasoningDelta>().single().text)
    }
    @Test fun conflictingDoneFailsProtocol() {val r=reader();r.consume(frame(added()));r.consume(frame(delta()));assertThrows(ResponseProtocolFailure::class.java) {r.consume(frame("""{"type":"response.reasoning_text.done","output_index":0,"text":"conflict"}"""))}}
    @Test fun unassociatedReasoningFailsClosed() {assertThrows(ResponseProtocolFailure::class.java) {reader().consume(frame(delta()))}}
    @Test fun conflictingItemFailsClosed() {val r=reader();r.consume(frame(added()));assertThrows(ResponseProtocolFailure::class.java) {r.consume(frame(delta().replace("item-0","other")))}}
    @Test fun reasoningOutputBound() {val r=reader();r.consume(frame(added()));assertThrows(ResponseProtocolFailure::class.java) {r.consume(frame(delta("x".repeat(262145))))}}
    @Test fun reasoningOnlyCannotComplete() {val r=reader();r.consume(frame(added()));r.consume(frame(delta()));assertThrows(ResponseProtocolFailure::class.java) {r.consume(frame("""{"type":"response.completed","response":{"id":"r","status":"completed","output":[]}}"""))}}
    @Test fun eventExportsRedacted() {assertFalse((LlmEvent.ReasoningDelta("PRIVATE_REASONING").toString()+ReasoningRecord("PRIVATE_REASONING").toString()).contains("PRIVATE_REASONING"))}
    @Test fun malformedIndexRejected() {val r=reader();r.consume(frame(added()));assertThrows(ResponseProtocolFailure::class.java) {r.consume(frame(delta().replace("\"content_index\":0","\"content_index\":\"zero\"")))}}
    @Test fun malformedItemAssociationRejected() {val r=reader();r.consume(frame(added()));assertThrows(ResponseProtocolFailure::class.java) {r.consume(frame(delta().replace("\"item_id\":\"item-0\"","\"item_id\":4")))}}
    @Test fun deltaAfterDoneRejected() {val r=reader();r.consume(frame(added()));r.consume(frame("""{"type":"response.reasoning_text.done","output_index":0,"text":"done"}"""));assertThrows(ResponseProtocolFailure::class.java) {r.consume(frame(delta()))}}
    @Test fun repeatedFinalizedReasoningDoesNotDuplicate() {val r=reader();r.consume(frame(added()));val done=frame("""{"type":"response.reasoning_text.done","output_index":0,"text":"done"}""");assertTrue(r.consume(done).isNotEmpty());assertTrue(r.consume(done).isEmpty())}
    @Test fun emptyReasoningPartsStillBounded() {val r=reader();r.consume(frame(added()));repeat(256) {r.consume(frame(delta("").replace("\"content_index\":0","\"content_index\":$it")))};assertThrows(ResponseProtocolFailure::class.java) {r.consume(frame(delta("").replace("\"content_index\":0","\"content_index\":256")))}}
    @Test fun finalizedEnvelopeSeparatesReasoningAndAnswer() {
        val events=reader().consume(frame("""{"type":"response.completed","response":{"id":"synthetic","status":"completed","output":[{"id":"r","type":"reasoning","content":[{"type":"reasoning_text","text":"VISIBLE"}]},{"id":"a","type":"message","role":"assistant","content":[{"type":"output_text","text":"ANSWER"}]}]}}"""))
        assertEquals("VISIBLE",events.filterIsInstance<LlmEvent.ReasoningDelta>().single().text)
        assertEquals("ANSWER",events.filterIsInstance<LlmEvent.TextDelta>().single().text);assertTrue(events.last() is LlmEvent.Completed)
    }
    @Test fun finalizedOpenaiNeverExposesRawReasoning() {
        val events=reader(ReasoningReadMode.OpenAiSummary).consume(frame("""{"type":"response.completed","response":{"id":"synthetic","status":"completed","output":[{"id":"r","type":"reasoning","content":[{"type":"reasoning_text","text":"PRIVATE_RAW"}],"summary":[{"type":"summary_text","text":"SUMMARY"}]},{"id":"a","type":"message","role":"assistant","content":[{"type":"output_text","text":"ANSWER"}]}]}}"""))
        assertEquals("SUMMARY",events.filterIsInstance<LlmEvent.ReasoningDelta>().single().text)
        assertFalse(events.filterIsInstance<LlmEvent.ReasoningDelta>().any {it.text.contains("PRIVATE_RAW")})
    }
    @Test fun thinkingRequestActualMockPostAndDiagnosticsRemainSanitized()=runBlocking {
        MockWebServer().use {s ->s.start()
            s.enqueue(MockResponse().setBody("""{"object":"list","data":[{"object":"model","id":"returned-model"}]}"""))
            val body=listOf(added(),delta(),"""{"type":"response.reasoning_text.done","output_index":0,"text":"PRIVATE_REASONING"}""",added("message",1),
                delta("ANSWER","response.output_text.delta",1),"""{"type":"response.completed","response":{"id":"private-id","status":"completed","output":[]}}""").joinToString("") {"data: $it\n\n"}
            s.enqueue(MockResponse().setBody(body))
            val c=DeepSeekCredentials(MemoryBlob(),testBox());c.replace("PRIVATE_SYNTHETIC_KEY")
            val p=DeepSeekProvider(c,NetworkClient(allowLocalTestHttp=true),s.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'))
            p.listModels();val e=p.streamResponse(LlmRequest("returned-model",input,reasoning=ReasoningPreference.High,observeHttp=true)).toList()
            assertTrue(e.first() is LlmEvent.HttpReady);assertTrue(e.last() is LlmEvent.Completed)
            assertEquals("PRIVATE_REASONING",e.filterIsInstance<LlmEvent.ReasoningDelta>().joinToString("") {it.text})
            assertEquals("ANSWER",e.filterIsInstance<LlmEvent.TextDelta>().joinToString("") {it.text})
            s.takeRequest();val post=s.takeRequest();val root=Json.parseToJsonElement(post.body.readUtf8()).jsonObject
            assertEquals("high",root["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content);assertEquals(2,s.requestCount)
            val diagnostics=p.inferenceDiagnostic.value!!.summary();listOf("PRIVATE","ANSWER","Authorization","private-id").forEach {assertFalse(diagnostics.contains(it))}
        }
    }
    @Test fun unsupportedChatgptFieldNeverMakesPost()=runBlocking {
        MockWebServer().use {s ->s.start();s.enqueue(MockResponse().setBody("""{"models":[{"slug":"m","display_name":"M","visibility":"list"}]}"""))
            var record=StoredSession(Registration(Siwc.ISSUER,Secret("synthetic-client"),Secret("synthetic-subject"),"synthetic-host"),
                CredentialSet(Secret("synthetic-access"),Secret("synthetic-refresh"),Secret("synthetic-id"),1000000,Scopes.requested,0),1,CredentialPhase.ACTIVE)
            val tokens=TokenManager(object:CredentialStore {override fun read()=record;override fun write(value:StoredSession) {record=value}},
                object:RefreshEndpoint {override suspend fun refresh(registration:Registration,current:CredentialSet):CredentialSet=error("UNEXPECTED")},TimeSource {0})
            tokens.initialize();val p=ChatGptProvider(tokens,NetworkClient(allowLocalTestHttp=true),s.url("/v1").newBuilder().host("127.0.0.1").build().toString())
            p.listModels();val e=p.streamResponse(LlmRequest("m",input,reasoning=ReasoningPreference.High)).toList()
            assertEquals(ErrorKind.UNSUPPORTED_CAPABILITY,(e.single() as LlmEvent.Failed).error.kind);assertEquals(1,s.requestCount)
        }
    }
}
