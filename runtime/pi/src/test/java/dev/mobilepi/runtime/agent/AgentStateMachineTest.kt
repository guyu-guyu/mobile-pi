package dev.mobilepi.runtime.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentStateMachineTest {
    @Test
    fun `covers successful prompt abort and stop lifecycle`() {
        val machine = AgentStateMachine()

        assertEquals(AgentState.STARTING, machine.transition(AgentSignal.StartRequested))
        assertEquals(AgentState.READY, machine.transition(AgentSignal.RpcResponseReceived))
        assertEquals(AgentState.RUNNING, machine.transition(AgentSignal.AgentStarted))
        assertEquals(AgentState.READY, machine.transition(AgentSignal.AbortCompleted))
        assertEquals(AgentState.STOPPING, machine.transition(AgentSignal.StopRequested))
        assertEquals(AgentState.STOPPED, machine.transition(AgentSignal.ProcessExited))
    }

    @Test
    fun `protocol failure requires explicit restart`() {
        val machine = AgentStateMachine()
        machine.transition(AgentSignal.StartRequested)
        assertEquals(AgentState.CRASHED, machine.transition(AgentSignal.ProtocolFailure))
        assertEquals(AgentState.STARTING, machine.transition(AgentSignal.RestartRequested))
    }

    @Test
    fun `rejects events that do not belong to current lifecycle`() {
        val machine = AgentStateMachine()
        assertThrows(InvalidAgentTransition::class.java) {
            machine.transition(AgentSignal.AgentStarted)
        }
    }
}
