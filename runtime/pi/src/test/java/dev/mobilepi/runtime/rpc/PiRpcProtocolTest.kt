package dev.mobilepi.runtime.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PiRpcProtocolTest {
    @Test
    fun `decodes agent end and settled as distinct lifecycle events`() {
        assertEquals(
            PiRpcMessage.Event.AgentEnd,
            PiRpcProtocol.decode(json.parseToJsonElement("""{"type":"agent_end","messages":[]}""").jsonObject),
        )
        assertEquals(
            PiRpcMessage.Event.AgentSettled,
            PiRpcProtocol.decode(json.parseToJsonElement("""{"type":"agent_settled"}""").jsonObject),
        )
    }

    private val json = Json

    @Test
    fun `decodes response correlation fields`() {
        val value = json.parseToJsonElement(
            """{"id":"req-4","type":"response","command":"prompt","success":true}""",
        ).jsonObject

        val response = PiRpcProtocol.decode(value) as PiRpcMessage.Response
        assertEquals("req-4", response.id)
        assertEquals("prompt", response.command)
        assertEquals(true, response.success)
    }

    @Test
    fun `decodes only text delta as visible streaming text`() {
        val value = json.parseToJsonElement(
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"hello"}}""",
        ).jsonObject

        assertEquals(PiRpcMessage.Event.TextDelta("hello"), PiRpcProtocol.decode(value))
    }

    @Test
    fun `decodes tool completion status by call id`() {
        val value = json.parseToJsonElement(
            """{"type":"tool_execution_end","toolCallId":"call-1","toolName":"write","isError":false,"result":{"content":[{"type":"text","text":"created file"}]}}""",
        ).jsonObject

        val event = PiRpcProtocol.decode(value) as PiRpcMessage.Event.ToolEnd
        assertEquals("call-1", event.callId)
        assertEquals("write", event.name)
        assertFalse(event.isError)
        assertEquals("created file", event.output)
    }
}
