package dev.mobilepi.runtime.agent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PiSessionParserTest {
    @Test
    fun `restores visible messages session identity and usage`() {
        val restored = PiSessionParser.restore(
            state = json.parseToJsonElement(
                """{"sessionId":"session-1","sessionName":"Refactor"}""",
            ),
            messages = json.parseToJsonElement(
                """{"messages":[{"role":"user","content":"Hello"},{"role":"assistant","content":[{"type":"thinking","thinking":"hidden"},{"type":"text","text":"Hi "},{"type":"text","text":"there"}]},{"role":"toolResult","content":[]}] }""",
            ),
            statistics = json.parseToJsonElement(
                """{"tokens":{"total":321},"cost":0.42,"contextUsage":{"tokens":100,"contextWindow":1000,"percent":10}}""",
            ),
        )

        assertEquals("session-1", restored.sessionId)
        assertEquals("Refactor", restored.sessionName)
        assertEquals(
            listOf(MessageRole.USER to "Hello", MessageRole.ASSISTANT to "Hi there"),
            restored.messages,
        )
        assertEquals(321L, restored.statistics.totalTokens)
        assertEquals(0.42, restored.statistics.costUsd)
        assertEquals(10.0, restored.statistics.contextPercent)
    }

    private val json = Json
}
