package io.github.xiaomeng2568.meldwise.auth

import io.github.xiaomeng2568.meldwise.network.NetworkClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.Request
import org.jose4j.jwk.JsonWebKeySet
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import org.jose4j.jwa.AlgorithmConstraints
import java.security.interfaces.RSAPublicKey
import java.security.MessageDigest

object Siwc {
    const val ISSUER = "https://auth.openai.com"
    const val DISCOVERY = "$ISSUER/.well-known/openid-configuration"
    const val RESOURCE = "https://api.openai.com/v1"
}
class Metadata(val authorization: String, val token: String, val jwks: String)
class TrustedMetadata(private val network: NetworkClient) {
    private val mutex=Mutex()
    private var cached: Metadata?=null
    suspend fun get(): Metadata = mutex.withLock {
        cached ?: run {
            val result=network.request(Request.Builder().url(Siwc.DISCOVERY).get().build())
            if (result.status != 200) throw AuthFailure(AuthReason.PROTOCOL)
            val json=Json.parseToJsonElement(result.body).jsonObject
            if (json["issuer"]?.jsonPrimitive?.content != Siwc.ISSUER) throw AuthFailure(AuthReason.IDENTITY_INVALID)
            fun endpoint(name:String):String {
                val value=json[name]?.jsonPrimitive?.content ?: throw AuthFailure(AuthReason.PROTOCOL)
                val uri=java.net.URI(value)
                if (uri.scheme!="https" || uri.host!="auth.openai.com" || uri.port !in listOf(-1,443)
                    || uri.rawUserInfo!=null || uri.rawFragment!=null || uri.rawQuery!=null)
                    throw AuthFailure(AuthReason.IDENTITY_INVALID)
                return value
            }
            Metadata(endpoint("authorization_endpoint"),endpoint("token_endpoint"),endpoint("jwks_uri")).also { cached=it }
        }
    }
}
fun interface KeySetSource { suspend fun retrieve(): String }
class ValidatedIdentity(internal val subject: Secret) { override fun toString()="ValidatedIdentity([REDACTED])" }
/** Pinned algorithm and trust root. Token-supplied jku/x5u are never fetched. */
class IdentityValidator(private val source: KeySetSource,
    private val clock: TimeSource=TimeSource(System::currentTimeMillis)) {
    private val mutex=Mutex()
    private var keys: JsonWebKeySet?=null
    private var fetchedAt=0L
    suspend fun validate(token: Secret, audience: Secret, nonce: Secret?, expectedSubject: Secret?=null): ValidatedIdentity = mutex.withLock {
        try {
            val parts=token.value.split('.')
            require(parts.size==3)
            val header=Json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(parts[0]),Charsets.UTF_8)).jsonObject
            require(header["alg"]?.jsonPrimitive?.content=="RS256")
            require(header["crit"]==null)
            val kid=header["kid"]?.jsonPrimitive?.content ?: error("KEY_REQUIRED")
            require(kid.isNotBlank() && kid.length<=256)
            var fetchedNow=false
            suspend fun fetch() {
                val raw=source.retrieve()
                require(raw.length<=262144)
                keys=JsonWebKeySet(raw); fetchedAt=clock.nowMillis(); fetchedNow=true
            }
            if (keys==null || clock.nowMillis()-fetchedAt>600000) fetch()
            fun matches()=requireNotNull(keys).jsonWebKeys.filter { it.keyId==kid && it.keyType=="RSA"
                && (it.use==null || it.use=="sig") && (it.algorithm==null || it.algorithm=="RS256") }
            if (matches().isEmpty() && !fetchedNow) fetch() // One bounded unknown-key rotation fetch.
            val key=matches().single().key as RSAPublicKey
            require(key.modulus.bitLength()>=2048)
            val claims=JwtConsumerBuilder().setVerificationKey(key)
                .setJwsAlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT,AlgorithmIdentifiers.RSA_USING_SHA256)
                .setExpectedIssuer(Siwc.ISSUER).setExpectedAudience(audience.value)
                .setRequireExpirationTime().setRequireIssuedAt().setRequireSubject()
                .setAllowedClockSkewInSeconds(5)
                .setEvaluationTime(org.jose4j.jwt.NumericDate.fromMilliseconds(clock.nowMillis()))
                .build().processToClaims(token.value)
            require(claims.issuedAt.value<=clock.nowMillis()/1000+5)
            if (nonce!=null) require(constantEqual(claims.getStringClaimValue("nonce") ?: "",nonce.value))
            if (expectedSubject!=null) require(constantEqual(claims.subject,expectedSubject.value))
            val audiences=claims.audience
            if (audiences.size>1) require(claims.getStringClaimValue("azp")==audience.value)
            ValidatedIdentity(Secret(claims.subject))
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { throw AuthFailure(AuthReason.IDENTITY_INVALID) }
    }
}
internal fun constantEqual(a:String,b:String)=MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8),b.toByteArray(Charsets.UTF_8))
