package dev.mobilepi.runtime.rpc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

enum class JsonlErrorCode {
    EMPTY_FRAME,
    INVALID_UTF8,
    INVALID_JSON,
    NON_OBJECT_JSON,
    FRAME_TOO_LARGE,
    TRUNCATED_FRAME,
    DECODER_CLOSED,
}

sealed interface JsonlDecodeResult {
    data class Frame(val value: JsonObject) : JsonlDecodeResult

    data class Error(
        val code: JsonlErrorCode,
        val summary: String,
        val fatal: Boolean,
    ) : JsonlDecodeResult
}

class StrictJsonlDecoder(
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val frame = ByteArrayOutputStream()
    private var closed = false
    private var failed = false

    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
    }

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<JsonlDecodeResult> {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        if (closed || failed) {
            return listOf(error(JsonlErrorCode.DECODER_CLOSED, "decoder is not accepting input", true))
        }

        val results = mutableListOf<JsonlDecodeResult>()
        val end = offset + length
        for (index in offset until end) {
            val byte = bytes[index]
            if (byte == LF) {
                results += decodeFrame()
                if (failed) break
            } else {
                frame.write(byte.toInt())
                if (frame.size() > maxFrameBytes) {
                    failed = true
                    results += error(
                        JsonlErrorCode.FRAME_TOO_LARGE,
                        "frame exceeds $maxFrameBytes bytes",
                        true,
                    )
                    break
                }
            }
        }
        return results
    }

    fun finish(): List<JsonlDecodeResult> {
        if (closed) return emptyList()
        closed = true
        if (failed || frame.size() == 0) return emptyList()
        val summary = sanitize(rawFrame().decodeToString())
        frame.reset()
        return listOf(error(JsonlErrorCode.TRUNCATED_FRAME, summary, true))
    }

    private fun decodeFrame(): JsonlDecodeResult {
        var bytes = frame.toByteArray()
        frame.reset()
        if (bytes.lastOrNull() == CR) {
            bytes = bytes.copyOf(bytes.size - 1)
        }
        if (bytes.isEmpty()) {
            return error(JsonlErrorCode.EMPTY_FRAME, "empty frame", false)
        }

        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            return error(JsonlErrorCode.INVALID_UTF8, hexSummary(bytes), false)
        }

        val element = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            return error(JsonlErrorCode.INVALID_JSON, sanitize(text), false)
        }
        return if (element is JsonObject) {
            JsonlDecodeResult.Frame(element)
        } else {
            error(JsonlErrorCode.NON_OBJECT_JSON, sanitize(text), false)
        }
    }

    private fun rawFrame(): ByteArray = frame.toByteArray()

    private fun error(code: JsonlErrorCode, summary: String, fatal: Boolean) =
        JsonlDecodeResult.Error(code, summary.take(MAX_SUMMARY_CHARS), fatal)

    private fun sanitize(value: String): String =
        value
            .replace(SECRET_FIELD_PATTERN) { match -> "${match.groupValues[1]}\"[REDACTED]\"" }
            .replace(AUTH_HEADER_PATTERN, "Authorization: [REDACTED]")

    private fun hexSummary(bytes: ByteArray): String =
        bytes.take(32).joinToString(" ") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        const val DEFAULT_MAX_FRAME_BYTES = 1024 * 1024
        private const val MAX_SUMMARY_CHARS = 256
        private const val LF: Byte = 0x0A
        private const val CR: Byte = 0x0D
        private val SECRET_FIELD_PATTERN =
            Regex("(?i)(\\\"(?:api[_-]?key|token|authorization)\\\"\\s*:\\s*)\\\"[^\\\"]*\\\"")
        private val AUTH_HEADER_PATTERN = Regex("(?i)Authorization\\s*:\\s*[^\\s,}]+")
    }
}
