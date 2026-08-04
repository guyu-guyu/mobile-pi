package dev.mobilepi.runtime.agent

enum class AgentState {
    STOPPED,
    STARTING,
    READY,
    RUNNING,
    STOPPING,
    CRASHED,
}

sealed interface AgentSignal {
    data object StartRequested : AgentSignal
    data object RpcResponseReceived : AgentSignal
    data object AgentStarted : AgentSignal
    data object AgentSettled : AgentSignal
    data object AbortCompleted : AgentSignal
    data object StopRequested : AgentSignal
    data object ProcessExited : AgentSignal
    data object UnexpectedExit : AgentSignal
    data object ProtocolFailure : AgentSignal
    data object RestartRequested : AgentSignal
}

class InvalidAgentTransition(
    state: AgentState,
    signal: AgentSignal,
) : IllegalStateException("Cannot apply ${signal::class.simpleName} while agent is $state")

class AgentStateMachine(initialState: AgentState = AgentState.STOPPED) {
    var state: AgentState = initialState
        private set

    fun transition(signal: AgentSignal): AgentState {
        state = when (state) {
            AgentState.STOPPED -> when (signal) {
                AgentSignal.StartRequested -> AgentState.STARTING
                else -> invalid(signal)
            }
            AgentState.STARTING -> when (signal) {
                AgentSignal.RpcResponseReceived -> AgentState.READY
                AgentSignal.StopRequested -> AgentState.STOPPING
                AgentSignal.UnexpectedExit,
                AgentSignal.ProtocolFailure,
                -> AgentState.CRASHED
                else -> invalid(signal)
            }
            AgentState.READY -> when (signal) {
                AgentSignal.AgentStarted -> AgentState.RUNNING
                AgentSignal.StopRequested -> AgentState.STOPPING
                AgentSignal.UnexpectedExit,
                AgentSignal.ProtocolFailure,
                -> AgentState.CRASHED
                else -> invalid(signal)
            }
            AgentState.RUNNING -> when (signal) {
                AgentSignal.AgentSettled,
                AgentSignal.AbortCompleted,
                -> AgentState.READY
                AgentSignal.StopRequested -> AgentState.STOPPING
                AgentSignal.UnexpectedExit,
                AgentSignal.ProtocolFailure,
                -> AgentState.CRASHED
                else -> invalid(signal)
            }
            AgentState.STOPPING -> when (signal) {
                AgentSignal.ProcessExited -> AgentState.STOPPED
                AgentSignal.UnexpectedExit -> AgentState.STOPPED
                else -> invalid(signal)
            }
            AgentState.CRASHED -> when (signal) {
                AgentSignal.RestartRequested,
                AgentSignal.StartRequested,
                -> AgentState.STARTING
                AgentSignal.StopRequested -> AgentState.STOPPED
                else -> invalid(signal)
            }
        }
        return state
    }

    private fun invalid(signal: AgentSignal): Nothing = throw InvalidAgentTransition(state, signal)
}
