package dev.mobilepi.runtime.agent

enum class MessageRole {
    USER,
    ASSISTANT,
    ERROR,
}

data class AgentMessage(
    val id: Long,
    val role: MessageRole,
    val text: String,
    val streaming: Boolean = false,
)

enum class ToolStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class ToolExecution(
    val callId: String,
    val name: String,
    val status: ToolStatus,
)

data class ProofResult(
    val nonce: String,
    val actual: String?,
    val success: Boolean,
)

data class AgentSnapshot(
    val state: AgentState = AgentState.STOPPED,
    val messages: List<AgentMessage> = emptyList(),
    val tools: List<ToolExecution> = emptyList(),
    val error: String? = null,
    val proofResult: ProofResult? = null,
)
