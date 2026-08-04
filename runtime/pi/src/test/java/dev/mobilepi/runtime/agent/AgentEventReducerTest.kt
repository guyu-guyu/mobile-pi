package dev.mobilepi.runtime.agent

import dev.mobilepi.runtime.rpc.PiRpcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentEventReducerTest {
    private var id = 0L
    private val nextId = { ++id }

    @Test
    fun `streams text into one assistant message and settles`() {
        var snapshot = AgentSnapshot(state = AgentState.READY)
        snapshot = AgentEventReducer.reduce(snapshot, PiRpcMessage.Event.AgentStart, nextId)
        snapshot = AgentEventReducer.reduce(snapshot, PiRpcMessage.Event.TextDelta("hello "), nextId)
        snapshot = AgentEventReducer.reduce(snapshot, PiRpcMessage.Event.TextDelta("world"), nextId)
        snapshot = AgentEventReducer.reduce(snapshot, PiRpcMessage.Event.AgentEnd, nextId)

        assertEquals(AgentState.READY, snapshot.state)
        assertEquals("hello world", snapshot.messages.single().text)
        assertFalse(snapshot.messages.single().streaming)
    }

    @Test
    fun `correlates tool state by call id`() {
        var snapshot = AgentSnapshot(state = AgentState.RUNNING)
        snapshot = AgentEventReducer.reduce(
            snapshot,
            PiRpcMessage.Event.ToolStart("call-1", "write"),
            nextId,
        )
        snapshot = AgentEventReducer.reduce(
            snapshot,
            PiRpcMessage.Event.ToolEnd("call-1", "write", isError = false),
            nextId,
        )

        assertEquals(ToolStatus.SUCCEEDED, snapshot.tools.single().status)
    }
}
