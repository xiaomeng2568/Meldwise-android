package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.security.*
import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import java.util.concurrent.TimeUnit
import java.net.Socket
import java.net.URI
import org.jose4j.jwk.RsaJwkGenerator
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.NumericDate
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jws.AlgorithmIdentifiers

private class Blob:AtomicBlob {
    var bytes:ByteArray?=null
    var fail=false
    override fun read()=bytes?.clone()
    override fun write(value:ByteArray) { if(fail) error("SYNTHETIC_DISK_FAILURE");bytes=value.clone() }
}
private class Store(var session:StoredSession?=null):CredentialStore {
    var fail=false; val writes=mutableListOf<StoredSession>()
    override fun read()=session
    override fun write(value:StoredSession) { if(fail) error("SYNTHETIC_DISK_FAILURE");session=value;writes+=value }
}
private fun credentials(expiry:Long=1,access:String="synthetic-access",refresh:String="synthetic-refresh")=
    CredentialSet(Secret(access),Secret(refresh),Secret("synthetic-id"),expiry,Scopes.requested,0)
private fun session(expiry:Long=1)=StoredSession(Registration(Siwc.ISSUER,Secret("synthetic-client"),Secret("synthetic-subject"),"synthetic-host"),credentials(expiry),1,CredentialPhase.ACTIVE)
private fun box(purpose:String="test")=AesGcmBox({ testKey },purpose,8_388_640)
private val testKey=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
private fun completed()="""{"type":"response.completed","response":{"id":"synthetic-response","status":"completed","output":[],"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}"""
private fun added()="""{"type":"response.output_item.added","output_index":0,"item":{"id":"synthetic-item","type":"message","role":"assistant"}}"""
private fun delta()="""{"type":"response.output_text.delta","output_index":0,"content_index":0,"item_id":"synthetic-item","delta":"synthetic text"}"""

class FoundationTests {
    @Test fun encryptionFreshNonceRoundTrip() {
        val cipher=box();val plain="synthetic secret".toByteArray()
        val a=cipher.seal(plain);val b=cipher.seal(plain)
        assertFalse(a.contentEquals(b));assertArrayEquals(plain,cipher.open(a))
        assertFalse(String(a).contains("synthetic secret"))
    }
    @Test fun tamperedCiphertextFailsClosed() {
        val cipher=box();val bytes=cipher.seal("synthetic".toByteArray());bytes[bytes.lastIndex]=(bytes.last()+1).toByte()
        assertThrows(Exception::class.java) { cipher.open(bytes) }
    }
    @Test fun aadIsolation() { val bytes=box("credentials").seal(byteArrayOf(1));assertThrows(Exception::class.java) { box("chat").open(bytes) } }
    @Test fun credentialWholeRecordRoundTrip() {
        val blob=Blob();val store=EncryptedCredentialStore(blob,box());store.write(session())
        assertEquals("synthetic-refresh",store.read()!!.credentials!!.refreshToken!!.value)
        assertFalse(String(blob.bytes!!).contains("synthetic-refresh"))
    }
    @Test fun diskFailureNeverPublishesReplacement()=runTest {
        val store=Store(session(100000));val manager=TokenManager(store,RefreshEndpointFake { _,_->credentials(200000) },TimeSource {0})
        manager.initialize();store.fail=true
        try { manager.accept(store.session!!.registration,credentials(200000));fail() } catch(failure:AuthFailure) { assertEquals(AuthReason.STORAGE_UNAVAILABLE,failure.reason) }
        assertEquals(1,store.session!!.generation)
        assertEquals(AuthState.StorageUnavailable,manager.state.value)
    }
    @Test fun singleFlightThreeCallersShareRotation()=runTest {
        val store=Store(session());var calls=0
        val manager=TokenManager(store,RefreshEndpointFake { _,_->calls++;delay(100);credentials(100000,"replacement","rotated") },TimeSource {0})
        manager.initialize()
        val values=(1..3).map { async { manager.accessToken().value } }.awaitAll()
        assertEquals(listOf("replacement","replacement","replacement"),values);assertEquals(1,calls)
        assertEquals(2,store.session!!.generation)
        assertEquals(listOf(CredentialPhase.REFRESH_IN_FLIGHT,CredentialPhase.ACTIVE),store.writes.map {it.phase})
    }
    @Test fun noAutomaticRetryForWaitingFailedCallers()=runTest {
        val ownerEntered=CompletableDeferred<Unit>()
        val releaseFailure=CompletableDeferred<Unit>()
        val store=Store(session());var calls=0
        val manager=TokenManager(store,RefreshEndpointFake { _,_->
            calls++
            ownerEntered.complete(Unit)
            releaseFailure.await()
            throw RefreshFailure(AuthReason.NETWORK,false)
        },TimeSource {0})
        manager.initialize()
        suspend fun outcome():AuthReason? = try {manager.accessToken();null} catch(f:AuthFailure) {f.reason}
        val owner=async {outcome()}
        ownerEntered.await()
        assertEquals(AuthState.Refreshing,manager.state.value)
        assertEquals(CredentialPhase.REFRESH_IN_FLIGHT,store.session!!.phase)
        // UNDISPATCHED runs accessToken through attempt capture to its first suspension.
        // The owner holds the mutex at the endpoint barrier: that suspension MUST be mutex.lock.
        val waiters=(1..2).map {async(start=CoroutineStart.UNDISPATCHED) {outcome()}}
        assertFalse(owner.isCompleted)
        assertTrue(waiters.all {it.isActive && !it.isCompleted})
        assertEquals(1,calls)
        releaseFailure.complete(Unit)
        val outcomes=(listOf(owner)+waiters).awaitAll()
        assertEquals(List(3) { AuthReason.NETWORK },outcomes);assertEquals(1,calls)
        assertEquals(CredentialPhase.ACTIVE,store.session!!.phase)
        assertEquals(1L,store.session!!.generation)
        // A new explicit request AFTER settlement is not an existing waiter and may try again.
        assertEquals(AuthReason.NETWORK,outcome())
        assertEquals(2,calls)
    }
    @Test fun coldLoadNearExpiryRefreshes()=runTest {
        var calls=0;val manager=TokenManager(Store(session()),RefreshEndpointFake { _,_->calls++;credentials(100000) },TimeSource {0})
        manager.accessToken();assertEquals(1,calls)
    }
    @Test fun invalidGrantTransitionsWithoutRetry()=runTest {
        val store=Store(session());var calls=0
        val manager=TokenManager(store,RefreshEndpointFake { _,_->calls++;throw RefreshFailure(AuthReason.INVALID_REFRESH,false) },TimeSource {0})
        repeat(2) { try {manager.accessToken();fail()} catch(_:AuthFailure) {} }
        assertEquals(1,calls);assertEquals(CredentialPhase.REAUTH_REQUIRED,store.session!!.phase);assertNull(store.session!!.credentials)
    }
    @Test fun uncertainRotationQuarantinesOldCredential()=runTest {
        val store=Store(session());val manager=TokenManager(store,RefreshEndpointFake { _,_->throw RefreshFailure(AuthReason.NETWORK,true) },TimeSource {0})
        try {manager.accessToken();fail()} catch(_:AuthFailure) {}
        assertEquals(CredentialPhase.RECOVERY_UNCERTAIN,store.session!!.phase)
        assertNotNull(store.session!!.credentials)
        assertTrue(manager.state.value is AuthState.ReauthRequired)
    }
    @Test fun interruptedMarkerNeverReplaysRefresh()=runTest {
        val active=session();val store=Store(StoredSession(active.registration,active.credentials,1,CredentialPhase.REFRESH_IN_FLIGHT));var calls=0
        val manager=TokenManager(store,RefreshEndpointFake { _,_->calls++;credentials() })
        manager.initialize();try {manager.accessToken();fail()} catch(_:AuthFailure) {}
        assertEquals(0,calls);assertEquals(CredentialPhase.RECOVERY_UNCERTAIN,store.session!!.phase)
    }
    @Test fun cancelledRefreshLeavesRecoverableQuarantine()=runTest {
        val entered=CompletableDeferred<Unit>()
        val store=Store(session());val manager=TokenManager(store,RefreshEndpointFake { _,_->entered.complete(Unit);awaitCancellation() },TimeSource {0})
        manager.initialize();val job=launch {manager.accessToken()};entered.await();job.cancelAndJoin()
        assertEquals(CredentialPhase.RECOVERY_UNCERTAIN,store.session!!.phase)
    }
    @Test fun scopesPreventInferenceWithoutDeletingIdentity()=runTest {
        val active=session(100000);val c=active.credentials!!
        val restricted=CredentialSet(c.accessToken,c.refreshToken,c.idToken,c.expiresAtMillis,setOf("openid"),0)
        val store=Store(StoredSession(active.registration,restricted,1,CredentialPhase.ACTIVE));var calls=0
        val manager=TokenManager(store,RefreshEndpointFake { _,_->calls++;credentials() },TimeSource {0})
        try {manager.accessToken();fail()} catch(f:AuthFailure) {assertEquals(AuthReason.SCOPE_CHANGED,f.reason)}
        assertEquals(0,calls);assertNotNull(store.session!!.credentials)
    }
    @Test fun accountSwitchIsRejected()=runTest {
        val store=Store(session());val manager=TokenManager(store,RefreshEndpointFake { _,_->credentials() })
        try {manager.accept(Registration(Siwc.ISSUER,Secret("other-client"),Secret("other-subject"),"synthetic-host"),credentials());fail()}
        catch(f:AuthFailure) {assertEquals(AuthReason.ACCOUNT_MISMATCH,f.reason)}
    }
    @Test fun installationIdentityReusedAndNewAfterDataLoss() {
        val blob=Blob();val id=InstallationIdentity(blob).load();assertEquals(id,InstallationIdentity(blob).load())
        blob.bytes=null;assertNotEquals(id,InstallationIdentity(blob).load())
    }
    @Test fun sseMultilineAndComments() {
        val parser=SseParser(Buffer().writeUtf8(": comment\r\nevent: example\r\ndata: one\r\ndata: two\r\n\r\n"))
        val frame=parser.next()!!;assertEquals("example",frame.event);assertEquals("one\ntwo",frame.data);assertNull(parser.next())
    }
    @Test fun sseBoundedLine() { assertThrows(Exception::class.java) {SseParser(Buffer().writeUtf8("data: "+"a".repeat(32769)+"\n\n")).next()} }
    @Test fun sseMalformedUtf8Rejected() { assertThrows(Exception::class.java) {SseParser(Buffer().write(byteArrayOf(0xc3.toByte(),0x28,10))).next()} }
    @Test fun emptyTerminalEnvelopeUsesStreamText() {
        val reader=ResponsesReader();reader.consume(SseFrame("response.output_item.added",added()))
        assertTrue(reader.consume(SseFrame(null,delta())).single() is LlmEvent.TextDelta)
        assertTrue(reader.consume(SseFrame("response.completed",completed())).single() is LlmEvent.Completed)
    }
    @Test fun terminalWithoutAssistantNeverPasses() {assertThrows(Exception::class.java) {ResponsesReader().consume(SseFrame(null,completed()))}}
    @Test fun untrustedDeltaCannotBecomeAssistant() {assertThrows(Exception::class.java) {ResponsesReader().consume(SseFrame(null,delta()))}}
    @Test fun eventNameConflictRejected() {assertThrows(Exception::class.java) {ResponsesReader().consume(SseFrame("response.completed",delta()))}}
    @Test fun outputDoneMustMatchDelta() {
        val r=ResponsesReader();r.consume(SseFrame(null,added()));r.consume(SseFrame(null,delta()))
        assertThrows(Exception::class.java) {r.consume(SseFrame(null,"""{"type":"response.output_text.done","output_index":0,"content_index":0,"text":"different synthetic text"}"""))}
    }
    @Test fun partialJournalRecoversIncompleteWithoutPost() {
        val blob=Blob();val repo=ChatRepository(blob,box());val (id,_)=repo.begin("synthetic input")
        repo.update(id,"synthetic partial",MessageState.STREAMING)
        val recovered=ChatRepository(blob,box()).load();assertEquals(MessageState.INTERRUPTED,recovered.last().state)
        assertEquals("synthetic partial",recovered.last().text)
        assertEquals(7,java.util.UUID.fromString(recovered.last().id).version())
    }
    @Test fun incompleteHistoryNotSilentlySent() {
        val repo=ChatRepository(Blob(),box());val (id,_)=repo.begin("synthetic input");repo.update(id,"partial",MessageState.INCOMPLETE)
        val (_,history)=repo.begin("second synthetic input");assertEquals(1,history.size)
    }
    @Test fun safeDiagnosticsHaveClosedSchema() {
        val d=SafeDiagnostics();repeat(120) { d.record(Operation.RESPONSE,Outcome.HTTP,200) };assertEquals(100,d.snapshot().size)
        assertFalse(d.snapshot().toString().contains("synthetic-access"));assertEquals("[REDACTED]",Secret("synthetic-access").toString())
    }
    @Test fun cancelledHttpCallDoesNotRetry()=runBlocking {
        MockWebServer().use { server->server.start();server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val network=NetworkClient(allowLocalTestHttp=true)
            val job=launch(Dispatchers.Default) {network.request(Request.Builder().url(server.url("/bounded").newBuilder().host("127.0.0.1").build()).get().build())}
            assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));job.cancelAndJoin();assertEquals(1,server.requestCount)
        }
    }
    @Test fun redirectsNotFollowed()=runBlocking {
        MockWebServer().use { server->server.start();server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://example.invalid/"))
            val network=NetworkClient(allowLocalTestHttp=true)
            val result=network.request(Request.Builder().url(server.url("/").newBuilder().host("127.0.0.1").build()).get().build())
            assertEquals(302,result.status);assertEquals(1,server.requestCount)
        }
    }
    @Test fun httpDisabledExceptExplicitLocalTest() {assertThrows(Exception::class.java) { NetworkClient().newCall(Request.Builder().url("http://127.0.0.1/").build()) }}
    @Test fun retryAfterZeroCannotCauseImplicitSecondRequest()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After","0"));server.enqueue(MockResponse().setBody("unexpected retry"))
            val network=NetworkClient(allowLocalTestHttp=true)
            val result=network.request(Request.Builder().url(server.url("/").newBuilder().host("127.0.0.1").build()).get().build())
            assertEquals(503,result.status);assertEquals(1,server.requestCount)
        }
    }
    @Test fun rotatingRequestBodyMarkedOneShot() {
        val request=Request.Builder().url("https://example.invalid/").post(okhttp3.FormBody.Builder().add("field","synthetic").build()).build()
        assertTrue(NetworkClient().newCall(request).request().body!!.isOneShot())
    }
    @Test fun pkceRfcVector() {assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",Pkce.challenge(Secret("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")))}
    @Test fun loopbackRejectsWrongStateThenAcceptsOnce()=runBlocking {
        val state=Secret("synthetic-state");LoopbackReceiver(state).use { receiver->
            val task=async(Dispatchers.IO) {receiver.await()};val port=URI(receiver.redirect).port
            fun callback(query:String):String=Socket("127.0.0.1",port).use { socket->
                socket.soTimeout=2000;socket.getOutputStream().write("GET /auth/callback?$query HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray());socket.getInputStream().bufferedReader().readText() }
            assertTrue(callback("state=wrong&code=synthetic-code").startsWith("HTTP/1.1 400"))
            assertTrue(callback("state=synthetic-state&code=synthetic-code&client_id=synthetic-client").startsWith("HTTP/1.1 200"))
            assertEquals("synthetic-code",task.await().code.value)
        }
    }
    @Test fun identityClaimsAndRotationNegativeCases()=runBlocking {
        val first=RsaJwkGenerator.generateJwk(2048).apply { keyId="synthetic-key-1" }
        val second=RsaJwkGenerator.generateJwk(2048).apply { keyId="synthetic-key-2" }
        var active=first;var fetches=0
        fun keys()="{\"keys\":[${active.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY)}]}"
        val validator=IdentityValidator(KeySetSource {fetches++;keys()},TimeSource {1_000_000})
        fun token(issuer:String=Siwc.ISSUER,audience:String="synthetic-client",nonce:String="synthetic-nonce",expiry:Long=2000,key:org.jose4j.jwk.RsaJsonWebKey=active):Secret {
            val c=JwtClaims().apply {this.issuer=issuer;setAudience(audience);subject="synthetic-subject";issuedAt=NumericDate.fromSeconds(990);expirationTime=NumericDate.fromSeconds(expiry);setStringClaim("nonce",nonce)}
            return Secret(JsonWebSignature().apply {payload=c.toJson();this.key=key.privateKey;keyIdHeaderValue=key.keyId;algorithmHeaderValue=AlgorithmIdentifiers.RSA_USING_SHA256}.compactSerialization)
        }
        validator.validate(token(),Secret("synthetic-client"),Secret("synthetic-nonce"));assertEquals(1,fetches)
        suspend fun rejected(t:Secret) {try {validator.validate(t,Secret("synthetic-client"),Secret("synthetic-nonce"));fail()} catch(f:AuthFailure) {assertEquals(AuthReason.IDENTITY_INVALID,f.reason)}}
        rejected(token(issuer="https://example.invalid"));rejected(token(audience="wrong"));rejected(token(nonce="wrong"));rejected(token(expiry=900))
        val signed=token().value;rejected(Secret(signed.dropLast(8)+"AAAAAAAA"))
        active=second;validator.validate(token(),Secret("synthetic-client"),Secret("synthetic-nonce"));assertEquals(2,fetches)
        val unknown=RsaJwkGenerator.generateJwk(2048).apply {keyId="synthetic-unknown"};rejected(token(key=unknown));assertEquals(3,fetches)
    }
}
private class RefreshEndpointFake(val block:suspend(Registration,CredentialSet)->CredentialSet):RefreshEndpoint {
    override suspend fun refresh(registration:Registration,current:CredentialSet)=block(registration,current)
}
