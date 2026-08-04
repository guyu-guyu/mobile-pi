package dev.mobilepi.runtime.rpc

import kotlin.random.Random
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictJsonlDecoderTest {
    @Test
    fun `decodes frames split at every byte boundary`() {
        val input = "{\"type\":\"message_update\",\"text\":\"你好🙂\"}\n".encodeToByteArray()

        for (split in 0..input.size) {
            val decoder = StrictJsonlDecoder()
            val results = decoder.feed(input.copyOfRange(0, split)) +
                decoder.feed(input.copyOfRange(split, input.size))

            val frame = results.single() as JsonlDecodeResult.Frame
            assertEquals("你好🙂", frame.value["text"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `decodes randomly chunked and coalesced frames`() {
        val source = (0 until 50).joinToString("") { "{\"index\":$it}\n" }.encodeToByteArray()
        val decoder = StrictJsonlDecoder()
        val results = mutableListOf<JsonlDecodeResult>()
        var offset = 0
        val random = Random(7)
        while (offset < source.size) {
            val size = random.nextInt(1, 19).coerceAtMost(source.size - offset)
            results += decoder.feed(source, offset, size)
            offset += size
        }

        val indexes = results.map { (it as JsonlDecodeResult.Frame).value["index"]?.jsonPrimitive?.content }
        assertEquals((0 until 50).map(Int::toString), indexes)
    }

    @Test
    fun `uses LF only and accepts optional CR`() {
        val results = StrictJsonlDecoder().feed(
            "{\"text\":\"a\\u2028b\\u2029c\"}\r\n".encodeToByteArray(),
        )

        val frame = results.single() as JsonlDecodeResult.Frame
        assertEquals("a\u2028b\u2029c", frame.value["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reports empty invalid and non-object frames`() {
        val decoder = StrictJsonlDecoder()
        val results = decoder.feed("\n{broken}\n[]\n".encodeToByteArray())

        assertEquals(
            listOf(JsonlErrorCode.EMPTY_FRAME, JsonlErrorCode.INVALID_JSON, JsonlErrorCode.NON_OBJECT_JSON),
            results.map { (it as JsonlDecodeResult.Error).code },
        )
        assertTrue(results.all { !(it as JsonlDecodeResult.Error).fatal })
    }

    @Test
    fun `reports malformed UTF-8 without corrupting following frame`() {
        val decoder = StrictJsonlDecoder()
        val bytes = byteArrayOf(0x7b, 0x22, 0x78, 0x22, 0x3a, 0xc3.toByte(), 0x28, 0x7d, 0x0a) +
            "{\"ok\":true}\n".encodeToByteArray()
        val results = decoder.feed(bytes)

        assertEquals(JsonlErrorCode.INVALID_UTF8, (results[0] as JsonlDecodeResult.Error).code)
        assertTrue(results[1] is JsonlDecodeResult.Frame)
    }

    @Test
    fun `frame limit is fatal and closes decoder`() {
        val decoder = StrictJsonlDecoder(maxFrameBytes = 8)
        val first = decoder.feed("{\"123456789\"".encodeToByteArray()).single() as JsonlDecodeResult.Error
        val second = decoder.feed("}\n".encodeToByteArray()).single() as JsonlDecodeResult.Error

        assertEquals(JsonlErrorCode.FRAME_TOO_LARGE, first.code)
        assertTrue(first.fatal)
        assertEquals(JsonlErrorCode.DECODER_CLOSED, second.code)
    }

    @Test
    fun `finish reports trailing partial frame`() {
        val decoder = StrictJsonlDecoder()
        assertTrue(decoder.feed("{\"ok\":true}".encodeToByteArray()).isEmpty())

        val error = decoder.finish().single() as JsonlDecodeResult.Error
        assertEquals(JsonlErrorCode.TRUNCATED_FRAME, error.code)
        assertTrue(error.fatal)
        assertFalse(error.summary.isBlank())
    }

    @Test
    fun `invalid frame summaries redact common secrets`() {
        val result = StrictJsonlDecoder().feed(
            "{\"apiKey\":\"secret-value\",broken}\n".encodeToByteArray(),
        ).single() as JsonlDecodeResult.Error

        assertFalse(result.summary.contains("secret-value"))
        assertTrue(result.summary.contains("[REDACTED]"))
    }
}
