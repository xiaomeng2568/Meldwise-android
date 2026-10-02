package io.github.xiaomeng2568.meldwise.provider

import kotlinx.coroutines.flow.Flow

enum class Capability { STREAMING, REASONING, TOOLS, IMAGE_INPUT, SYSTEM_PROMPT, TEMPERATURE,
    TOP_P, MAX_OUTPUT_TOKENS, RESPONSE_FORMAT, PARALLEL_TOOL_CALLS, STORE_FALSE, USAGE, PLAN_USAGE, API_KEY }
data class ProviderCapability(val supported: Set<Capability>) {
    fun intersect(model: ProviderCapability, auth: ProviderCapability) =
        ProviderCapability(supported intersect model.supported intersect auth.supported)
    operator fun contains(capability: Capability) = capability in supported
}
enum class ModelAvailability { UNKNOWN, AVAILABLE, UNAVAILABLE, TEMPORARILY_UNAVAILABLE, UNSUPPORTED_FOR_AUTH_PATH }
data class LlmModel(val id: String, val displayName: String, val origin: String,
    val capabilities: ProviderCapability, val availability: ModelAvailability = ModelAvailability.UNKNOWN)
enum class MessageRole { USER, ASSISTANT, DEVELOPER }
class LlmMessage(val role: MessageRole, val text: String) {
    override fun toString() = "LlmMessage(role=$role, text=[REDACTED])"
}
class LlmRequest(val model: String, val messages: List<LlmMessage>, val instructions: String? = null,
    val temperature: Double? = null, val topP: Double? = null, val maxOutputTokens: Int? = null,
    val responseFormat: String? = null, val stream: Boolean = true, val store: Boolean = false) {
    override fun toString() = "LlmRequest([REDACTED])"
}
enum class ErrorKind { NETWORK, TIMEOUT, AUTHENTICATION, AUTHORIZATION, RATE_LIMIT, PLAN_USAGE_LIMIT,
    BILLING, CONTEXT_OVERFLOW, MODEL_UNAVAILABLE, UNSUPPORTED_CAPABILITY, CONTENT_REJECTED, SERVER,
    STREAM_INTERRUPTED, PROTOCOL, CANCELLED, STORAGE, UNKNOWN }
data class LlmError(val kind: ErrorKind, val isRetryable: Boolean = false,
    val requiresReauth: Boolean = false, val requiresUserAction: Boolean = true,
    val mayHaveProducedOutput: Boolean = false)
data class Usage(val inputTokens: Long?, val outputTokens: Long?, val totalTokens: Long?)
sealed interface LlmEvent {
    class TextDelta(val text: String) : LlmEvent { override fun toString() = "TextDelta([REDACTED])" }
    class ReasoningDelta(val text: String) : LlmEvent { override fun toString() = "ReasoningDelta([REDACTED])" }
    data class ToolCallStarted(val index: Int) : LlmEvent
    class ToolCallDelta(val index: Int, val arguments: String) : LlmEvent { override fun toString() = "ToolCallDelta([REDACTED])" }
    data class ToolCallCompleted(val index: Int) : LlmEvent
    data class UsageDelta(val usage: Usage) : LlmEvent
    data class UsageFinal(val usage: Usage) : LlmEvent
    data class RequestId(val value: String) : LlmEvent
    data class ProviderMetadata(val kind: String) : LlmEvent
    data class RateLimitInfo(val retryAfterSeconds: Long?) : LlmEvent
    data class Completed(val usage: Usage?) : LlmEvent
    data class Incomplete(val error: LlmError) : LlmEvent
    data object Cancelled : LlmEvent
    data class Failed(val error: LlmError) : LlmEvent
}
enum class ProviderStatus { DISCONNECTED, IDENTITY_ONLY, READY, REAUTH_REQUIRED, UNAVAILABLE }
interface LlmProvider {
    val id: String
    val displayName: String
    val capabilities: ProviderCapability
    suspend fun listModels(): List<LlmModel>
    fun streamResponse(request: LlmRequest): Flow<LlmEvent>
    suspend fun validateConnection(): ProviderStatus
}
