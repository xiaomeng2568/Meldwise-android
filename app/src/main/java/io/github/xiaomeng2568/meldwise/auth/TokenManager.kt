package io.github.xiaomeng2568.meldwise.auth

import io.github.xiaomeng2568.meldwise.security.CredentialStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RefreshEndpoint { suspend fun refresh(registration: Registration, current: CredentialSet): CredentialSet }
class RefreshFailure(val reason: AuthReason, val rotationUncertain: Boolean) : Exception(reason.name)

/** One selected account / one Android process. Mutex waiters share the newly committed generation. */
class TokenManager(private val store: CredentialStore, private val endpoint: RefreshEndpoint,
    private val clock: TimeSource = TimeSource(System::currentTimeMillis)) {
    private val mutex = Mutex()
    private var loaded = false
    @Volatile private var current: StoredSession? = null
    @Volatile private var refreshAttempt = 0L
    private var lastFailure: Pair<Long, AuthReason>? = null
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Restoring)
    val state: StateFlow<AuthState> = mutableState
    private fun publish() {
        val session = current
        mutableState.value = when {
            session == null -> AuthState.Disconnected
            session.phase != CredentialPhase.ACTIVE -> AuthState.ReauthRequired(session.reason ?: AuthReason.INTERRUPTED_REFRESH)
            else -> AuthState.Connected(Scopes.planEnabled(requireNotNull(session.credentials).grantedScopes),
                session.credentials.expiresAtMillis-clock.nowMillis() < 60000)
        }
    }
    private suspend fun loadLocked() {
        if (loaded) {
            if (state.value == AuthState.StorageUnavailable) throw AuthFailure(AuthReason.STORAGE_UNAVAILABLE)
            return
        }
        loaded = true
        try {
            current = withContext(Dispatchers.IO) { store.read() }
            if (current?.phase == CredentialPhase.REFRESH_IN_FLIGHT) {
                // Old rotating credential is quarantined, NOT replayed after uncertain process death.
                saveLocked(requireNotNull(current).asUncertain(AuthReason.INTERRUPTED_REFRESH))
            }
            publish()
        } catch (_: Exception) { mutableState.value=AuthState.StorageUnavailable; throw AuthFailure(AuthReason.STORAGE_UNAVAILABLE) }
    }
    private suspend fun saveLocked(value: StoredSession) = withContext(NonCancellable + Dispatchers.IO) {
        try { store.write(value); current=value }
        catch (_: Exception) { mutableState.value=AuthState.StorageUnavailable; throw AuthFailure(AuthReason.STORAGE_UNAVAILABLE) }
    }
    suspend fun initialize() = mutex.withLock { loadLocked() }
    suspend fun registration(): Registration? = mutex.withLock { loadLocked(); current?.registration }
    suspend fun authenticationStarted() = mutex.withLock { loadLocked(); mutableState.value=AuthState.Authenticating }
    suspend fun authenticationFinished() = mutex.withLock { if (state.value != AuthState.StorageUnavailable) publish() }
    suspend fun accept(registration: Registration, credentials: CredentialSet) = mutex.withLock {
        loadLocked()
        val previous=current?.registration
        if (previous != null && (previous.issuer != registration.issuer || previous.clientId.value != registration.clientId.value
                    || previous.subject.value != registration.subject.value || previous.hostId != registration.hostId)) {
            throw AuthFailure(AuthReason.ACCOUNT_MISMATCH)
        }
        saveLocked(StoredSession(registration,credentials,(current?.generation ?: 0)+1,CredentialPhase.ACTIVE))
        publish()
    }
    suspend fun requireReauth(reason: AuthReason) = mutex.withLock {
        loadLocked()
        current?.let { saveLocked(it.asReauth(reason)) }
        publish()
    }
    suspend fun clearLocalConnection() = requireReauth(AuthReason.SIGNED_OUT)
    suspend fun accessToken(): Secret {
        // Capture generation before waiting, so near-expiry waiters do not rotate the new token again.
        val generationAtEntry=current?.generation
        val attemptAtEntry=refreshAttempt
        return mutex.withLock {
            loadLocked()
            val session=current ?: throw AuthFailure(AuthReason.SIGNED_OUT)
            if (session.phase != CredentialPhase.ACTIVE) throw AuthFailure(session.reason ?: AuthReason.INTERRUPTED_REFRESH)
            val credentials=requireNotNull(session.credentials)
            if (!Scopes.planEnabled(credentials.grantedScopes)) throw AuthFailure(AuthReason.SCOPE_CHANGED)
            val now=clock.nowMillis()
            if ((generationAtEntry != null && generationAtEntry != session.generation && credentials.expiresAtMillis > now)
                || credentials.expiresAtMillis-now >= 60000) return@withLock credentials.accessToken
            if (attemptAtEntry != refreshAttempt && lastFailure?.first == session.generation)
                throw AuthFailure(requireNotNull(lastFailure).second)
            if (credentials.earliestRefreshAtMillis?.let { it>now } == true) {
                if (credentials.expiresAtMillis>now) return@withLock credentials.accessToken
                throw AuthFailure(AuthReason.REFRESH_TOO_EARLY)
            }
            if (credentials.refreshToken == null) {
                saveLocked(session.asReauth(AuthReason.INVALID_REFRESH)); publish()
                throw AuthFailure(AuthReason.INVALID_REFRESH)
            }
            saveLocked(StoredSession(session.registration,credentials,session.generation,CredentialPhase.REFRESH_IN_FLIGHT))
            mutableState.value=AuthState.Refreshing
            refreshAttempt++
            lastFailure=null
            try {
                currentCoroutineContext().ensureActive()
                val replacement=endpoint.refresh(session.registration,credentials)
                if (replacement.expiresAtMillis<=clock.nowMillis()) throw RefreshFailure(AuthReason.PROTOCOL,true)
                saveLocked(StoredSession(session.registration,replacement,session.generation+1,CredentialPhase.ACTIVE))
                publish()
                if (!Scopes.planEnabled(replacement.grantedScopes)) {
                    saveLocked(requireNotNull(current).asReauth(AuthReason.SCOPE_CHANGED)); publish()
                    throw AuthFailure(AuthReason.SCOPE_CHANGED)
                }
                replacement.accessToken
            } catch (failure: RefreshFailure) {
                val next = when {
                    failure.reason in setOf(AuthReason.INVALID_REFRESH,AuthReason.INVALID_CLIENT) -> session.asReauth(failure.reason)
                    failure.rotationUncertain -> session.asUncertain(AuthReason.UNCERTAIN_ROTATION)
                    else -> session // Confirmed not sent: preserve old state, no automatic retry.
                }
                saveLocked(next); publish()
                lastFailure=session.generation to failure.reason
                throw AuthFailure(if (failure.rotationUncertain) AuthReason.UNCERTAIN_ROTATION else failure.reason)
            } catch (failure: CancellationException) {
                if (current?.phase == CredentialPhase.REFRESH_IN_FLIGHT) {
                    saveLocked(session.asUncertain(AuthReason.UNCERTAIN_ROTATION)); publish()
                }
                throw failure
            } catch (failure: AuthFailure) { throw failure }
            catch (_: Exception) {
                saveLocked(session.asUncertain(AuthReason.UNCERTAIN_ROTATION)); publish()
                throw AuthFailure(AuthReason.UNCERTAIN_ROTATION)
            }
        }
    }
}
