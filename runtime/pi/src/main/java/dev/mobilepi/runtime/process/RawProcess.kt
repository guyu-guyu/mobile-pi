package dev.mobilepi.runtime.process

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Deferred

data class RawProcessSpec(
    val command: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String,
)

data class ProcessExit(
    val exitCode: Int,
    val expected: Boolean,
)

interface RawProcessLauncher {
    suspend fun start(spec: RawProcessSpec): RawProcess
}

interface RawProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    val exit: Deferred<ProcessExit>

    suspend fun terminate(gracePeriodMs: Long)
}
