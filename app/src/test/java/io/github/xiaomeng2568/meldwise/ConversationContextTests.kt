// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.network.NetworkClient
import io.github.xiaomeng2568.meldwise.security.DeepSeekCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ConversationContextTests {
    private val ref=ModelRef("deepseek","b")
    private fun pair(index:Int,u:String="u$index",a:String="a$index",state:MessageState=MessageState.COMPLETED)=listOf(
        ChatMessage("u$index",null,MessageRole.USER,u,MessageState.COMPLETED,order=index*2,modelRef=ref),
        ChatMessage("a$index","u$index",MessageRole.ASSISTANT,a,state,ReasoningRecord("PRIVATE_REASONING",ReasoningContent.ProviderVisibleReasoning),order=index*2+1,modelRef=ref))
    @Test fun currentMessageAlwaysLast() {val result=ConversationContextBuilder().build(pair(0),"now");assertEquals(MessageRole.USER,result.messages.last().role);assertEquals("now",result.messages.last().text)}
    @Test fun trimsOldestWholePairsByBytes() {val h=(0..4).flatMap {pair(it)};val p=ConversationContextBuilder(ConversationContextPolicy(budgetBytes=10));assertEquals(listOf("u3","a3","u4","a4","N"),p.build(h,"N").messages.map {it.text})}
    @Test fun trimsOldestPairsByCount() {val h=(0..4).flatMap {pair(it)};assertEquals(listOf("u4","a4","N"),ConversationContextBuilder(ConversationContextPolicy(1)).build(h,"N").messages.map {it.text})}
    @Test fun budgetSelectionDeterministic() {val b=ConversationContextBuilder(ConversationContextPolicy(budgetBytes=20));val h=(0..9).flatMap {pair(it)};assertEquals(b.build(h,"N").messages.map {it.text},b.build(h,"N").messages.map {it.text})}
    @Test fun currentPreservedAboveSoftBudgetButStillHardBounded() {assertEquals("中文😺",ConversationContextBuilder(ConversationContextPolicy(budgetBytes=1)).build(pair(0),"中文😺").messages.single().text)}
    @Test fun utf8BoundDoesNotSplitContent() {val text="中文😺";val b=ConversationContextBuilder(ConversationContextPolicy(budgetBytes=25));val result=b.build(pair(0,text,text),"now");assertEquals(listOf(text,text,"now"),result.messages.map {it.text});assertEquals(23,result.messages.sumOf {it.text.toByteArray().size})}
    @Test fun cannotCreateUnboundedPolicy() {assertThrows(IllegalArgumentException::class.java) {ConversationContextPolicy(21)};assertThrows(IllegalArgumentException::class.java) {ConversationContextPolicy(budgetBytes=131073)}}
    @Test fun tooLongCurrentRejectedBeforePersistence() {val blob=MemoryBlob();val r=ChatRepository(blob,testBox());assertThrows(IllegalArgumentException::class.java) {r.begin("x".repeat(32769))};assertNull(blob.read())}
    @Test fun invalidSurrogateCannotBeSilentlyReplaced() {assertThrows(Exception::class.java) {ConversationContextBuilder().build(emptyList(),"\uD800")}}
    @Test fun developerMessageNeverSentAsHistory() {val h=listOf(ChatMessage("debug",null,MessageRole.DEVELOPER,"INTERNAL",MessageState.COMPLETED))+pair(0);assertFalse(ConversationContextBuilder().build(h,"now").messages.any {it.text=="INTERNAL"})}
    @Test fun unpairedUserNeverLeakedAsContext() {assertEquals(1,ConversationContextBuilder().build(pair(0).take(1),"now").messages.size)}
    @Test fun unrelatedAssistantCannotPairWithUser() {val u=pair(0).first();val a=ChatMessage("a","wrong-parent",MessageRole.ASSISTANT,"no",MessageState.COMPLETED);assertEquals(1,ConversationContextBuilder().build(listOf(u,a),"now").messages.size)}
    @Test fun reasoningStageIsNotOrdinaryContext() {val u=pair(0).first();val a=ChatMessage("a",u.id,MessageRole.ASSISTANT,"stage",MessageState.COMPLETED,stage=1);assertEquals(1,ConversationContextBuilder().build(listOf(u,a),"now").messages.size)}
    @Test fun allIncompleteKindsExcludedWithOrphanUser() {listOf(MessageState.CANCELLED,MessageState.FAILED,MessageState.INCOMPLETE,MessageState.INTERRUPTED,MessageState.STREAMING,MessageState.PENDING).forEach {assertEquals(1,ConversationContextBuilder().build(pair(0,state=it),"now").messages.size)}}
    @Test fun noProcessingOrReasoningMetadataInContext() {val context=ConversationContextBuilder().build(pair(0),"now");assertEquals(listOf("u0","a0","now"),context.messages.map {it.text});assertFalse(context.toString().contains("PRIVATE"))}
    private fun provider(server:MockWebServer):DeepSeekProvider {
        val key=DeepSeekCredentials(MemoryBlob(),testBox());key.replace("synthetic_test_key")
        return DeepSeekProvider(key,NetworkClient(allowLocalTestHttp=true),server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')).also {
            it.restoreCatalog(it.parseModels("""{"object":"list","data":[{"object":"model","id":"b"}]}"""))
        }
    }
    private fun response()=MockResponse().setBody(
        "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"i\",\"type\":\"message\",\"role\":\"assistant\"}}\n\n"+
        "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"item_id\":\"i\",\"delta\":\"answer\"}\n\n"+
        "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"synthetic-response\",\"status\":\"completed\",\"output\":[]}}\n\n")
    @Test fun explicitContinuationMakesOnePostAndSerializesOnlyVisibleContext()=runBlocking {
        MockWebServer().use {s ->s.start();val p=provider(s);val r=ChatRepository(MemoryBlob(),testBox());r.activate(ref)
            val first=r.begin("old").first;r.update(first,"old answer",MessageState.COMPLETED,ReasoningRecord("PRIVATE_REASONING",ReasoningContent.ProviderVisibleReasoning))
            s.enqueue(response());val (id,context)=r.begin("next")
            val events=withTimeout(5000) {p.streamResponse(LlmRequest(ref.modelId,context)).toList()}
            assertTrue(events.last() is LlmEvent.Completed);r.update(id,"answer",MessageState.COMPLETED)
            val request=s.takeRequest(1,TimeUnit.SECONDS)!!;assertEquals("POST",request.method)
            val payload=request.body.readUtf8();assertFalse(payload.contains("PRIVATE_REASONING"))
            assertEquals(listOf("old","old answer","next"),Json.parseToJsonElement(payload).jsonObject["input"]!!.jsonArray.map {it.jsonObject["content"]!!.jsonPrimitive.content})
            assertEquals(1,s.requestCount);assertNull(s.takeRequest(30,TimeUnit.MILLISECONDS));assertEquals(1,r.sessions().size)
        }
    }
    @Test fun cancelCrossProviderConsentMakesZeroRequests()=runBlocking {
        MockWebServer().use {s ->s.start();val p=provider(s);val r=ChatRepository(MemoryBlob(),testBox());r.activate(ModelRef("chatgpt","a"));val first=r.begin("old").first;r.update(first,"answer",MessageState.COMPLETED);r.activate(ref)
            val plan=r.prepare("new");assertTrue(plan.requiresSharing)
            assertThrows(ContextSharingRequired::class.java) {r.beginPrepared(plan)}
            assertEquals(0,s.requestCount);assertEquals(2,r.load().size);assertEquals(ProviderStatus.READY,p.validateConnection())
        }
    }
    @Test fun acceptedCrossProviderSendExcludesReasoningAndDoesNotReplay()=runBlocking {
        MockWebServer().use {s ->s.start();val p=provider(s);val r=ChatRepository(MemoryBlob(),testBox());r.activate(ModelRef("chatgpt","a"));val first=r.begin("old").first;r.update(first,"answer",MessageState.COMPLETED,ReasoningRecord("PRIVATE_SUMMARY"));r.activate(ref)
            val plan=r.prepare("new");s.enqueue(response());val context=r.beginPrepared(plan,true).second
            assertTrue(p.streamResponse(LlmRequest(ref.modelId,context)).toList().last() is LlmEvent.Completed)
            assertThrows(IllegalArgumentException::class.java) {r.beginPrepared(plan,true)}
            val payload=s.takeRequest().body.readUtf8();assertFalse(payload.contains("PRIVATE_SUMMARY"));assertTrue(payload.contains("old"));assertEquals(1,s.requestCount)
        }
    }
    @Test fun restoreAndDraftSelectionDoNotContactProvider()=runBlocking {
        MockWebServer().use {s ->s.start();provider(s);val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.activate(ref);val id=r.begin("old").first;r.update(id,"partial",MessageState.STREAMING);val restored=ChatRepository(blob,box);restored.load();restored.activate(ref);restored.prepare("not sent");assertEquals(0,s.requestCount);assertEquals(MessageState.INTERRUPTED,restored.load()[1].state)}
    }
}
