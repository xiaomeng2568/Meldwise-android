package io.github.xiaomeng2568.meldwise.auth

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64

object Pkce {
    fun randomSecret():Secret=Secret(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }))
    fun challenge(verifier:Secret):String=Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.value.toByteArray(Charsets.US_ASCII)))
}
class OAuthCoordinator(private val host:()->String,private val tokens:TokenManager,private val metadata:TrustedMetadata,
    private val endpoint:TokenEndpoint,private val validator:IdentityValidator) {
    private val mutex=Mutex()
    suspend fun connect(openBrowser:(String)->Unit) = mutex.withLock {
        tokens.authenticationStarted()
        try {
            withTimeout(300000) {
                val installation=withContext(Dispatchers.IO) { host() }
                val previous=tokens.registration()
                if(previous!=null && previous.hostId!=installation) throw AuthFailure(AuthReason.ACCOUNT_MISMATCH)
                val client=previous?.clientId ?: Secret("dynamic_agent_client")
                val state=Pkce.randomSecret(); val nonce=Pkce.randomSecret(); val verifier=Pkce.randomSecret()
                val endpoints=metadata.get()
                LoopbackReceiver(state).use { receiver ->
                    val url=endpoints.authorization.toHttpUrl().newBuilder().addQueryParameter("response_type","code")
                        .addQueryParameter("client_id",client.value).addQueryParameter("redirect_uri",receiver.redirect)
                        .addQueryParameter("scope",Scopes.requested.joinToString(" ")).addQueryParameter("resource",Siwc.RESOURCE)
                        .addQueryParameter("ext_agent_host_id",installation).addQueryParameter("state",state.value)
                        .addQueryParameter("nonce",nonce.value).addQueryParameter("code_challenge",Pkce.challenge(verifier))
                        .addQueryParameter("code_challenge_method","S256")
                    if(previous==null) url.addQueryParameter("agent_name_hint","Meldwise")
                    // No id_token_hint: avoid placing a credential in a browser URL.
                    try { openBrowser(url.build().toString()) } catch (_:Exception) { throw AuthFailure(AuthReason.BROWSER_UNAVAILABLE) }
                    val reply=receiver.await()
                    val issued=reply.client ?: previous?.clientId ?: throw AuthFailure(AuthReason.PROTOCOL)
                    if(previous!=null && !constantEqual(issued.value,previous.clientId.value)) throw AuthFailure(AuthReason.ACCOUNT_MISMATCH)
                    val credential=endpoint.exchange(reply.code,verifier,receiver.redirect,issued)
                    val identity=validator.validate(credential.idToken,issued,nonce,previous?.subject)
                    tokens.accept(Registration(Siwc.ISSUER,issued,identity.subject,installation),credential)
                }
            }
        } catch (timeout:TimeoutCancellationException) { throw AuthFailure(AuthReason.TIMEOUT) }
        catch (cancel:CancellationException) { throw cancel }
        catch (failure:AuthFailure) { throw failure }
        catch (_:Exception) { throw AuthFailure(AuthReason.NETWORK) }
        finally { withContext(NonCancellable) { tokens.authenticationFinished() } }
    }
}
