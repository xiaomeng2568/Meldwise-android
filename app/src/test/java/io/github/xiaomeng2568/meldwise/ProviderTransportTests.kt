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

class ProviderTransportTests {
    private suspend fun provider(server:MockWebServer):ChatGptProvider {
        var record=StoredSession(Registration(Siwc.ISSUER,Secret("synthetic-client"),Secret("synthetic-subject"),"synthetic-host"),
            CredentialSet(Secret("synthetic-access"),Secret("synthetic-refresh"),Secret("synthetic-id"),1000000,Scopes.requested,0),1,CredentialPhase.ACTIVE)
        val manager=TokenManager(object:CredentialStore {override fun read()=record;override fun write(value:StoredSession) {record=value}},
            object:RefreshEndpoint {override suspend fun refresh(registration:Registration,current:CredentialSet):CredentialSet=error("UNEXPECTED_REFRESH")},TimeSource {0})
        manager.initialize()
        return ChatGptProvider(manager,NetworkClient(allowLocalTestHttp=true),server.url("/v1").newBuilder().host("127.0.0.1").build().toString())
    }
    private fun catalog()=MockResponse().setBody("""{"models":[{"slug":"synthetic-model","display_name":"Synthetic Model","visibility":"list"},{"slug":"hidden-model","display_name":"Hidden","visibility":"hide"}]}""")
    private fun frame(type:String,data:String)="event: $type\ndata: $data\n\n"
    private fun stream():String=frame("response.created","""{"type":"response.created"}""")+
        frame("response.output_item.added","""{"type":"response.output_item.added","output_index":0,"item":{"id":"synthetic-item","type":"message","role":"assistant"}}""")+
        frame("response.output_text.delta","""{"type":"response.output_text.delta","output_index":0,"content_index":0,"item_id":"synthetic-item","delta":"synthetic reply"}""")+
        frame("response.output_text.done","""{"type":"response.output_text.done","output_index":0,"content_index":0,"item_id":"synthetic-item","text":"synthetic reply"}""")+
        frame("response.completed","""{"type":"response.completed","response":{"id":"synthetic-response","status":"completed","output":[]}}""")
    @Test fun modelCatalogThenOneExplicitPostNoFallback()=runBlocking {
        MockWebServer().use { server->server.start();server.enqueue(catalog());server.enqueue(MockResponse().setBody(stream()))
            val provider=provider(server);val models=provider.listModels();assertEquals(listOf("synthetic-model"),models.map {it.id})
            val events=provider.streamResponse(LlmRequest(models.single().id,listOf(LlmMessage(MessageRole.USER,"synthetic input")))).toList()
            assertTrue(events.last() is LlmEvent.Completed);assertEquals("synthetic reply",events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") {it.text})
            val get=server.takeRequest()!!;val post=server.takeRequest()!!
            assertEquals("GET",get.method);assertEquals("POST",post.method);assertEquals(2,server.requestCount)
            val payload=post.body.readUtf8();assertTrue(payload.contains("\"store\":false"));assertFalse(payload.contains("max_output_tokens"));assertFalse(payload.contains("previous_response_id"))
        }
    }
    @Test fun http401DoesNotReplayPostOrRefresh()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(catalog());server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val provider=provider(server);provider.listModels()
            val events=provider.streamResponse(LlmRequest("synthetic-model",listOf(LlmMessage(MessageRole.USER,"synthetic")))).toList()
            assertTrue(events.single() is LlmEvent.Failed);assertEquals(2,server.requestCount)
        }
    }
    @Test fun eofAfterPartialIsIncomplete()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(catalog());server.enqueue(MockResponse().setBody(stream().substringBefore("event: response.completed")))
            val provider=provider(server);provider.listModels()
            val events=provider.streamResponse(LlmRequest("synthetic-model",listOf(LlmMessage(MessageRole.USER,"synthetic")))).toList()
            assertTrue(events.last() is LlmEvent.Incomplete);assertFalse(events.any {it is LlmEvent.Completed})
        }
    }
    @Test fun unsupportedParametersNeverSendPost()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(catalog())
            val provider=provider(server);provider.listModels()
            val events=provider.streamResponse(LlmRequest("synthetic-model",listOf(LlmMessage(MessageRole.USER,"synthetic")),temperature=0.5)).toList()
            assertEquals(ErrorKind.UNSUPPORTED_CAPABILITY,(events.single() as LlmEvent.Failed).error.kind);assertEquals(1,server.requestCount)
        }
    }
    @Test fun cancellationStopsStreamWithoutAnotherRequest()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(catalog());server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val provider=provider(server);provider.listModels();server.takeRequest()
            val job=launch(Dispatchers.Default) {provider.streamResponse(LlmRequest("synthetic-model",listOf(LlmMessage(MessageRole.USER,"synthetic")))).collect()}
            assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));withTimeout(5000) {job.cancelAndJoin()};assertEquals(2,server.requestCount)
        }
    }
    @Test fun capabilityIntersectionDoesNotInventSupport() {
        val provider=ProviderCapability(setOf(Capability.STREAMING,Capability.TOOLS));val model=ProviderCapability(setOf(Capability.STREAMING,Capability.IMAGE_INPUT))
        val auth=ProviderCapability(setOf(Capability.STREAMING));assertEquals(setOf(Capability.STREAMING),provider.intersect(model,auth).supported)
    }
}
