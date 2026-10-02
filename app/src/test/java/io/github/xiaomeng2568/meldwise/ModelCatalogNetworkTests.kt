package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.CredentialStore
import io.github.xiaomeng2568.meldwise.ui.errorLabel
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.*
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class ModelCatalogNetworkTests {
    private val marker="DO_NOT_EXPORT_SYNTHETIC_SECRET"
    private suspend fun provider(server:MockWebServer,connected:Boolean=true):ChatGptProvider {
        var record:StoredSession?=if(connected) StoredSession(
            Registration(Siwc.ISSUER,Secret(marker),Secret(marker),marker),
            CredentialSet(Secret(marker),Secret(marker),Secret(marker),1000000,Scopes.requested,0),
            1,CredentialPhase.ACTIVE) else null
        val manager=TokenManager(object:CredentialStore {
            override fun read()=record
            override fun write(value:StoredSession) {record=value}
        },object:RefreshEndpoint {
            override suspend fun refresh(registration:Registration,current:CredentialSet):CredentialSet=error("UNEXPECTED_REFRESH")
        },TimeSource {0})
        manager.initialize()
        return ChatGptProvider(manager,NetworkClient(allowLocalTestHttp=true),
            server.url("/v1").newBuilder().host("127.0.0.1").build().toString())
    }
    private fun assertSafe(d:ModelCatalogDiagnostic) {
        val serialized=d.toString()+d.summary()
        listOf(marker,"Authorization","Bearer","https://","127.0.0.1","raw-exception-message").forEach {
            assertFalse("Diagnostic leaked a forbidden synthetic value",serialized.contains(it))
        }
        assertTrue(d.visibleModelCount in 0..1024);assertTrue(d.parsedModelCount in 0..1024)
    }
    private suspend fun failure(provider:ChatGptProvider):LlmError = try {
        provider.listModels();error("EXPECTED_FAILURE")
    } catch(f:ProviderFailure) { assertFalse(f.toString().contains(marker));f.error }
    private fun transport(error:IOException,expected:TransportCategory,kind:ErrorKind) {
        val category=transportCategory(error)
        assertEquals(expected,category)
        val outcome=if(category==TransportCategory.TIMEOUT) Outcome.TIMEOUT else Outcome.NETWORK
        val fault=NetworkFault(outcome,false,category)
        assertEquals(kind,catalogNetworkError(fault).kind)
        assertNull(fault.cause);assertFalse(fault.toString().contains("raw-exception-message"))
    }
    @Test fun dnsFailureIsNetworkDns()=transport(UnknownHostException("raw-exception-message"),TransportCategory.NETWORK_DNS,ErrorKind.NETWORK)
    @Test fun connectFailureIsNetworkConnect() {
        transport(ConnectException("raw-exception-message"),TransportCategory.NETWORK_CONNECT,ErrorKind.NETWORK)
        transport(NoRouteToHostException("raw-exception-message"),TransportCategory.NETWORK_CONNECT,ErrorKind.NETWORK)
    }
    @Test fun tlsFailuresAreNetworkTls() {
        transport(SSLHandshakeException("raw-exception-message"),TransportCategory.NETWORK_TLS,ErrorKind.NETWORK)
        transport(SSLPeerUnverifiedException("raw-exception-message"),TransportCategory.NETWORK_TLS,ErrorKind.NETWORK)
        transport(IOException(CertificateException("raw-exception-message")),TransportCategory.NETWORK_TLS,ErrorKind.NETWORK)
    }
    @Test fun timeoutIsNotGenericNetwork() {
        transport(SocketTimeoutException("raw-exception-message"),TransportCategory.TIMEOUT,ErrorKind.TIMEOUT)
        transport(InterruptedIOException("raw-exception-message"),TransportCategory.TIMEOUT,ErrorKind.TIMEOUT)
    }
    @Test fun genericIoIsNetworkIo()=transport(IOException("raw-exception-message"),TransportCategory.NETWORK_IO,ErrorKind.NETWORK)

    private fun httpError(status:Int,code:String?,kind:ErrorKind,category:CatalogFailure)=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setResponseCode(status)
                .setBody("""{"error":{"code":"${code ?: "unknown"}","message":"$marker"}}"""))
            val p=provider(server);assertEquals(kind,failure(p).kind)
            val d=requireNotNull(p.catalogDiagnostic)
            assertTrue(d.tokenAvailable && d.requestCreated && d.requestStarted && d.httpResponseReceived && d.bodyReadCompleted)
            assertEquals(status,d.httpStatus);assertEquals(CatalogStage.HTTP,d.failureStage)
            assertEquals(category,d.failureCategory);assertFalse(d.modelsArrayFound)
            assertSafe(d);assertEquals(1,server.requestCount)
        }
    }
    @Test fun http401IsAuthentication()=httpError(401,null,ErrorKind.AUTHENTICATION,CatalogFailure.AUTHENTICATION)
    @Test fun http403ScopeIsAuthorization()=httpError(403,"insufficient_scope",ErrorKind.AUTHORIZATION,CatalogFailure.AUTHORIZATION)
    @Test fun http429IsRateLimit()=httpError(429,null,ErrorKind.RATE_LIMIT,CatalogFailure.HTTP_ERROR)

    private fun protocol(body:String,json:Boolean,array:Boolean,protocol:CatalogProtocol)=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(body))
            val p=provider(server);val error=failure(p)
            assertEquals(ErrorKind.PROTOCOL,error.kind);assertFalse(error.isRetryable)
            val d=requireNotNull(p.catalogDiagnostic)
            assertEquals(200,d.httpStatus);assertTrue(d.httpResponseReceived && d.bodyReadCompleted)
            assertEquals(json,d.jsonParsed);assertEquals(array,d.modelsArrayFound)
            assertEquals(protocol,d.protocolCategory);assertEquals(TransportCategory.NONE,d.transportCategory)
            assertSafe(d);assertEquals(1,server.requestCount)
        }
    }
    @Test fun malformedJsonIsProtocolJson()=protocol("{ $marker",false,false,CatalogProtocol.PROTOCOL_JSON)
    @Test fun missingModelsIsProtocolCatalog()=protocol("""{"data":[],"unknown":"$marker"}""",true,false,CatalogProtocol.PROTOCOL_MODEL_CATALOG)
    @Test fun wrongModelsTypeIsProtocolCatalog()=protocol("""{"models":{}}""",true,false,CatalogProtocol.PROTOCOL_MODEL_CATALOG)
    @Test fun nonObjectRootIsProtocolCatalog()=protocol("[]",true,false,CatalogProtocol.PROTOCOL_MODEL_CATALOG)
    @Test fun invalidEntryIsProtocolCatalog()=protocol("""{"models":[{"visibility":"list","slug":"valid"}]}""",true,true,CatalogProtocol.PROTOCOL_MODEL_CATALOG)
    @Test fun numericSlugCannotBecomeString()=protocol("""{"models":[{"visibility":"list","slug":123,"display_name":"Valid"}]}""",true,true,CatalogProtocol.PROTOCOL_MODEL_CATALOG)
    @Test fun invalidSlugIsRejected()=protocol("""{"models":[{"visibility":"list","slug":"invalid slug","display_name":"Valid"}]}""",true,true,CatalogProtocol.PROTOCOL_MODEL_CATALOG)
    @Test fun duplicateSlugsAreRejected()=protocol("""{"models":[{"visibility":"list","slug":"same","display_name":"One"},{"visibility":"list","slug":"same","display_name":"Two"}]}""",true,true,CatalogProtocol.PROTOCOL_MODEL_CATALOG)
    @Test fun nonObjectEntryIsRejected()=protocol("""{"models":[123]}""",true,true,CatalogProtocol.PROTOCOL_MODEL_CATALOG)
    @Test fun validCatalogKeepsOrderAndFiltersVisibility()=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("""{"models":[
                {"visibility":"list","slug":"second","display_name":"$marker"},
                {"visibility":"hide","slug":"hidden","display_name":"Hidden"},
                {"slug":"unlisted"},
                {"visibility":"list","slug":"first","display_name":"First","unknown":"$marker"}]}"""))
            val p=provider(server);val models=p.listModels()
            assertEquals(listOf("second","first"),models.map {it.id})
            assertEquals(marker,models.first().displayName)
            val d=requireNotNull(p.catalogDiagnostic)
            assertEquals(2,d.visibleModelCount);assertEquals(2,d.parsedModelCount)
            assertTrue(d.modelsArrayFound && d.jsonParsed);assertEquals(CatalogFailure.NONE,d.failureCategory)
            assertSafe(d);assertEquals(1,server.requestCount)
        }
    }
    @Test fun emptyVisibleCatalogIsNotNetwork()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"models":[]}"""))
            val p=provider(server);assertTrue(p.listModels().isEmpty())
            assertEquals(CatalogFailure.NONE,p.catalogDiagnostic?.failureCategory);assertEquals(1,server.requestCount)
        }
    }
    @Test fun missingCredentialNeverStartsRequest()=runBlocking {
        MockWebServer().use {server->server.start()
            val p=provider(server,connected=false);assertEquals(ErrorKind.AUTHENTICATION,failure(p).kind)
            val d=requireNotNull(p.catalogDiagnostic)
            assertFalse(d.tokenAvailable || d.requestCreated || d.requestStarted || d.httpResponseReceived)
            assertEquals(CatalogStage.CREDENTIAL,d.failureStage);assertEquals(0,server.requestCount);assertSafe(d)
        }
    }
    @Test fun interruptedBodyIsTransportFailureNotJsonFailure()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("x".repeat(20000))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val p=provider(server);assertEquals(ErrorKind.NETWORK,failure(p).kind)
            val d=requireNotNull(p.catalogDiagnostic)
            assertTrue(d.httpResponseReceived);assertEquals(200,d.httpStatus)
            assertFalse(d.bodyReadCompleted || d.jsonParsed);assertEquals(CatalogStage.BODY_READ,d.failureStage)
            assertEquals(TransportCategory.NETWORK_IO,d.transportCategory);assertSafe(d);assertEquals(1,server.requestCount)
        }
    }
    @Test fun bodyLimitIsProtocolNotNetwork()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("x".repeat(262145)))
            val p=provider(server);assertEquals(ErrorKind.PROTOCOL,failure(p).kind)
            assertEquals(CatalogProtocol.BODY_LIMIT,p.catalogDiagnostic?.protocolCategory)
            assertFalse(requireNotNull(p.catalogDiagnostic).bodyReadCompleted);assertEquals(1,server.requestCount)
        }
    }
    @Test fun cancellationDoesNotReplayOrOverwriteTerminalSnapshot()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val p=provider(server);val job=launch(Dispatchers.Default) {p.listModels()}
            assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));withTimeout(5000) {job.cancelAndJoin()}
            val d=requireNotNull(p.catalogDiagnostic)
            assertEquals(CatalogFailure.CANCELLED,d.failureCategory);assertEquals(1,server.requestCount);assertSafe(d)
            val trace=ModelCatalogTrace();trace.finish(CatalogStage.CANCELLED,CatalogFailure.CANCELLED)
            trace.bodyRead();trace.finish();assertEquals(CatalogFailure.CANCELLED,trace.snapshot().failureCategory)
        }
    }
    @Test fun chineseErrorLabelsNeverEchoArbitraryInput() {
        assertEquals("网络连接失败",errorLabel("NETWORK"));assertEquals("服务响应格式不兼容",errorLabel("PROTOCOL"))
        assertEquals("操作失败",errorLabel(marker));assertFalse(errorLabel(marker).contains(marker))
    }
}
