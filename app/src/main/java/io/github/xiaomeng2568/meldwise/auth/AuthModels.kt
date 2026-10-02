package io.github.xiaomeng2568.meldwise.auth

import kotlinx.serialization.Serializable

/** Never interpolate these records into diagnostics. Strings are accessible only within this module. */
@Serializable
class Secret(internal val value: String) {
    init { require(value.isNotBlank() && value.length <= 65536) { "INVALID_SECRET_SHAPE" } }
    override fun toString() = "[REDACTED]"
}
@Serializable
class Registration(val issuer: String, internal val clientId: Secret, internal val subject: Secret,
    internal val hostId: String) {
    override fun toString() = "Registration([REDACTED])"
}
@Serializable
class CredentialSet(internal val accessToken: Secret, internal val refreshToken: Secret?,
    internal val idToken: Secret, val expiresAtMillis: Long, val grantedScopes: Set<String>,
    val scopeGrantedAtMillis: Long, val earliestRefreshAtMillis: Long? = null) {
    override fun toString() = "CredentialSet([REDACTED])"
}
@Serializable enum class CredentialPhase { ACTIVE, REFRESH_IN_FLIGHT, RECOVERY_UNCERTAIN, REAUTH_REQUIRED }
@Serializable enum class AuthReason { SIGNED_OUT, TOKEN_REJECTED, INVALID_REFRESH, INVALID_CLIENT,
    SCOPE_CHANGED, INTERRUPTED_REFRESH, UNCERTAIN_ROTATION, IDENTITY_INVALID, ACCOUNT_MISMATCH,
    STORAGE_UNAVAILABLE, PROTOCOL, NETWORK, CANCELLED, BROWSER_UNAVAILABLE, TIMEOUT, REFRESH_TOO_EARLY }
@Serializable
class StoredSession(val registration: Registration, val credentials: CredentialSet?,
    val generation: Long, val phase: CredentialPhase, val reason: AuthReason? = null) {
    init {
        require(generation >= 1) { "INVALID_GENERATION" }
        require((phase == CredentialPhase.REAUTH_REQUIRED) == (credentials == null)) { "INVALID_SESSION_PHASE" }
    }
    fun asReauth(reason: AuthReason) = StoredSession(registration, null, generation, CredentialPhase.REAUTH_REQUIRED, reason)
    fun asUncertain(reason: AuthReason) = StoredSession(registration, credentials, generation, CredentialPhase.RECOVERY_UNCERTAIN, reason)
    override fun toString() = "StoredSession(phase=$phase, generation=$generation, credentials=[REDACTED])"
}
sealed interface AuthState {
    data object Restoring : AuthState
    data object Disconnected : AuthState
    data object Authenticating : AuthState
    data class Connected(val planEnabled: Boolean, val expiringSoon: Boolean = false) : AuthState
    data object Refreshing : AuthState
    data class ReauthRequired(val reason: AuthReason) : AuthState
    data object StorageUnavailable : AuthState
}
class AuthFailure(val reason: AuthReason) : Exception(reason.name)
object Scopes {
    val requested = setOf("openid", "profile", "email", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct")
    val inference = setOf("resource.invoke", "chatgpt.tokens.use.direct")
    fun planEnabled(scopes: Set<String>) = scopes.containsAll(inference)
}
fun interface TimeSource { fun nowMillis(): Long }
