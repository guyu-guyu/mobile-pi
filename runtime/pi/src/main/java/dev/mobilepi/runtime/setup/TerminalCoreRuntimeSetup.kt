package dev.mobilepi.runtime.setup

import android.content.Context
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.utils.CacheManager
import dev.mobilepi.runtime.model.HealthCheckResult
import dev.mobilepi.runtime.model.RuntimeInstallState
import dev.mobilepi.runtime.model.RuntimeLogEntry
import dev.mobilepi.runtime.model.RuntimeManifest
import dev.mobilepi.runtime.model.RuntimeStatus
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RuntimeInstallException(
    val stage: String,
    val processExitCode: Int?,
    message: String,
) : IllegalStateException(message)

class TerminalCoreRuntimeSetup(context: Context) : RuntimeSetup {
    private val appContext = context.applicationContext
    private val manifestFile = File(appContext.filesDir, "runtime/manifest.json")
    private val installMutex = Mutex()
    private val sequence = AtomicLong()
    private val json = Json { prettyPrint = true }

    private val _status = MutableStateFlow(initialStatus())
    override val status = _status.asStateFlow()

    private val _logs = MutableSharedFlow<RuntimeLogEntry>(extraBufferCapacity = 64)
    override val logs = _logs.asSharedFlow()

    override suspend fun inspect(): HealthCheckResult? = installMutex.withLock {
        if (!manifestFile.isFile) {
            update(RuntimeInstallState.NOT_INSTALLED, "runtime")
            return@withLock null
        }
        update(RuntimeInstallState.VERIFYING, "health")
        runCatching { healthCheck(manager()) }
            .onSuccess { result ->
                if (result.isHealthy) {
                    writeManifest(result, preserveInstallTime = true)
                    update(RuntimeInstallState.READY, "health")
                } else {
                    update(RuntimeInstallState.BROKEN, "health", detail = healthSummary(result))
                }
            }
            .onFailure { error ->
                update(RuntimeInstallState.BROKEN, "health", detail = safeMessage(error))
            }
            .getOrNull()
    }

    override suspend fun install(): HealthCheckResult = installMutex.withLock {
        try {
            update(RuntimeInstallState.EXTRACTING_ROOTFS, "rootfs")
            log("rootfs", "Preparing TerminalCore assets")
            val manager = manager()
            if (!manager.initializeEnvironment()) {
                fail("rootfs", null, "TerminalCore environment initialization failed")
            }

            // Opening the hidden Ubuntu shell runs TerminalCore's idempotent rootfs installer.
            runStep("rootfs", "true", ROOTFS_TIMEOUT_MS)

            update(RuntimeInstallState.INSTALLING_NODE, "node")
            runStep("node", NODE_INSTALL_COMMAND, PACKAGE_TIMEOUT_MS)

            update(RuntimeInstallState.INSTALLING_PI, "pi")
            runStep("pi", PI_INSTALL_COMMAND, PACKAGE_TIMEOUT_MS)

            update(RuntimeInstallState.VERIFYING, "health")
            val result = healthCheck(manager)
            if (!result.isHealthy) {
                fail("health", null, healthSummary(result))
            }
            writeManifest(result, preserveInstallTime = false)
            update(RuntimeInstallState.READY, "health")
            log("health", healthSummary(result))
            result
        } catch (error: Throwable) {
            if (error is RuntimeInstallException) {
                update(
                    RuntimeInstallState.FAILED,
                    error.stage,
                    error.processExitCode,
                    safeMessage(error),
                )
            } else {
                update(RuntimeInstallState.FAILED, "runtime", detail = safeMessage(error))
            }
            throw error
        }
    }

    override suspend fun clear() = installMutex.withLock {
        update(RuntimeInstallState.NOT_INSTALLED, "clear")
        val manager = manager()
        CacheManager(appContext).clearCache(manager)
        withContext(Dispatchers.IO) {
            manifestFile.delete()
            File(appContext.filesDir, "pi").deleteRecursively()
        }
        log("clear", "Runtime installation removed")
    }

    private suspend fun healthCheck(manager: TerminalManager): HealthCheckResult {
        val result = manager.executeHiddenCommand(
            command = HEALTH_COMMAND,
            executorKey = EXECUTOR_KEY,
            timeoutMs = HEALTH_TIMEOUT_MS,
        )
        ensureSuccess("health", result)
        return parseHealthOutput(result.output)
    }

    private suspend fun runStep(stage: String, command: String, timeoutMs: Long) {
        log(stage, "Started")
        val result = manager().executeHiddenCommand(
            command = command,
            executorKey = EXECUTOR_KEY,
            timeoutMs = timeoutMs,
        )
        ensureSuccess(stage, result)
        log(stage, "Completed", result.exitCode)
    }

    private fun ensureSuccess(stage: String, result: HiddenExecResult) {
        if (result.isOk && result.exitCode == 0) return
        val detail = result.error.takeIf(String::isNotBlank)
            ?: result.output.takeLast(MAX_LOG_CHARS).takeIf(String::isNotBlank)
            ?: "Command failed with ${result.state}"
        log(stage, detail, result.exitCode)
        fail(stage, result.exitCode, detail)
    }

    private fun manager(): TerminalManager = TerminalManager.getInstance(appContext)

    private fun writeManifest(result: HealthCheckResult, preserveInstallTime: Boolean) {
        val now = Instant.now().toString()
        val installedAt = if (preserveInstallTime) {
            readManifest()?.installedAt ?: now
        } else {
            now
        }
        val manifest = RuntimeManifest(
            rootfsVersion = HealthCheckResult.ROOTFS_VERSION,
            terminalCoreCommit = HealthCheckResult.TERMINAL_CORE_COMMIT,
            nodeVersion = requireNotNull(result.nodeVersion),
            piVersion = requireNotNull(result.piVersion),
            abi = "arm64-v8a",
            installedAt = installedAt,
            lastVerifiedAt = now,
        )
        manifestFile.parentFile?.mkdirs()
        manifestFile.writeText(json.encodeToString(manifest))
    }

    private fun readManifest(): RuntimeManifest? = runCatching {
        json.decodeFromString<RuntimeManifest>(manifestFile.readText())
    }.getOrNull()

    private fun initialStatus(): RuntimeStatus = if (manifestFile.isFile) {
        RuntimeStatus(RuntimeInstallState.VERIFYING, "health")
    } else {
        RuntimeStatus(RuntimeInstallState.NOT_INSTALLED, "runtime")
    }

    private fun update(
        state: RuntimeInstallState,
        stage: String,
        exitCode: Int? = null,
        detail: String? = null,
    ) {
        _status.value = RuntimeStatus(state, stage, exitCode, detail?.let(::redact))
    }

    private fun log(stage: String, message: String, exitCode: Int? = null) {
        _logs.tryEmit(
            RuntimeLogEntry(
                sequence = sequence.incrementAndGet(),
                stage = stage,
                message = redact(message).takeLast(MAX_LOG_CHARS),
                exitCode = exitCode,
            ),
        )
    }

    private fun fail(stage: String, exitCode: Int?, detail: String): Nothing {
        throw RuntimeInstallException(stage, exitCode, redact(detail))
    }

    private fun safeMessage(error: Throwable): String =
        redact(error.message ?: error::class.java.simpleName).take(MAX_LOG_CHARS)

    private fun redact(value: String): String =
        value.replace(AUTH_PATTERN, "$1[REDACTED]")

    companion object {
        private const val EXECUTOR_KEY = "mobile-pi-runtime"
        private const val MAX_LOG_CHARS = 2_000
        private const val HEALTH_TIMEOUT_MS = 60_000L
        private const val ROOTFS_TIMEOUT_MS = 10 * 60_000L
        private const val PACKAGE_TIMEOUT_MS = 15 * 60_000L

        private const val NODE_INSTALL_COMMAND =
            "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get update && apt-get install -y ca-certificates curl git && " +
                "curl -fsSL https://deb.nodesource.com/setup_24.x | bash - && " +
                "apt-get install -y nodejs"

        private const val PI_INSTALL_COMMAND =
            "mkdir -p /root/.mobile-pi && " +
                "npm install -g @earendil-works/pi-coding-agent@0.81.1"

        private const val HEALTH_COMMAND =
            "printf '__ARCH__='; uname -m; " +
                "printf '__NODE__='; node --version; " +
                "printf '__PI__='; pi --version"

        private val AUTH_PATTERN = Regex(
            "(?i)(authorization\\s*[:=]\\s*|api[_-]?key\\s*[:=]\\s*|bearer\\s+)[^\\s]+",
        )

        internal fun parseHealthOutput(output: String): HealthCheckResult {
            val values = output.lineSequence()
                .map(String::trim)
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (!line.startsWith("__") || separator < 0) null
                    else line.substring(0, separator) to line.substring(separator + 1).trim()
                }
                .toMap()
            return HealthCheckResult(
                bashAvailable = true,
                architecture = values["__ARCH__"],
                nodeVersion = values["__NODE__"],
                piVersion = values["__PI__"]?.removePrefix("v"),
            )
        }

        internal fun healthSummary(result: HealthCheckResult): String =
            "arch=${result.architecture ?: "missing"}, " +
                "node=${result.nodeVersion ?: "missing"}, " +
                "pi=${result.piVersion ?: "missing"}"
    }
}
