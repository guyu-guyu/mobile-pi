package dev.mobilepi.runtime.agent

import android.content.Context
import dev.mobilepi.runtime.process.PiAgentConfig
import dev.mobilepi.runtime.process.PiProcessSpecFactory
import dev.mobilepi.runtime.process.RawProcess
import dev.mobilepi.runtime.process.RawProcessLauncher
import dev.mobilepi.runtime.process.RuntimePaths
import dev.mobilepi.runtime.process.TerminalCoreRawProcessLauncher
import dev.mobilepi.runtime.rpc.PiRpcClient
import dev.mobilepi.runtime.rpc.PiRpcMessage
import dev.mobilepi.runtime.rpc.RpcDiagnostic
import java.nio.charset.StandardCharsets
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class PiAgentController(
    context: Context,
    private val launcher: RawProcessLauncher = TerminalCoreRawProcessLauncher(context),
) {
    private val appContext = context.applicationContext
    private val paths = RuntimePaths.from(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private val messageIds = AtomicLong()
    private val instanceIds = AtomicLong()

    private val _snapshot = MutableStateFlow(AgentSnapshot())
    val snapshot: StateFlow<AgentSnapshot> = _snapshot.asStateFlow()

    private val _diagnostics = MutableSharedFlow<RpcDiagnostic>(extraBufferCapacity = 128)
    val diagnostics: SharedFlow<RpcDiagnostic> = _diagnostics.asSharedFlow()

    private var process: RawProcess? = null
    private var client: PiRpcClient? = null
    private var eventJob: Job? = null
    private var diagnosticJob: Job? = null
    private var exitJob: Job? = null
    private var settled = CompletableDeferred<Unit>()
    private var workspaceDirectory: File? = null

    suspend fun start(config: PiAgentConfig) = lifecycleMutex.withLock {
        check(_snapshot.value.state in setOf(AgentState.STOPPED, AgentState.CRASHED)) {
            "Agent is already active"
        }
        if (_snapshot.value.state == AgentState.CRASHED) {
            cleanupCurrentProcess()
        }
        val instanceId = instanceIds.incrementAndGet()
        _snapshot.update { it.copy(state = AgentState.STARTING, error = null, tools = emptyList()) }
        try {
            paths.preparePrivateDirectories()
            val spec = PiProcessSpecFactory.create(config, paths)
            val rawProcess = launcher.start(spec)
            val rpcClient = PiRpcClient(rawProcess, sensitiveValues = setOf(config.apiKey))
            process = rawProcess
            client = rpcClient
            attach(rpcClient, rawProcess, instanceId)
            rpcClient.start()
            val restored = PiSessionParser.restore(
                state = rpcClient.getState(),
                messages = rpcClient.getMessages(),
                statistics = runCatching { rpcClient.getSessionStats() }.getOrNull(),
            )
            if (instanceIds.get() == instanceId && _snapshot.value.state == AgentState.STARTING) {
                workspaceDirectory = File(config.workspaceDirectory)
                _snapshot.update {
                    it.copy(
                        state = AgentState.READY,
                        messages = restored.messages.map { (role, text) ->
                            AgentMessage(nextMessageId(), role, text)
                        },
                        sessionId = restored.sessionId,
                        sessionName = restored.sessionName,
                        sessionStatistics = restored.statistics,
                    )
                }
            }
        } catch (error: Throwable) {
            cleanupFailedStart()
            _snapshot.update {
                AgentEventReducer.addError(
                    it.copy(state = AgentState.CRASHED),
                    safeMessage(error),
                    ::nextMessageId,
                )
            }
            throw error
        }
    }

    suspend fun prompt(text: String) {
        val prompt = text.trim()
        require(prompt.isNotEmpty()) { "Prompt cannot be empty" }
        check(_snapshot.value.state == AgentState.READY) { "Agent is not ready" }
        settled = CompletableDeferred()
        _snapshot.update { snapshot ->
            snapshot.copy(
                error = null,
                proofResult = null,
                messages = snapshot.messages + listOf(
                    AgentMessage(nextMessageId(), MessageRole.USER, prompt),
                    AgentMessage(nextMessageId(), MessageRole.ASSISTANT, "", streaming = true),
                ),
            )
        }
        try {
            requireClient().prompt(prompt)
        } catch (error: Throwable) {
            settled.complete(Unit)
            _snapshot.update { snapshot ->
                AgentEventReducer.addError(
                    snapshot.copy(
                        state = AgentState.READY,
                        messages = snapshot.messages.map { message ->
                            if (message.role == MessageRole.ASSISTANT && message.streaming) {
                                message.copy(streaming = false)
                            } else message
                        },
                    ),
                    safeMessage(error),
                    ::nextMessageId,
                )
            }
            throw error
        }
    }

    suspend fun abort() {
        if (_snapshot.value.state != AgentState.RUNNING) return
        runCatching { requireClient().abort() }
            .onFailure { error -> addError(safeMessage(error)) }
        if (withTimeoutOrNull(ABORT_SETTLE_TIMEOUT_MS) { settled.await() } == null) {
            addError("Abort timed out; the Agent process was stopped")
            stop()
        }
    }

    suspend fun newSession() {
        check(_snapshot.value.state == AgentState.READY) { "Agent is not ready" }
        val rpcClient = requireClient()
        rpcClient.newSession()
        val restored = PiSessionParser.restore(
            state = rpcClient.getState(),
            messages = rpcClient.getMessages(),
            statistics = runCatching { rpcClient.getSessionStats() }.getOrNull(),
        )
        _snapshot.update {
            it.copy(
                messages = emptyList(),
                tools = emptyList(),
                error = null,
                proofResult = null,
                sessionId = restored.sessionId,
                sessionName = restored.sessionName,
                sessionStatistics = restored.statistics,
            )
        }
    }

    suspend fun stop() = lifecycleMutex.withLock {
        val rpcClient = client
        if (rpcClient == null) {
            _snapshot.update { it.copy(state = AgentState.STOPPED) }
            return@withLock
        }
        val wasRunning = _snapshot.value.state == AgentState.RUNNING
        _snapshot.update { it.copy(state = AgentState.STOPPING) }
        if (wasRunning) {
            withTimeoutOrNull(STOP_ABORT_TIMEOUT_MS) { runCatching { rpcClient.abort() } }
        }
        try {
            rpcClient.stop(PROCESS_STOP_GRACE_MS)
        } finally {
            runCatching { process?.terminate(PROCESS_STOP_GRACE_MS) }
            detach()
            process = null
            client = null
            settled.complete(Unit)
            _snapshot.update { it.copy(state = AgentState.STOPPED, tools = emptyList()) }
        }
    }

    suspend fun verifyFileTool(): ProofResult {
        check(_snapshot.value.state == AgentState.READY) { "Agent is not ready" }
        val nonce = UUID.randomUUID().toString()
        val proofFile = checkNotNull(workspaceDirectory) {
            "Agent has no managed workspace"
        }.resolve(PROOF_FILE_NAME)
        withContext(Dispatchers.IO) { proofFile.delete() }
        prompt(
            "Use the write tool to create $PROOF_FILE_NAME in the current workspace. " +
                "The complete file content must be exactly this nonce, with no newline or other text: $nonce",
        )
        withTimeout(PROOF_TIMEOUT_MS) { settled.await() }
        val actual = withContext(Dispatchers.IO) {
            if (proofFile.isFile) proofFile.readText(StandardCharsets.UTF_8) else null
        }
        val result = ProofResult(nonce, actual, actual == nonce)
        _snapshot.update { it.copy(proofResult = result) }
        return result
    }

    fun close() {
        scope.launch {
            try {
                runCatching { stop() }
            } finally {
                scope.cancel()
            }
        }
    }

    private fun attach(rpcClient: PiRpcClient, rawProcess: RawProcess, instanceId: Long) {
        eventJob = scope.launch {
            rpcClient.events.collect { event ->
                if (instanceIds.get() != instanceId) return@collect
                _snapshot.update { AgentEventReducer.reduce(it, event, ::nextMessageId) }
                if (event is PiRpcMessage.Event.AgentSettled) {
                    settled.complete(Unit)
                    scope.launch { refreshStatistics(rpcClient, instanceId) }
                }
            }
        }
        diagnosticJob = scope.launch {
            rpcClient.diagnostics.collect { _diagnostics.emit(it) }
        }
        exitJob = scope.launch {
            val exit = rawProcess.exit.await()
            if (instanceIds.get() != instanceId || _snapshot.value.state == AgentState.STOPPED) return@launch
            if (_snapshot.value.state == AgentState.STOPPING || exit.expected) {
                _snapshot.update { it.copy(state = AgentState.STOPPED) }
            } else {
                _snapshot.update { snapshot ->
                    AgentEventReducer.addError(
                        snapshot.copy(state = AgentState.CRASHED),
                        "Pi exited unexpectedly with code ${exit.exitCode}",
                        ::nextMessageId,
                    )
                }
            }
            settled.complete(Unit)
        }
    }

    private suspend fun cleanupFailedStart() {
        cleanupCurrentProcess()
    }

    private suspend fun cleanupCurrentProcess() {
        val currentClient = client
        if (currentClient != null) {
            runCatching { currentClient.stop(PROCESS_STOP_GRACE_MS) }
        } else {
            runCatching { process?.terminate(PROCESS_STOP_GRACE_MS) }
        }
        detach()
        client = null
        process = null
    }

    private fun detach() {
        eventJob?.cancel()
        diagnosticJob?.cancel()
        exitJob?.cancel()
        eventJob = null
        diagnosticJob = null
        exitJob = null
    }

    private fun requireClient(): PiRpcClient = checkNotNull(client) { "Agent process is not running" }

    private suspend fun refreshStatistics(rpcClient: PiRpcClient, instanceId: Long) {
        val statistics = runCatching { rpcClient.getSessionStats() }.getOrNull() ?: return
        if (instanceIds.get() != instanceId) return
        _snapshot.update {
            it.copy(sessionStatistics = PiSessionParser.parseStatistics(statistics))
        }
    }

    private fun addError(message: String) {
        _snapshot.update { AgentEventReducer.addError(it, message, ::nextMessageId) }
    }

    private fun nextMessageId(): Long = messageIds.incrementAndGet()

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error::class.java.simpleName).take(MAX_ERROR_CHARS)

    companion object {
        private const val PROOF_FILE_NAME = "proof.txt"
        private const val MAX_ERROR_CHARS = 1_000
        private const val ABORT_SETTLE_TIMEOUT_MS = 10_000L
        private const val STOP_ABORT_TIMEOUT_MS = 2_000L
        private const val PROCESS_STOP_GRACE_MS = 3_000L
        private const val PROOF_TIMEOUT_MS = 180_000L
    }
}
