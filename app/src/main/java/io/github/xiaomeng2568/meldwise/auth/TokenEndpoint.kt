package io.github.xiaomeng2568.meldwise.auth

import io.github.xiaomeng2568.meldwise.network.*
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.Request
import java.time.Instant

class TokenEndpoint(private val network: NetworkClient, private val metadata: TrustedMetadata,
    private val validator: IdentityValidator, private val clock: TimeSource=TimeSource(System::currentTimeMillis)) : RefreshEndpoint {
    suspend fun exchange(code:Secret,verifier:Secret,redirect:String,client:Secret):CredentialSet {
        val form=FormBody.Builder().add("grant_type","authorization_code").add("code",code.value)
            .add("code_verifier",verifier.value).add("redirect_uri",redirect).add("client_id",client.value)
            .add("resource",Siwc.RESOURCE).build()
        try {
            val result=network.request(Request.Builder().url(metadata.get().token).post(form).build(),Operation.TOKEN_EXCHANGE)
            if (result.status!=200) throw AuthFailure(AuthReason.TOKEN_REJECTED)
            return parse(result.body,null)
        } catch (cancel:kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure:AuthFailure) { throw failure }
        catch (_:Exception) { throw AuthFailure(AuthReason.PROTOCOL) }
    }
    override suspend fun refresh(registration:Registration,current:CredentialSet):CredentialSet {
        val endpoint=try { metadata.get().token }
            catch(cancel:kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_:Exception) { throw RefreshFailure(AuthReason.NETWORK,false) }
        val form=FormBody.Builder().add("grant_type","refresh_token").add("client_id",registration.clientId.value)
            .add("refresh_token",requireNotNull(current.refreshToken).value).add("resource",Siwc.RESOURCE).build()
        try {
            val result=network.request(Request.Builder().url(endpoint).post(form).build(),Operation.REFRESH)
            if (result.status!=200) {
                val code=runCatching { Json.parseToJsonElement(result.body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                val terminal=setOf("invalid_grant","invalid_refresh_token","token_expired","refresh_token_expired",
                    "refresh_token_invalidated","refresh_token_reused")
                throw RefreshFailure(when(code) { in terminal->AuthReason.INVALID_REFRESH; "invalid_client"->AuthReason.INVALID_CLIENT
                    else->AuthReason.PROTOCOL },code !in terminal && code!="invalid_client")
            }
            val replacement=parse(result.body,current)
            require(replacement.refreshToken!=null) // Never replay old RT when replacement is missing.
            if (replacement.idToken.value!=current.idToken.value)
                validator.validate(replacement.idToken,registration.clientId,null,registration.subject)
            return replacement
        } catch (cancel:kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure:RefreshFailure) { throw failure }
        catch (failure:NetworkFault) { throw RefreshFailure(AuthReason.NETWORK,failure.requestMayHaveBeenSent) }
        catch (_:Exception) { throw RefreshFailure(AuthReason.PROTOCOL,true) }
    }
    internal fun parse(body:String,previous:CredentialSet?):CredentialSet {
        val obj=Json.parseToJsonElement(body).jsonObject
        require(obj["token_type"]?.jsonPrimitive?.content?.equals("Bearer",true)==true)
        fun secret(name:String)=obj[name]?.jsonPrimitive?.content?.let(::Secret)
        val seconds=obj["expires_in"]?.jsonPrimitive?.long ?: error("MISSING_EXPIRY")
        require(seconds in 1..86400)
        val scopes=obj["scope"]?.jsonPrimitive?.content?.split(' ')?.filter(String::isNotBlank)?.toSet()
            ?: previous?.grantedScopes ?: error("MISSING_SCOPES")
        require(scopes.size<=64 && scopes.all { it.length<=128 })
        val earliest=obj["earliest_refresh_at"]?.jsonPrimitive?.content?.let { value ->
            value.toLongOrNull()?.let { Math.multiplyExact(it,1000) } ?: Instant.parse(value).toEpochMilli()
        }
        return CredentialSet(requireNotNull(secret("access_token")),secret("refresh_token"),
            secret("id_token") ?: previous?.idToken ?: error("MISSING_ID_TOKEN"),
            Math.addExact(clock.nowMillis(),seconds*1000),scopes,clock.nowMillis(),earliest)
    }
}
