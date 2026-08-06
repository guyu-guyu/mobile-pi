package dev.mobilepi.runtime.agent

import dev.mobilepi.runtime.rpc.PiRpcMessage
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object AgentEventReducer {
    fun reduce(snapshot: AgentSnapshot, event: PiRpcMessage.Event, nextMessageId: () -> Long): AgentSnapshot =
        when (event) {
            PiRpcMessage.Event.AgentStart -> snapshot.copy(
                state = AgentState.RUNNING,
                error = null,
                proofResult = null,
            )
            PiRpcMessage.Event.AgentEnd -> snapshot.copy(
                messages = snapshot.messages.map { message ->
                    if (message.role == MessageRole.ASSISTANT && message.streaming) {
                        message.copy(streaming = false)
                    } else {
                        message
                    }
                },
            )
            PiRpcMessage.Event.AgentSettled -> snapshot.copy(state = AgentState.READY)
            is PiRpcMessage.Event.TextDelta -> appendAssistantText(snapshot, event.text, nextMessageId)
            is PiRpcMessage.Event.MessageEnd -> finishAssistant(snapshot, event, nextMessageId)
            is PiRpcMessage.Event.ToolStart -> snapshot.copy(
                tools = snapshot.tools.filterNot { it.callId == event.callId } +
                    ToolExecution(event.callId, event.name, ToolStatus.RUNNING),
            )
            is PiRpcMessage.Event.ToolUpdate -> snapshot.copy(
                tools = snapshot.tools.map { tool ->
                    if (tool.callId == event.callId) tool.copy(output = event.output) else tool
                },
            )
            is PiRpcMessage.Event.ToolEnd -> snapshot.copy(
                tools = snapshot.tools.map { tool ->
                    if (tool.callId == event.callId) {
                        tool.copy(
                            status = if (event.isError) ToolStatus.FAILED else ToolStatus.SUCCEEDED,
                            output = event.output ?: tool.output,
                        )
                    } else {
                        tool
                    }
                },
            )
            is PiRpcMessage.Event.ExtensionError -> addError(snapshot, event.message, nextMessageId)
            is PiRpcMessage.Event.Unsupported -> snapshot
        }

    private fun appendAssistantText(
        snapshot: AgentSnapshot,
        delta: String,
        nextMessageId: () -> Long,
    ): AgentSnapshot {
        val active = snapshot.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.streaming }
        return if (active == null) {
            snapshot.copy(
                messages = snapshot.messages + AgentMessage(
                    id = nextMessageId(),
                    role = MessageRole.ASSISTANT,
                    text = delta,
                    streaming = true,
                ),
            )
        } else {
            snapshot.copy(
                messages = snapshot.messages.map { message ->
                    if (message.id == active.id) message.copy(text = message.text + delta) else message
                },
            )
        }
    }

    private fun finishAssistant(
        snapshot: AgentSnapshot,
        event: PiRpcMessage.Event.MessageEnd,
        nextMessageId: () -> Long,
    ): AgentSnapshot {
        val errorMessage = runCatching {
            event.message?.jsonObject?.get("errorMessage")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val finished = snapshot.copy(
            messages = snapshot.messages.map { message ->
                if (message.role == MessageRole.ASSISTANT && message.streaming) {
                    message.copy(streaming = false)
                } else {
                    message
                }
            },
        )
        return if (errorMessage.isNullOrBlank()) finished else addError(finished, errorMessage, nextMessageId)
    }

    fun addError(snapshot: AgentSnapshot, message: String, nextMessageId: () -> Long): AgentSnapshot =
        snapshot.copy(
            error = message,
            messages = snapshot.messages + AgentMessage(
                id = nextMessageId(),
                role = MessageRole.ERROR,
                text = message,
            ),
        )
}
