package com.wynh.appapp.ai.auth

import com.wynh.appapp.ai.network.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AuthRepository(
    private val store: CredentialStore,
    private val api: OAuthApi,
    private val validator: IdTokenValidator,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) : AuthenticationRepository {
    private val lock = Mutex()
    private var snapshot: CredentialSnapshot? = null
    private var authJob: Job? = null
    private var listener: LoopbackListener? = null
    private var incompleteClientId: String? = null
    private val foreground = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(AuthState(phase = AuthPhase.RESTORING))
    override val state: StateFlow<AuthState> = mutableState.asStateFlow()
    override val browserRequests = Channel<String>(Channel.BUFFERED)

    override fun setForeground(value: Boolean) { foreground.value = value }

    init {
        scope.launch {
            lock.withLock {
                try { snapshot = withContext(Dispatchers.IO) { store.read() }; publish() }
                catch (e: Exception) { mutableState.value = AuthState(message = message(e), storageFailed = true) }
            }
        }
    }

    override fun beginSignIn(clientId: String?) {
        if (mutableState.value.phase != AuthPhase.IDLE || snapshot == null || authJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(phase = AuthPhase.WAITING_FOR_BROWSER, message = "Complete sign-in in your browser, then return to AppApp.")
        authJob = scope.launch {
            try {
                val attempt = lock.withLock {
                    val saved = snapshot ?: throw AuthFailure("Local sign-in data is unavailable.")
                    val registration = clientId?.let { id -> saved.registrations.firstOrNull { it.clientId == id }
                        ?: throw AuthFailure("The selected account was not found.") }
                    val local = withContext(Dispatchers.IO) { LoopbackListener() }
                    listener = local
                    OAuthAttempt(local.redirectUri, saved.hostId, registration, retryClientId = if (clientId == null) incompleteClientId else null)
                }
                browserRequests.send(attempt.authorizationUrl())
                when (val callback = listener!!.await(attempt)) {
                    is CallbackResult.Denied -> throw AuthFailure(callback.message)
                    is CallbackResult.Success -> {
                        // A transport failure may happen after the server has consumed the code.
                        // Reauthorize with the issued client on retry rather than creating another registration.
                        if (attempt.registration == null) incompleteClientId = callback.clientId
                        mutableState.value = mutableState.value.copy(phase = AuthPhase.EXCHANGING, message = "Return to AppApp to finish sign-in.")
                        withTimeout(600_000) { foreground.first { it } }
                        mutableState.value = mutableState.value.copy(message = "Exchanging the sign-in code…")
                        val grant = try { api.exchange(attempt, callback) } catch (e: ApiFailure) {
                            if (e.serviceCode == "invalid_grant" && attempt.registration == null) incompleteClientId = callback.clientId
                            throw e
                        }
                        val idToken = grant.idToken ?: throw AuthFailure("OpenAI did not return an identity token. Please restart sign-in.")
                        mutableState.value = mutableState.value.copy(message = "Verifying your ChatGPT identity…")
                        val identity = validator.validate(idToken, callback.clientId, attempt.nonce, api.signingKeys())
                        attempt.registration?.let { previous ->
                            if (previous.identity.issuer != identity.issuer || previous.identity.subject != identity.subject) {
                                throw AuthFailure("This sign-in does not match the selected account. Add it as a separate account instead.")
                            }
                        }
                        val scopes = grant.scopes ?: emptySet()
                        val credentials = Credentials(grant.accessToken, grant.refreshToken, idToken,
                            now() + grant.expiresInSeconds * 1_000, scopes)
                        val registration = Registration(callback.clientId, identity, credentials)
                        lock.withLock {
                            val current = snapshot!!
                            current.registrations.firstOrNull { it.clientId == registration.clientId }?.let {
                                if (it.identity.issuer != identity.issuer || it.identity.subject != identity.subject) throw AuthFailure("This registration belongs to another account.")
                            }
                            save(CredentialSnapshot(current.hostId,
                                current.registrations.filterNot { it.clientId == registration.clientId } + registration, registration.clientId))
                            incompleteClientId = null
                            publish(if (canInfer(credentials)) "Signed in. You can now run the inference check." else
                                "Signed in, but ChatGPT plan usage was not granted. Review app permissions in ChatGPT settings and sign in again.")
                        }
                    }
                }
            } catch (_: CancellationException) {
                publish("Sign-in cancelled. You can try again.")
            } catch (e: Exception) {
                publish(message(e))
            } finally { listener?.close(); listener = null }
        }
    }

    override fun cancelSignIn() { authJob?.cancel(); listener?.close(); browserRequests.tryReceive() }

    override suspend fun activeRegistration(): Registration = lock.withLock {
        val current = snapshot ?: throw AuthFailure("Sign in with ChatGPT first.")
        var registration = current.registrations.firstOrNull { it.clientId == current.activeClientId }
            ?: throw AuthFailure("Select a ChatGPT account first.")
        val credentials = registration.credentials ?: throw AuthFailure("Sign in again to use this account.")
        if (!canInfer(credentials)) throw AuthFailure("ChatGPT plan usage is not enabled for this account. Review permissions and sign in again.")
        if (credentials.expiresAtMillis <= now() + 60_000) {
            try {
                val grant = api.refresh(registration)
                val idToken = grant.idToken ?: credentials.idToken
                if (grant.idToken != null) {
                    val identity = validator.validate(idToken, registration.clientId, null, api.signingKeys())
                    if (identity.issuer != registration.identity.issuer || identity.subject != registration.identity.subject) {
                        throw AuthFailure("The renewed session did not match your saved account. Sign in again.")
                    }
                }
                registration = Registration(registration.clientId, registration.identity,
                    Credentials(grant.accessToken, grant.refreshToken ?: credentials.refreshToken, idToken,
                        now() + grant.expiresInSeconds * 1_000, grant.scopes ?: credentials.scopes))
                save(CredentialSnapshot(current.hostId, current.registrations.map { if (it.clientId == registration.clientId) registration else it }, current.activeClientId))
                publish()
            } catch (e: ApiFailure) {
                if (e.kind == ApiFailure.Kind.AUTHENTICATION) {
                    val signedOut = Registration(registration.clientId, registration.identity, null)
                    save(CredentialSnapshot(current.hostId, current.registrations.map { if (it.clientId == signedOut.clientId) signedOut else it }, current.activeClientId))
                    publish("Your session expired. Sign in again.")
                }
                throw e
            }
        }
        if (!canInfer(registration.credentials!!)) throw AuthFailure("ChatGPT plan usage is no longer permitted. Review app access in ChatGPT settings.")
        registration
    }

    override suspend fun signOut(): String = lock.withLock {
        val current = snapshot ?: throw AuthFailure("Local sign-in data is unavailable.")
        val registration = current.registrations.firstOrNull { it.clientId == current.activeClientId }
            ?: throw AuthFailure("Select an account first.")
        mutableState.value = mutableState.value.copy(phase = AuthPhase.SIGNING_OUT, message = "Signing out…")
        try {
            var revoked = registration.credentials?.refreshToken == null
            for (attempt in 0..1) {
                try { api.revoke(registration); revoked = true; break }
                catch (e: ApiFailure) {
                    if (e.kind != ApiFailure.Kind.NETWORK && e.kind != ApiFailure.Kind.SERVICE) break
                    if (attempt == 0) delay(1_000)
                } catch (_: Exception) { break }
            }
            val signedOut = Registration(registration.clientId, registration.identity, null)
            save(CredentialSnapshot(current.hostId, current.registrations.map { if (it.clientId == registration.clientId) signedOut else it }, current.activeClientId))
            val result = if (revoked) "Signed out. Local tokens were removed." else
                "Signed out locally. Remote revocation was not confirmed; disconnect AppApp in ChatGPT settings if needed."
            publish(result)
            result
        } catch (e: Exception) { publish(message(e)); throw e }
    }

    override suspend fun resetLocalCredentials() = lock.withLock {
        withContext(Dispatchers.IO) { store.reset(); snapshot = store.read() }
        publish("Local sign-in data reset. Old registrations can be disconnected in ChatGPT settings.")
    }

    private suspend fun save(value: CredentialSnapshot) {
        withContext(Dispatchers.IO) { store.write(value) }
        snapshot = value
    }

    private fun publish(message: String? = null) {
        val saved = snapshot ?: return
        mutableState.value = AuthState(saved.registrations.map { AccountSummary(it.clientId, it.label, it.credentials != null, it.credentials?.let(::canInfer) == true) },
            saved.activeClientId, message = message)
    }

    companion object {
        fun canInfer(credentials: Credentials) = credentials.scopes.containsAll(setOf("resource.invoke", OpenAiAuth.PLAN_SCOPE))
        fun message(error: Exception): String = when (error) {
            is AuthFailure -> error.userMessage
            is ApiFailure -> error.userMessage
            else -> "The operation could not be completed. Please try again."
        }
    }
}
