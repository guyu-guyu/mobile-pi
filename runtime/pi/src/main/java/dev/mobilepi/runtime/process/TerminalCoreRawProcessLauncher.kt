package dev.mobilepi.runtime.process

import android.content.Context
import com.ai.assistance.operit.terminal.TerminalManager
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ProcessStartException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class TerminalCoreRawProcessLauncher(context: Context) : RawProcessLauncher {
    private val appContext = context.applicationContext
    private val paths = RuntimePaths.from(appContext)

    override suspend fun start(spec: RawProcessSpec): RawProcess = withContext(Dispatchers.IO) {
        validateSpec(spec)
        if (!TerminalManager.getInstance(appContext).initializeEnvironment()) {
            throw ProcessStartException("TerminalCore environment initialization failed")
        }
        paths.preparePrivateDirectories()
        paths.requireInstalled()
        val sessionDirectory = File(spec.sessionDirectory).canonicalFile
        check(sessionDirectory.isDirectory || sessionDirectory.mkdirs()) {
            "Cannot create private Session directory"
        }

        val command = buildList {
            add(paths.proot.absolutePath)
            add("-v")
            add("-1")
            add("--kill-on-exit")
            add("-0")
            add("-r")
            add(paths.rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("${paths.temporary.absolutePath}:/tmp")
            add("-b")
            add("${File(spec.workingDirectory).canonicalPath}:$GUEST_WORKSPACE")
            add("-b")
            add("${paths.pi.canonicalPath}:$GUEST_PI_HOME")
            add("-b")
            add("${sessionDirectory.absolutePath}:$GUEST_SESSIONS")
            add("-w")
            add(GUEST_WORKSPACE)
            addAll(spec.command)
        }

        val process = try {
            ProcessBuilder(command)
                .directory(paths.files)
                .redirectErrorStream(false)
                .apply {
                    environment().clear()
                    environment().putAll(baseEnvironment())
                    environment().putAll(spec.environment)
                }
                .start()
        } catch (error: Throwable) {
            throw ProcessStartException("Failed to start raw PRoot process", error)
        }
        AndroidRawProcess(process)
    }

    private fun validateSpec(spec: RawProcessSpec) {
        check(spec.command.isNotEmpty()) { "Raw process command cannot be empty" }
        val requestedWorkspace = File(spec.workingDirectory).canonicalFile
        val workspacesRoot = File(paths.files, "workspaces").canonicalFile
        val workspaceId = requestedWorkspace.parentFile?.name
        check(
            requestedWorkspace.toPath().startsWith(workspacesRoot.toPath()) &&
                requestedWorkspace.name == "files" &&
                requestedWorkspace.parentFile?.parentFile == workspacesRoot &&
                workspaceId != null &&
                runCatching { UUID.fromString(workspaceId).toString() == workspaceId }.getOrDefault(false),
        ) {
            "Agent working directory must be a managed private workspace"
        }
        val requestedSessions = File(spec.sessionDirectory).canonicalFile
        check(
            requestedSessions.toPath().startsWith(paths.sessions.canonicalFile.toPath()) &&
                requestedSessions.parentFile == paths.sessions.canonicalFile &&
                requestedSessions.name == workspaceId,
        ) {
            "Agent Session directory must match the managed workspace"
        }
        check(spec.environment.keys.none { it.contains('=') || it.contains('\u0000') }) {
            "Invalid environment variable name"
        }
        check(spec.environment.values.none { it.contains('\u0000') }) {
            "Environment variable contains NUL"
        }
    }

    private fun baseEnvironment(): Map<String, String> = mapOf(
        "HOME" to "/root",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "TERM" to "dumb",
        "TMPDIR" to "/tmp",
        "PROOT_TMP_DIR" to paths.temporary.absolutePath,
        "PROOT_LOADER" to paths.loader.absolutePath,
        "LD_LIBRARY_PATH" to "",
        "PI_CODING_AGENT_DIR" to "$GUEST_PI_HOME/config",
        "PI_SKIP_VERSION_CHECK" to "1",
        "PI_TELEMETRY" to "0",
    )

    companion object {
        const val GUEST_WORKSPACE = "/workspace"
        const val GUEST_PI_HOME = "/mobile-pi/pi"
        const val GUEST_SESSIONS = "/mobile-pi/sessions"
    }
}

private class AndroidRawProcess(
    private val process: Process,
) : RawProcess {
    private val terminationRequested = AtomicBoolean(false)
    private val exitResult = CompletableDeferred<ProcessExit>()

    override val stdin = process.outputStream
    override val stdout = process.inputStream
    override val stderr = process.errorStream
    override val exit = exitResult

    init {
        Thread(
            {
                val code = runCatching { process.waitFor() }.getOrDefault(-1)
                exitResult.complete(ProcessExit(code, terminationRequested.get()))
            },
            "mobile-pi-process-waiter",
        ).apply {
            isDaemon = true
            start()
        }
    }

    override suspend fun terminate(gracePeriodMs: Long) = withContext(Dispatchers.IO) {
        terminationRequested.set(true)
        runCatching { stdin.close() }
        if (!process.isAlive) return@withContext
        process.destroy()
        val exited = withTimeoutOrNull(gracePeriodMs.coerceAtLeast(0)) {
            exit.await()
        } != null
        if (!exited && process.isAlive) {
            process.destroyForcibly()
            process.waitFor(FORCE_WAIT_MS, TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        private const val FORCE_WAIT_MS = 2_000L
    }
}
