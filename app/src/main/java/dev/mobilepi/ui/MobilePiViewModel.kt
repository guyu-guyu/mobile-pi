package dev.mobilepi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mobilepi.runtime.agent.AgentMessage
import dev.mobilepi.runtime.agent.PiAgentController
import dev.mobilepi.runtime.agent.ProofResult
import dev.mobilepi.runtime.agent.AgentState
import dev.mobilepi.runtime.agent.ToolExecution
import dev.mobilepi.runtime.model.RuntimeInstallState
import dev.mobilepi.runtime.model.RuntimeLogEntry
import dev.mobilepi.runtime.model.RuntimeStatus
import dev.mobilepi.runtime.process.PiAgentConfig
import dev.mobilepi.runtime.setup.TerminalCoreRuntimeSetup
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MobilePiUiState(
    val provider: String = "",
    val model: String = "",
    val apiKey: String = "",
    val prompt: String = "",
    val agentState: AgentState = AgentState.STOPPED,
    val messages: List<AgentMessage> = emptyList(),
    val tools: List<ToolExecution> = emptyList(),
    val proofResult: ProofResult? = null,
    val agentError: String? = null,
    val diagnostics: List<RuntimeLogEntry> = emptyList(),
    val operationInProgress: Boolean = false,
)

class MobilePiViewModel(application: Application) : AndroidViewModel(application) {
    private val runtimeSetup = TerminalCoreRuntimeSetup(application)
    private val agent = PiAgentController(application)
    private val diagnosticSequence = AtomicLong()
    private val _uiState = MutableStateFlow(MobilePiUiState())
    val uiState = _uiState.asStateFlow()

    val runtimeStatus: StateFlow<RuntimeStatus> = runtimeSetup.status.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = runtimeSetup.status.value,
    )

    init {
        viewModelScope.launch {
            runtimeSetup.logs.collect { entry ->
                appendDiagnostic(entry.stage, entry.message, entry.exitCode)
            }
        }
        viewModelScope.launch {
            agent.snapshot.collect { snapshot ->
                _uiState.update {
                    it.copy(
                        agentState = snapshot.state,
                        messages = snapshot.messages,
                        tools = snapshot.tools,
                        proofResult = snapshot.proofResult,
                        agentError = snapshot.error,
                    )
                }
            }
        }
        viewModelScope.launch {
            agent.diagnostics.collect { entry ->
                appendDiagnostic("rpc/${entry.stream}", entry.message)
            }
        }
        if (runtimeSetup.status.value.state == RuntimeInstallState.VERIFYING) {
            inspectRuntime()
        }
    }

    fun installRuntime() {
        if (_uiState.value.operationInProgress) return
        viewModelScope.launch {
            setBusy(true)
            runCatching { runtimeSetup.install() }
            setBusy(false)
        }
    }

    fun inspectRuntime() {
        if (_uiState.value.operationInProgress) return
        viewModelScope.launch {
            setBusy(true)
            runtimeSetup.inspect()
            setBusy(false)
        }
    }

    fun clearRuntime() {
        if (_uiState.value.operationInProgress) return
        viewModelScope.launch {
            setBusy(true)
            runCatching { agent.stop() }
            runCatching { runtimeSetup.clear() }
            setBusy(false)
        }
    }

    fun startAgent() {
        val state = _uiState.value
        if (runtimeStatus.value.state != RuntimeInstallState.READY ||
            state.agentState !in setOf(AgentState.STOPPED, AgentState.CRASHED)
        ) return
        val config = PiAgentConfig(
            provider = state.provider,
            model = state.model,
            apiKey = state.apiKey,
        )
        viewModelScope.launch {
            runCatching { agent.start(config) }
                .onFailure { appendDiagnostic("agent/start", safeError(it)) }
        }
    }

    fun stopAgent() {
        if (_uiState.value.agentState in setOf(AgentState.STOPPED, AgentState.STOPPING)) return
        viewModelScope.launch {
            runCatching { agent.stop() }
                .onFailure { appendDiagnostic("agent/stop", safeError(it)) }
        }
    }

    fun sendPrompt() {
        val state = _uiState.value
        val text = state.prompt.trim()
        if (state.agentState != AgentState.READY || text.isEmpty()) return
        _uiState.update { it.copy(prompt = "") }
        viewModelScope.launch {
            runCatching { agent.prompt(text) }
                .onFailure { appendDiagnostic("agent/prompt", safeError(it)) }
        }
    }

    fun abortAgent() {
        if (_uiState.value.agentState != AgentState.RUNNING) return
        viewModelScope.launch {
            runCatching { agent.abort() }
                .onFailure { appendDiagnostic("agent/abort", safeError(it)) }
        }
    }

    fun verifyFileTool() {
        if (_uiState.value.agentState != AgentState.READY) return
        viewModelScope.launch {
            runCatching { agent.verifyFileTool() }
                .onFailure { appendDiagnostic("agent/proof", safeError(it)) }
        }
    }

    fun updateProvider(value: String) = _uiState.update { it.copy(provider = value) }
    fun updateModel(value: String) = _uiState.update { it.copy(model = value) }
    fun updateApiKey(value: String) = _uiState.update { it.copy(apiKey = value) }
    fun updatePrompt(value: String) = _uiState.update { it.copy(prompt = value) }

    private fun setBusy(value: Boolean) = _uiState.update { it.copy(operationInProgress = value) }

    private fun appendDiagnostic(stage: String, message: String, exitCode: Int? = null) {
        val entry = RuntimeLogEntry(
            sequence = diagnosticSequence.incrementAndGet(),
            stage = stage,
            message = message,
            exitCode = exitCode,
        )
        _uiState.update { state ->
            state.copy(diagnostics = (state.diagnostics + entry).takeLast(MAX_LOG_ENTRIES))
        }
    }

    private fun safeError(error: Throwable): String {
        val apiKey = _uiState.value.apiKey
        val message = (error.message ?: error::class.java.simpleName).take(MAX_ERROR_CHARS)
        return if (apiKey.isBlank()) message else message.replace(apiKey, "[REDACTED]")
    }

    override fun onCleared() {
        agent.close()
        super.onCleared()
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 100
        private const val MAX_ERROR_CHARS = 1_000
    }
}
