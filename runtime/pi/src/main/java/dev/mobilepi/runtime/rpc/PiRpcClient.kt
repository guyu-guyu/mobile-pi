package dev.mobilepi.runtime.rpc

import dev.mobilepi.runtime.process.RawProcess
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RpcCommandException(
    val command: String,
    message: String,
) : IllegalStateException(message)

class RpcProtocolException(message: String) : IllegalStateException(message)

class RpcProcessExitedException(exitCode: Int) :
    IllegalStateException("Pi RPC process exited with code $exitCode")

data class RpcDiagnostic(
    val sequence: Long,
    val stream: String,
    val message: String,
)

class PiRpcClient(
    private val process: RawProcess,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    sensitiveValues: Set<String> = emptySet(),
) {
    private data class PendingRequest(
        val command: String,
        val result: CompletableDeferred<PiRpcMessage.Response>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val writeMutex = Mutex()
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val sequence = AtomicLong()
    private val secrets = sensitiveValues.filter(String::isNotBlank).sortedByDescending(String::length)
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var exitJob: Job? = null

    private val _events = MutableSharedFlow<PiRpcMessage.Event>(extraBufferCapacity = 64)
    val events: SharedFlow<PiRpcMessage.Event> = _events.asSharedFlow()

    private val _diagnostics = MutableSharedFlow<RpcDiagnostic>(extraBufferCapacity = 128)
    val diagnostics: SharedFlow<RpcDiagnostic> = _diagnostics.asSharedFlow()

    fun start() {
        check(started.compareAndSet(false, true)) { "PiRpcClient is already started" }
        stdoutJob = scope.launch { readStdout() }
        stderrJob = scope.launch { readStderr() }
        exitJob = scope.launch {
            val exit = process.exit.await()
            if (!stopping.get()) {
                failPending(RpcProcessExitedException(exit.exitCode))
                diagnostic("process", "Pi exited with code ${exit.exitCode}")
            }
        }
    }

    suspend fun getState(): JsonElement? = request("get_state")

    suspend fun prompt(message: String) = request("prompt") {
        put("message", message)
    }

    suspend fun abort() = request("abort")

    suspend fun request(
        command: String,
        content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): JsonElement? {
        check(started.get() && !stopping.get()) { "PiRpcClient is not running" }
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<PiRpcMessage.Response>()
        val request = PendingRequest(command, deferred)
        check(pending.putIfAbsent(id, request) == null)
        val payload = buildJsonObject {
            put("id", id)
            put("type", command)
            content()
        }
        try {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    process.stdin.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
                    process.stdin.write(LF)
                    process.stdin.flush()
                }
            }
            val response = withTimeout(requestTimeoutMs) { deferred.await() }
            if (!response.success) {
                throw RpcCommandException(command, response.error ?: "$command failed")
            }
            return response.data
        } finally {
            pending.remove(id, request)
        }
    }

    suspend fun stop(gracePeriodMs: Long) {
        if (!stopping.compareAndSet(false, true)) return
        failPending(IllegalStateException("Pi RPC client stopped"))
        runCatching { withContext(Dispatchers.IO) { process.stdin.close() } }
        process.terminate(gracePeriodMs)
        stdoutJob?.cancel()
        stderrJob?.cancel()
        exitJob?.cancel()
        scope.cancel()
    }

    private suspend fun readStdout() {
        val decoder = StrictJsonlDecoder()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (!stopping.get()) {
                val count = withContext(Dispatchers.IO) { process.stdout.read(buffer) }
                if (count < 0) break
                if (count == 0) continue
                for (result in decoder.feed(buffer, 0, count)) {
                    when (result) {
                        is JsonlDecodeResult.Frame -> handleFrame(result.value)
                        is JsonlDecodeResult.Error -> {
                            protocolFailure("${result.code}: ${result.summary}")
                            return
                        }
                    }
                }
            }
            if (!stopping.get()) {
                decoder.finish().forEach { result ->
                    if (result is JsonlDecodeResult.Error) {
                        protocolFailure("${result.code}: ${result.summary}")
                    }
                }
            }
        } catch (error: Throwable) {
            if (!stopping.get()) protocolFailure(error.message ?: "stdout read failed")
        }
    }

    private suspend fun handleFrame(frame: JsonObject) {
        val message = try {
            PiRpcProtocol.decode(frame)
        } catch (error: Throwable) {
            protocolFailure(error.message ?: "Invalid RPC object")
            return
        }
        when (message) {
            is PiRpcMessage.Response -> {
                val id = message.id
                if (id == null) {
                    protocolFailure("Response has no request id")
                    return
                }
                val request = pending.remove(id)
                if (request == null || !request.result.complete(message)) {
                    protocolFailure("Unexpected or duplicate response id")
                }
            }
            is PiRpcMessage.Event -> _events.emit(message)
        }
    }

    private suspend fun readStderr() {
        val reader = InputStreamReader(process.stderr, StandardCharsets.UTF_8)
        val buffer = CharArray(STDERR_BUFFER_CHARS)
        try {
            while (!stopping.get()) {
                val count = withContext(Dispatchers.IO) { reader.read(buffer) }
                if (count < 0) break
                if (count == 0) continue
                diagnostic("stderr", String(buffer, 0, count))
            }
        } catch (error: Throwable) {
            if (!stopping.get()) diagnostic("stderr", error.message ?: "stderr read failed")
        }
    }

    private suspend fun protocolFailure(message: String) {
        val error = RpcProtocolException(redact(message))
        diagnostic("protocol", error.message.orEmpty())
        failPending(error)
        if (stopping.compareAndSet(false, true)) {
            process.terminate(PROTOCOL_FAILURE_GRACE_MS)
        }
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.result.completeExceptionally(error) }
        pending.clear()
    }

    private suspend fun diagnostic(stream: String, message: String) {
        val clean = redact(message)
            .replace(ANSI_PATTERN, "")
            .trim()
            .take(MAX_DIAGNOSTIC_CHARS)
        if (clean.isEmpty()) return
        _diagnostics.emit(RpcDiagnostic(sequence.incrementAndGet(), stream, clean))
    }

    private fun redact(value: String): String {
        var result = value.replace(AUTH_PATTERN, "$1[REDACTED]")
        secrets.forEach { result = result.replace(it, "[REDACTED]") }
        return result
    }

    companion object {
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
        private const val PROTOCOL_FAILURE_GRACE_MS = 1_000L
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val STDERR_BUFFER_CHARS = 2 * 1024
        private const val MAX_DIAGNOSTIC_CHARS = 4_000
        private val LF = byteArrayOf(0x0A)
        private val ANSI_PATTERN = Regex("\\u001B\\[[;\\d?]*[ -/]*[@-~]")
        private val AUTH_PATTERN = Regex(
            "(?i)(authorization\\s*[:=]\\s*|api[_-]?key\\s*[:=]\\s*|bearer\\s+)[^\\s]+",
        )
    }
}
