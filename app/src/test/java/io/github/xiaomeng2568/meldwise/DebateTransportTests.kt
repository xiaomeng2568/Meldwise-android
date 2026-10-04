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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Accepted real adapters, localhost-only synthetic HTTP/SSE. No external endpoint or real quota. */
class DebateTransportTests {
    private suspend fun chat(server:MockWebServer):ChatGptProvider {
        var stored=StoredSession(Registration(Siwc.ISSUER,Secret("synthetic-client"),Secret("synthetic-subject"),"synthetic-host"),
            CredentialSet(Secret("synthetic-access"),Secret("synthetic-refresh"),Secret("synthetic-id"),1000000,Scopes.requested,0),1,CredentialPhase.ACTIVE)
        val manager=TokenManager(object:CredentialStore {override fun read()=stored;override fun write(value:StoredSession){stored=value}},
            object:RefreshEndpoint {override suspend fun refresh(registration:Registration,current:CredentialSet):CredentialSet=error("NO_REFRESH")},TimeSource {0})
        manager.initialize()
        return ChatGptProvider(manager,NetworkClient(allowLocalTestHttp=true),server.url("/v1").newBuilder().host("127.0.0.1").build().toString()).also {
            it.restoreCatalog(it.parseModels("""{"models":[{"slug":"b","display_name":"B","visibility":"list"}]}"""))
        }
    }
    private fun deep(server:MockWebServer):DeepSeekProvider {
        val credentials=DeepSeekCredentials(MemoryBlob(),testBox());credentials.replace("synthetic_key")
        return DeepSeekProvider(credentials,NetworkClient(allowLocalTestHttp=true),server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')).also {
            it.restoreCatalog(it.parseModels("""{"object":"list","data":[{"object":"model","id":"a"},{"object":"model","id":"judge"}]}"""))
        }
    }
    private fun stream(text:String,reasoning:Boolean=false)=buildList {
        add("""{"type":"response.created"}""")
        if(reasoning) {
            add("""{"type":"response.output_item.added","output_index":0,"item":{"id":"r","type":"reasoning"}}""")
            add("""{"type":"response.reasoning_text.delta","output_index":0,"content_index":0,"item_id":"r","delta":"LOCAL_REASONING_ONLY"}""")
        }
        add("""{"type":"response.output_item.added","output_index":1,"item":{"id":"a","type":"message","role":"assistant"}}""")
        add("""{"type":"response.output_text.delta","output_index":1,"content_index":0,"item_id":"a","delta":"$text"}""")
        add("""{"type":"response.completed","response":{"id":"synthetic-response","status":"completed","output":[]}}""")
    }.joinToString("") {"data: $it\n\n"}
    private fun quota()="data: {\"type\":\"response.created\"}\n\ndata: {\"type\":\"error\",\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"PRIVATE_ERROR_BODY\"}\n\n"
    private fun repo()=ChatRepository(MemoryBlob(),testBox()).also {it.newDebate();it.configureDebate(DebateFixtures.config)}
    @Test fun bothActualHttpWavesUseBarrierAndJudgeFivePosts()=runBlocking {
        MockWebServer().use {ds->MockWebServer().use {cg->ds.start();cg.start()
            val gates=listOf(CountDownLatch(2),CountDownLatch(2));val timeouts=AtomicInteger()
            fun dispatcher(texts:List<String>,reasoning:Boolean)=object:Dispatcher() {
                val count=AtomicInteger()
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val index=count.getAndIncrement()
                    if(index<2) {gates[index].countDown();if(!gates[index].await(5,TimeUnit.SECONDS)) timeouts.incrementAndGet()}
                    return MockResponse().setBody(stream(texts[index],reasoning))
                }
            }
            ds.dispatcher=dispatcher(listOf("INITIAL_A","REVIEW_B","JUDGE"),true)
            cg.dispatcher=dispatcher(listOf("INITIAL_B","REVIEW_A"),false)
            val p1=deep(ds);val p2=chat(cg);val r=repo()
            val c=withTimeout(15000) {DebateExecutor(ProviderRegistry(listOf(p1,p2)),r).execute(r.prepareDebate("QUESTION"),true)}
            assertEquals(0,timeouts.get());assertEquals(DebateRoundState.Complete,c.debateRounds.single().lifecycle)
            assertEquals(3,ds.requestCount);assertEquals(2,cg.requestCount)
            val dsPosts=List(3) {ds.takeRequest()};val cgPosts=List(2) {cg.takeRequest()}
            (dsPosts+cgPosts).forEach {assertEquals("POST",it.method)}
            val dsBodies=dsPosts.map {it.body.readUtf8()};val cgBodies=cgPosts.map {it.body.readUtf8()}
            assertEquals(listOf("a","a","judge"),dsBodies.map {Json.parseToJsonElement(it).jsonObject["model"]!!.jsonPrimitive.content})
            assertEquals(listOf("b","b"),cgBodies.map {Json.parseToJsonElement(it).jsonObject["model"]!!.jsonPrimitive.content})
            listOf(dsBodies[1],cgBodies[1],dsBodies[2]).forEach {assertTrue(it.contains("INITIAL_A") && it.contains("INITIAL_B"));assertFalse(it.contains("LOCAL_REASONING_ONLY"));assertFalse(it.contains("PRIVATE_MODEL"))}
            assertTrue(dsBodies[2].contains("REVIEW_A") && dsBodies[2].contains("REVIEW_B"))
            assertTrue(c.debateRounds.single().stages.filter {it.model.ref.providerId=="deepseek"}.all {it.reasoning.text=="LOCAL_REASONING_ONLY"})
            assertEquals(1,p1.inferenceDiagnostic.value!!.networkExchangeCount);assertEquals(1,p2.inferenceDiagnostic.value!!.networkExchangeCount)
        }}
    }
    private fun quotaAtReview(review:Boolean)=runBlocking {
        MockWebServer().use {ds->MockWebServer().use {cg->ds.start();cg.start()
            ds.enqueue(MockResponse().setBody(stream("INITIAL_A")))
            if(review) {cg.enqueue(MockResponse().setBody(stream("INITIAL_B")));ds.enqueue(MockResponse().setBody(stream("REVIEW_B")))}
            cg.enqueue(MockResponse().setBody(quota()))
            val p=chat(cg);val r=repo();val c=withTimeout(10000) {DebateExecutor(ProviderRegistry(listOf(deep(ds),p)),r).execute(r.prepareDebate("q"),true)}
            val type=if(review) DebateStageType.REVIEW_B_OF_A else DebateStageType.INITIAL_B
            assertEquals(DebateStageState.Failed,c.debateRounds.single().stage(type).state);assertEquals(ErrorKind.PLAN_USAGE_LIMIT,c.debateRounds.single().stage(type).error)
            assertEquals(if(review) 4 else 2,ds.requestCount+cg.requestCount)
            assertEquals(200,p.inferenceDiagnostic.value!!.httpStatus);assertEquals(ErrorKind.PLAN_USAGE_LIMIT,p.inferenceDiagnostic.value!!.resultCategory)
            assertEquals(TransportCategory.NONE,p.inferenceDiagnostic.value!!.transportCategory);assertEquals(1,p.inferenceDiagnostic.value!!.networkExchangeCount)
            assertFalse(p.inferenceDiagnostic.value!!.summary().contains("PRIVATE_ERROR_BODY"))
            assertEquals(DebateStageState.NotRun,c.debateRounds.single().stage(DebateStageType.JUDGE).state)
        }}
    }
    @Test fun actualHttp200SsePlanLimitInitialNoReplay() {quotaAtReview(false)}
    @Test fun actualHttp200SsePlanLimitReviewNoJudge() {quotaAtReview(true)}
    @Test fun actualCancelClosesBothNoResponseStreams()=runBlocking {
        MockWebServer().use {ds->MockWebServer().use {cg->ds.start();cg.start()
            ds.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));cg.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val r=repo();val registry=ProviderRegistry(listOf(deep(ds),chat(cg)))
            val task=launch(Dispatchers.Default) {DebateExecutor(registry,r).execute(r.prepareDebate("q"),true)}
            assertNotNull(withContext(Dispatchers.IO) {ds.takeRequest(5,TimeUnit.SECONDS)});assertNotNull(withContext(Dispatchers.IO) {cg.takeRequest(5,TimeUnit.SECONDS)})
            withTimeout(5000) {task.cancelAndJoin()}
            assertEquals(DebateRoundState.Cancelled,r.activeConversation()!!.debateRounds.single().lifecycle)
            assertTrue(r.activeConversation()!!.debateRounds.single().stages.take(2).all {it.state==DebateStageState.Cancelled})
            assertEquals(1,ds.requestCount);assertEquals(1,cg.requestCount)
        }}
    }
    @Test fun actualReviewCancelKeepsBothInitialOutputs()=runBlocking {
        MockWebServer().use {ds->MockWebServer().use {cg->ds.start();cg.start()
            ds.enqueue(MockResponse().setBody(stream("KEPT_A")));cg.enqueue(MockResponse().setBody(stream("KEPT_B")))
            ds.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));cg.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val r=repo();val registry=ProviderRegistry(listOf(deep(ds),chat(cg)));val task=launch(Dispatchers.Default) {DebateExecutor(registry,r).execute(r.prepareDebate("q"),true)}
            repeat(2) {assertNotNull(withContext(Dispatchers.IO) {ds.takeRequest(5,TimeUnit.SECONDS)});assertNotNull(withContext(Dispatchers.IO) {cg.takeRequest(5,TimeUnit.SECONDS)})}
            withTimeout(5000) {task.cancelAndJoin()};val round=r.activeConversation()!!.debateRounds.single()
            assertEquals(listOf("KEPT_A","KEPT_B"),round.stages.take(2).map {it.output});assertTrue(round.stages.take(2).all {it.state==DebateStageState.Complete})
            assertEquals(DebateStageState.NotRun,round.stage(DebateStageType.JUDGE).state);assertEquals(4,ds.requestCount+cg.requestCount)
        }}
    }
    @Test fun actualMalformedSseStopsRoundWithoutPostRetry()=runBlocking {
        MockWebServer().use {ds->MockWebServer().use {cg->ds.start();cg.start()
            ds.enqueue(MockResponse().setBody("data: {not-json}\n\n"));cg.enqueue(MockResponse().setBody(stream("B")))
            val r=repo();val c=withTimeout(10000) {DebateExecutor(ProviderRegistry(listOf(deep(ds),chat(cg))),r).execute(r.prepareDebate("q"),true)}
            assertEquals(DebateRoundState.Failed,c.debateRounds.single().lifecycle);assertEquals(ErrorKind.PROTOCOL,c.debateRounds.single().stage(DebateStageType.INITIAL_A).error)
            assertTrue(ds.requestCount+cg.requestCount<=2);assertEquals(1,ds.requestCount)
        }}
    }
}
