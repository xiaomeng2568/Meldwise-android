// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Real accepted adapters and HTTP/SSE, exclusively on local synthetic servers. No live account/key. */
class CollaborateTransportTests {
    private val hidden="SYNTHETIC_VISIBLE_REASONING"
    private val a=ModelRef("chatgpt","a");private val b=ModelRef("deepseek","b")
    private suspend fun chat(server:MockWebServer):ChatGptProvider {
        var stored=StoredSession(Registration(Siwc.ISSUER,Secret("synthetic-client"),Secret("synthetic-subject"),"synthetic-host"),
            CredentialSet(Secret("synthetic-access"),Secret("synthetic-refresh"),Secret("synthetic-id"),1000000,Scopes.requested,0),1,CredentialPhase.ACTIVE)
        val manager=TokenManager(object:CredentialStore {override fun read()=stored;override fun write(value:StoredSession){stored=value}},
            object:RefreshEndpoint {override suspend fun refresh(registration:Registration,current:CredentialSet):CredentialSet=error("UNEXPECTED_REFRESH")},TimeSource {0})
        manager.initialize()
        return ChatGptProvider(manager,NetworkClient(allowLocalTestHttp=true),server.url("/v1").newBuilder().host("127.0.0.1").build().toString()).also {
            it.restoreCatalog(it.parseModels("""{"models":[{"slug":"a","display_name":"A","visibility":"list"}]}"""))
        }
    }
    private fun deep(server:MockWebServer):DeepSeekProvider {
        val key=DeepSeekCredentials(MemoryBlob(),testBox());key.replace("synthetic_private_key")
        return DeepSeekProvider(key,NetworkClient(allowLocalTestHttp=true),server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')).also {
            it.restoreCatalog(it.parseModels("""{"object":"list","data":[{"object":"model","id":"b"},{"object":"model","id":"c"}]}"""))
        }
    }
    private fun stream(text:String,reasoning:Boolean=false)=buildList {
        add("""{"type":"response.created"}""")
        if(reasoning) {
            add("""{"type":"response.output_item.added","output_index":0,"item":{"id":"r","type":"reasoning"}}""")
            add("""{"type":"response.reasoning_text.delta","output_index":0,"content_index":0,"item_id":"r","delta":"$hidden"}""")
        }
        add("""{"type":"response.output_item.added","output_index":1,"item":{"id":"a","type":"message","role":"assistant"}}""")
        add("""{"type":"response.output_text.delta","output_index":1,"content_index":0,"item_id":"a","delta":"$text"}""")
        add("""{"type":"response.completed","response":{"id":"synthetic-response","status":"completed","output":[]}}""")
    }.joinToString("") {"data: $it\n\n"}
    private fun quota()="data: {\"type\":\"response.created\"}\n\ndata: {\"type\":\"error\",\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"SYNTHETIC_PRIVATE_ERROR\"}\n\n"
    private fun repository(primary:ModelRef=a,reviewer:ModelRef=b)=ChatRepository(MemoryBlob(),testBox()).also {
        it.newCollaborate();it.configureCollaborate(CollaborateConfig(CollaborateModel(primary),CollaborateModel(reviewer,preference=if(reviewer.providerId=="deepseek") ReasoningPreference.High else ReasoningPreference.Auto)))
    }
    @Test fun exactThreeActualPostsInOrderAndReasoningNeverCrossesProviders()=runBlocking {
        MockWebServer().use {s1 ->MockWebServer().use {s2 ->s1.start();s2.start()
            s1.enqueue(MockResponse().setBody(stream("INITIAL")));s2.enqueue(MockResponse().setBody(stream("REVIEW",true)));s1.enqueue(MockResponse().setBody(stream("SYNTHESIS")))
            val p1=chat(s1);val p2=deep(s2);val r=repository()
            val result=withTimeout(10000) {CollaborateExecutor(ProviderRegistry(listOf(p1,p2)),r).execute(r.prepareCollaborate("question"),true)}
            assertEquals(CollaborateRoundState.Complete,result.rounds.single().lifecycle)
            assertEquals(2,s1.requestCount);assertEquals(1,s2.requestCount)
            val first=s1.takeRequest();val second=s2.takeRequest();val third=s1.takeRequest()
            listOf(first,second,third).forEach {assertEquals("POST",it.method)}
            val body2=second.body.readUtf8();val body3=third.body.readUtf8()
            assertTrue(body2.contains("INITIAL"));assertTrue(body3.contains("INITIAL") && body3.contains("REVIEW"));assertFalse(body2.contains(hidden));assertFalse(body3.contains(hidden))
            assertEquals(hidden,result.rounds.single().stages[1].reasoning.text)
            assertFalse(body2.contains("store"));assertFalse(body2.contains("instructions"));assertTrue(body2.contains("\"effort\":\"high\""))
            listOf(p1.inferenceDiagnostic.value!!.summary(),p2.inferenceDiagnostic.value!!.summary()).forEach {d ->assertFalse(d.contains(hidden));assertFalse(d.contains("question"));assertFalse(d.contains("synthetic_private_key"))}
        }}
    }
    private fun quotaAt(index:Int)=runBlocking {
        MockWebServer().use {s1 ->MockWebServer().use {s2 ->s1.start();s2.start()
            val primary=if(index==1) b else a;val reviewer=if(index==1) a else b
            if(index>0) (if(primary==a) s1 else s2).enqueue(MockResponse().setBody(stream("INITIAL")))
            if(index>1) (if(reviewer==a) s1 else s2).enqueue(MockResponse().setBody(stream("REVIEW")))
            s1.enqueue(MockResponse().setBody(quota()))
            val p1=chat(s1);val p2=deep(s2);val r=repository(primary,reviewer)
            val result=withTimeout(10000) {CollaborateExecutor(ProviderRegistry(listOf(p1,p2)),r).execute(r.prepareCollaborate("q"),true)}
            val round=result.rounds.single();assertEquals(CollaborateRoundState.Failed,round.lifecycle)
            assertEquals(ErrorKind.PLAN_USAGE_LIMIT,round.stages[index].error);assertEquals(index+1,s1.requestCount+s2.requestCount)
            assertTrue(round.stages.take(index).all {it.state==CollaborateStageState.Complete})
            assertTrue(round.stages.drop(index+1).all {it.state==CollaborateStageState.NotRun})
            val trace=p1.inferenceDiagnostic.value!!;assertEquals(200,trace.httpStatus);assertEquals(ErrorKind.PLAN_USAGE_LIMIT,trace.resultCategory)
            assertEquals(TransportCategory.NONE,trace.transportCategory);assertEquals(1,trace.networkExchangeCount)
            assertFalse(trace.summary().contains("SYNTHETIC_PRIVATE_ERROR"))
            repeat(s1.requestCount) {assertEquals("POST",s1.takeRequest().method)}
            repeat(s2.requestCount) {assertEquals("POST",s2.takeRequest().method)}
            assertNull(s1.takeRequest(20,TimeUnit.MILLISECONDS));assertNull(s2.takeRequest(20,TimeUnit.MILLISECONDS))
        }}
    }
    @Test fun actualHttp200SsePlanLimitStopsInitial() {quotaAt(0)}
    @Test fun actualHttp200SsePlanLimitStopsReview() {quotaAt(1)}
    @Test fun actualHttp200SsePlanLimitStopsSynthesis() {quotaAt(2)}
    @Test fun sameProviderDeepseekUsesThreeAcceptedPostsWithoutSharingDialog()=runBlocking {
        MockWebServer().use {server ->server.start();repeat(3) {server.enqueue(MockResponse().setBody(stream("answer$it")))}
            val p=deep(server);val r=repository(b,ModelRef("deepseek","c"));val plan=r.prepareCollaborate("q")
            assertFalse(plan.requiresSharing)
            val c=withTimeout(8000) {CollaborateExecutor(ProviderRegistry(listOf(p)),r).execute(plan)}
            assertEquals(CollaborateRoundState.Complete,c.rounds.single().lifecycle);assertEquals(3,server.requestCount)
            val models=(0..2).map {Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content}
            assertEquals(listOf("b","c","b"),models)
        }
    }
    @Test fun actualReviewCancellationPreservesInitialAndNeverStartsSynthesis()=runBlocking {
        MockWebServer().use {s1 ->MockWebServer().use {s2 ->s1.start();s2.start()
            s1.enqueue(MockResponse().setBody(stream("KEPT")));s2.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val r=repository();val reg=ProviderRegistry(listOf(chat(s1),deep(s2)))
            val task=launch(Dispatchers.Default) {CollaborateExecutor(reg,r).execute(r.prepareCollaborate("q"),true)}
            assertNotNull(withContext(Dispatchers.IO) {s2.takeRequest(5,TimeUnit.SECONDS)})
            withTimeout(5000) {task.cancelAndJoin()}
            val round=r.activeConversation()!!.rounds.single();assertEquals(CollaborateRoundState.Cancelled,round.lifecycle)
            assertEquals("KEPT",round.stages[0].output);assertEquals(CollaborateStageState.Complete,round.stages[0].state)
            assertEquals(CollaborateStageState.Cancelled,round.stages[1].state);assertEquals(CollaborateStageState.NotRun,round.stages[2].state)
            assertEquals(1,s1.requestCount);assertEquals(1,s2.requestCount)
        }}
    }
}
