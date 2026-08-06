package dev.mobilepi.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mobilepi.agent.AgentRecoveryStore
import dev.mobilepi.agent.PiAgentServiceClient
import dev.mobilepi.credentials.ProviderProfile
import dev.mobilepi.credentials.ProviderProfileStore
import dev.mobilepi.runtime.agent.AgentMessage
import dev.mobilepi.runtime.agent.ProofResult
import dev.mobilepi.runtime.agent.AgentState
import dev.mobilepi.runtime.agent.SessionStatistics
import dev.mobilepi.runtime.agent.ToolExecution
import dev.mobilepi.runtime.model.RuntimeInstallState
import dev.mobilepi.runtime.model.RuntimeLogEntry
import dev.mobilepi.runtime.model.RuntimeStatus
import dev.mobilepi.runtime.process.PiAgentConfig
import dev.mobilepi.runtime.setup.TerminalCoreRuntimeSetup
import dev.mobilepi.workspaces.ManagedWorkspace
import dev.mobilepi.workspaces.ManagedWorkspaceSyncPreview
import dev.mobilepi.workspaces.WorkspaceRepository
import dev.mobilepi.workspaces.sync.WorkspaceProgress
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val workspace: ManagedWorkspace? = null,
    val workspacePermissionAvailable: Boolean = false,
    val workspaceProgress: WorkspaceProgress? = null,
    val workspaceOperationInProgress: Boolean = false,
    val syncPreview: ManagedWorkspaceSyncPreview? = null,
    val workspaceError: String? = null,
    val profileSaved: Boolean = false,
    val profileError: String? = null,
    val recoveryNotice: String? = null,
    val sessionId: String? = null,
    val sessionName: String? = null,
    val sessionStatistics: SessionStatistics = SessionStatistics(),
    val agentStartInProgress: Boolean = false,
)

class MobilePiViewModel(application: Application) : AndroidViewModel(application) {
    private val runtimeSetup = TerminalCoreRuntimeSetup(application)
    private val agent = PiAgentServiceClient(application, viewModelScope)
    private val workspaceRepository = WorkspaceRepository(application)
    private val profileStore = ProviderProfileStore(application)
    private val recoveryStore = AgentRecoveryStore(application)
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
                        sessionId = snapshot.sessionId,
                        sessionName = snapshot.sessionName,
                        sessionStatistics = snapshot.sessionStatistics,
                        recoveryNotice = if (snapshot.state != AgentState.STOPPED) {
                            null
                        } else {
                            it.recoveryNotice
                        },
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
        loadPersistedState()
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
        if (state.agentStartInProgress) return
        val workspace = state.workspace ?: return
        if (!state.workspacePermissionAvailable || state.workspaceOperationInProgress) return
        val config = PiAgentConfig(
            provider = state.provider,
            model = state.model,
            apiKey = state.apiKey,
            workspaceDirectory = workspace.filesDirectory.absolutePath,
            sessionDirectory = sessionDirectory(workspace).absolutePath,
            resumeExistingSession = hasSession(workspace),
        )
        _uiState.update { it.copy(agentStartInProgress = true) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    profileStore.save(
                        ProviderProfile(state.provider, state.model, state.apiKey),
                    )
                }
                _uiState.update {
                    it.copy(profileSaved = true, profileError = null, syncPreview = null)
                }
                agent.start(config, workspace.id)
            }
                .onFailure { appendDiagnostic("agent/start", safeError(it)) }
            _uiState.update { it.copy(agentStartInProgress = false) }
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

    fun newSession() {
        if (_uiState.value.agentState != AgentState.READY) return
        viewModelScope.launch {
            runCatching { agent.newSession() }
                .onFailure { appendDiagnostic("agent/session", safeError(it)) }
        }
    }

    fun verifyFileTool() {
        if (_uiState.value.agentState != AgentState.READY) return
        viewModelScope.launch {
            runCatching { agent.verifyFileTool() }
                .onFailure { appendDiagnostic("agent/proof", safeError(it)) }
        }
    }

    fun updateProvider(value: String) = _uiState.update {
        it.copy(provider = value, profileSaved = false, profileError = null)
    }
    fun updateModel(value: String) = _uiState.update {
        it.copy(model = value, profileSaved = false, profileError = null)
    }
    fun updateApiKey(value: String) = _uiState.update {
        it.copy(apiKey = value, profileSaved = false, profileError = null)
    }
    fun updatePrompt(value: String) = _uiState.update { it.copy(prompt = value) }

    fun saveProviderProfile() {
        val state = _uiState.value
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    profileStore.save(ProviderProfile(state.provider, state.model, state.apiKey))
                }
            }.onSuccess {
                _uiState.update { it.copy(profileSaved = true, profileError = null) }
            }.onFailure { error ->
                _uiState.update { it.copy(profileSaved = false, profileError = safeError(error)) }
            }
        }
    }

    fun clearProviderProfile() {
        if (_uiState.value.agentState !in setOf(AgentState.STOPPED, AgentState.CRASHED)) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { profileStore.clear() } }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            provider = "",
                            model = "",
                            apiKey = "",
                            profileSaved = false,
                            profileError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(profileError = safeError(error)) }
                }
        }
    }

    fun importWorkspace(treeUri: Uri) {
        val state = _uiState.value
        if (state.workspaceOperationInProgress ||
            state.agentState !in setOf(AgentState.STOPPED, AgentState.CRASHED)
        ) return
        viewModelScope.launch {
            setWorkspaceBusy(true)
            runCatching {
                workspaceRepository.importWorkspace(treeUri) { progress ->
                    _uiState.update { it.copy(workspaceProgress = progress) }
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        workspace = result.workspace,
                        workspacePermissionAvailable = true,
                        workspaceError = null,
                        syncPreview = null,
                        messages = emptyList(),
                        tools = emptyList(),
                        proofResult = null,
                        sessionId = null,
                        sessionName = null,
                        sessionStatistics = SessionStatistics(),
                    )
                }
                appendDiagnostic("workspace/import", "Imported ${result.importedFiles} file(s)")
            }.onFailure { error ->
                _uiState.update { it.copy(workspaceError = safeError(error)) }
            }
            setWorkspaceBusy(false)
        }
    }

    fun reportWorkspaceAccessFailure(error: Throwable) {
        _uiState.update { it.copy(workspaceError = safeError(error)) }
    }

    fun previewWorkspaceSync() {
        val state = _uiState.value
        if (state.workspace == null || state.workspaceOperationInProgress ||
            state.agentState !in setOf(AgentState.STOPPED, AgentState.CRASHED)
        ) return
        viewModelScope.launch {
            setWorkspaceBusy(true)
            runCatching {
                workspaceRepository.previewSync { progress ->
                    _uiState.update { it.copy(workspaceProgress = progress) }
                }
            }.onSuccess { preview ->
                _uiState.update {
                    it.copy(
                        syncPreview = preview,
                        workspaceError = null,
                        workspacePermissionAvailable = true,
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(workspaceError = safeError(error), syncPreview = null) }
            }
            setWorkspaceBusy(false)
        }
    }

    fun applyWorkspaceSync() {
        val state = _uiState.value
        val preview = state.syncPreview ?: return
        if (state.workspaceOperationInProgress ||
            state.agentState !in setOf(AgentState.STOPPED, AgentState.CRASHED) ||
            preview.sync.plan.conflicts.isNotEmpty()
        ) return
        viewModelScope.launch {
            setWorkspaceBusy(true)
            runCatching {
                workspaceRepository.applySync(preview) { progress ->
                    _uiState.update { it.copy(workspaceProgress = progress) }
                }
            }.onSuccess { result ->
                val workspace = workspaceRepository.activeWorkspace()
                _uiState.update {
                    it.copy(
                        workspace = workspace,
                        syncPreview = null,
                        workspaceError = null,
                        workspacePermissionAvailable = workspace?.let(
                            workspaceRepository::hasPersistedPermission,
                        ) == true,
                    )
                }
                appendDiagnostic(
                    "workspace/sync",
                    "Applied ${result.appliedOperations} operation(s)",
                )
            }.onFailure { error ->
                _uiState.update { it.copy(workspaceError = safeError(error), syncPreview = null) }
            }
            setWorkspaceBusy(false)
        }
    }

    private fun setBusy(value: Boolean) = _uiState.update { it.copy(operationInProgress = value) }

    private fun setWorkspaceBusy(value: Boolean) = _uiState.update {
        it.copy(
            workspaceOperationInProgress = value,
            workspaceProgress = if (value) it.workspaceProgress else null,
        )
    }

    private fun loadPersistedState() {
        viewModelScope.launch {
            val profile = runCatching { withContext(Dispatchers.IO) { profileStore.load() } }
                .onFailure { error ->
                    _uiState.update { it.copy(profileError = safeError(error)) }
                }
                .getOrNull()
            val workspace = runCatching { workspaceRepository.activeWorkspace() }
                .onFailure { error ->
                    _uiState.update { it.copy(workspaceError = safeError(error)) }
                }
                .getOrNull()
            _uiState.update {
                it.copy(
                    provider = profile?.provider.orEmpty(),
                    model = profile?.model.orEmpty(),
                    apiKey = profile?.apiKey.orEmpty(),
                    profileSaved = profile != null,
                    workspace = workspace,
                    workspacePermissionAvailable = workspace?.let(
                        workspaceRepository::hasPersistedPermission,
                    ) == true,
                    recoveryNotice = if (
                        it.agentState == AgentState.STOPPED &&
                        recoveryStore.wasActive()
                    ) {
                        "The previous Agent process ended without a clean stop. Start the Agent to resume its saved Session."
                    } else {
                        null
                    },
                )
            }
        }
    }

    private fun sessionDirectory(workspace: ManagedWorkspace): File =
        File(getApplication<Application>().filesDir, "sessions/${workspace.id}")

    private fun hasSession(workspace: ManagedWorkspace): Boolean {
        val directory = sessionDirectory(workspace)
        return directory.isDirectory && directory.walkTopDown().any {
            it.isFile && it.extension == "jsonl"
        }
    }

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
