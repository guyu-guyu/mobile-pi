package dev.mobilepi.runtime.setup

import dev.mobilepi.runtime.model.HealthCheckResult
import dev.mobilepi.runtime.model.RuntimeLogEntry
import dev.mobilepi.runtime.model.RuntimeStatus
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface RuntimeSetup {
    val status: StateFlow<RuntimeStatus>
    val logs: SharedFlow<RuntimeLogEntry>

    suspend fun inspect(): HealthCheckResult?
    suspend fun install(): HealthCheckResult
    suspend fun clear()
}
