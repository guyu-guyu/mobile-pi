package dev.mobilepi.runtime.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class RestoredSession(
    val sessionId: String?,
    val sessionName: String?,
    val messages: List<Pair<MessageRole, String>>,
    val statistics: SessionStatistics,
)

internal object PiSessionParser {
    fun restore(
        state: JsonElement?,
        messages: JsonElement?,
        statistics: JsonElement?,
    ): RestoredSession {
        val stateObject = state.asObject()
        val messageValues = messages.asObject()?.get("messages") as? JsonArray
        return RestoredSession(
            sessionId = stateObject.string("sessionId"),
            sessionName = stateObject.string("sessionName"),
            messages = messageValues.orEmpty().mapNotNull(::parseMessage),
            statistics = parseStatistics(statistics),
        )
    }

    fun parseStatistics(value: JsonElement?): SessionStatistics {
        val data = value.asObject()
        val tokens = data?.get("tokens").asObject()
        val context = data?.get("contextUsage").asObject()
        return SessionStatistics(
            totalTokens = tokens.long("total"),
            costUsd = data.double("cost"),
            contextTokens = context.long("tokens"),
            contextWindow = context.long("contextWindow"),
            contextPercent = context.double("percent"),
        )
    }

    private fun parseMessage(value: JsonElement): Pair<MessageRole, String>? {
        val message = value.asObject() ?: return null
        val role = when (message.string("role")) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            else -> return null
        }
        val text = extractText(message["content"])
        if (text.isBlank()) return null
        return role to text
    }

    private fun extractText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { part ->
            val value = part.asObject() ?: return@mapNotNull null
            if (value.string("type") == "text") value.string("text") else null
        }.joinToString("")
        else -> ""
    }

    private fun JsonElement?.asObject(): JsonObject? =
        runCatching { this?.jsonObject }.getOrNull()

    private fun JsonObject?.string(name: String): String? =
        this?.get(name)?.jsonPrimitive?.contentOrNull

    private fun JsonObject?.long(name: String): Long? =
        this?.get(name)?.jsonPrimitive?.longOrNull

    private fun JsonObject?.double(name: String): Double? =
        this?.get(name)?.jsonPrimitive?.doubleOrNull
}
