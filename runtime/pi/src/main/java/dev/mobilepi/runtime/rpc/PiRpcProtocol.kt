package dev.mobilepi.runtime.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface PiRpcMessage {
    data class Response(
        val id: String?,
        val command: String?,
        val success: Boolean,
        val data: JsonElement?,
        val error: String?,
    ) : PiRpcMessage

    sealed interface Event : PiRpcMessage {
        data object AgentStart : Event
        data object AgentEnd : Event
        data class TextDelta(val text: String) : Event
        data class MessageEnd(val message: JsonElement?) : Event
        data class ToolStart(val callId: String, val name: String) : Event
        data class ToolUpdate(val callId: String, val name: String) : Event
        data class ToolEnd(val callId: String, val name: String, val isError: Boolean) : Event
        data class ExtensionError(val message: String) : Event
        data class Unsupported(val type: String, val payload: JsonObject) : Event
    }
}

class PiRpcProtocolException(message: String) : IllegalArgumentException(message)

object PiRpcProtocol {
    fun decode(value: JsonObject): PiRpcMessage {
        val type = value.string("type") ?: throw PiRpcProtocolException("RPC message has no type")
        if (type == "response") {
            return PiRpcMessage.Response(
                id = value.string("id"),
                command = value.string("command"),
                success = value["success"]?.jsonPrimitive?.booleanOrNull ?: false,
                data = value["data"],
                error = errorText(value["error"]),
            )
        }
        return when (type) {
            "agent_start" -> PiRpcMessage.Event.AgentStart
            "agent_end" -> PiRpcMessage.Event.AgentEnd
            "message_update" -> decodeMessageUpdate(value)
            "message_end" -> PiRpcMessage.Event.MessageEnd(value["message"])
            "tool_execution_start" -> PiRpcMessage.Event.ToolStart(
                callId = value.requiredString("toolCallId"),
                name = value.requiredString("toolName"),
            )
            "tool_execution_update" -> PiRpcMessage.Event.ToolUpdate(
                callId = value.requiredString("toolCallId"),
                name = value.requiredString("toolName"),
            )
            "tool_execution_end" -> PiRpcMessage.Event.ToolEnd(
                callId = value.requiredString("toolCallId"),
                name = value.requiredString("toolName"),
                isError = value["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            "extension_error" -> PiRpcMessage.Event.ExtensionError(
                value.string("error") ?: value.string("message") ?: "Extension error",
            )
            else -> PiRpcMessage.Event.Unsupported(type, value)
        }
    }

    private fun decodeMessageUpdate(value: JsonObject): PiRpcMessage.Event {
        val update = value["assistantMessageEvent"]?.jsonObject
            ?: return PiRpcMessage.Event.Unsupported("message_update", value)
        return if (update.string("type") == "text_delta") {
            PiRpcMessage.Event.TextDelta(update.string("delta").orEmpty())
        } else {
            PiRpcMessage.Event.Unsupported("message_update", value)
        }
    }

    private fun errorText(element: JsonElement?): String? {
        if (element == null) return null
        return runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
            ?: runCatching { element.jsonObject.string("message") }.getOrNull()
            ?: element.toString()
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.requiredString(name: String): String =
        string(name) ?: throw PiRpcProtocolException("RPC message has no $name")
}
