package com.wynh.appapp.ai.auth

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

interface AuthenticationRepository {
    val state: StateFlow<AuthState>
    val browserRequests: ReceiveChannel<String>
    fun setForeground(value: Boolean)
    fun beginSignIn(clientId: String? = null)
    fun cancelSignIn()
    suspend fun activeRegistration(): Registration
    suspend fun signOut(): String
    suspend fun resetLocalCredentials()
}

data class Identity(val issuer: String, val subject: String, val email: String?)

// Never use generated toString implementations for credential-bearing types.
class Credentials(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String,
    val expiresAtMillis: Long,
    val scopes: Set<String>
) {
    override fun toString() = "Credentials([redacted])"
}

class Registration(
    val clientId: String,
    val identity: Identity,
    val credentials: Credentials?
) {
    val label: String get() = "${identity.email ?: "ChatGPT account"} · ${clientId.takeLast(6)}"
    override fun toString() = "Registration([redacted])"
}

class CredentialSnapshot(
    val hostId: String,
    val registrations: List<Registration> = emptyList(),
    val activeClientId: String? = null
) {
    override fun toString() = "CredentialSnapshot([redacted])"
}

interface CredentialStore {
    fun read(): CredentialSnapshot
    fun write(snapshot: CredentialSnapshot)
    fun reset()
}

data class AuthState(
    val accounts: List<AccountSummary> = emptyList(),
    val activeClientId: String? = null,
    val phase: AuthPhase = AuthPhase.IDLE,
    val message: String? = null,
    val storageFailed: Boolean = false
)

data class AccountSummary(val clientId: String, val label: String, val signedIn: Boolean, val canInfer: Boolean)
enum class AuthPhase { RESTORING, IDLE, WAITING_FOR_BROWSER, EXCHANGING, SIGNING_OUT }

class AuthFailure(val userMessage: String) : Exception(userMessage)

object OpenAiAuth {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val DISCOVERY = "$ISSUER/.well-known/openid-configuration"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC_CLIENT = "dynamic_agent_client"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    val scopes = setOf("openid", "profile", "email", "offline_access", "resource.invoke", PLAN_SCOPE)
}
