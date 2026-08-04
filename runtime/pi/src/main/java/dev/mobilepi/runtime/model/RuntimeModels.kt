package dev.mobilepi.runtime.model

import kotlinx.serialization.Serializable

enum class RuntimeInstallState {
    NOT_INSTALLED,
    EXTRACTING_ROOTFS,
    INSTALLING_NODE,
    INSTALLING_PI,
    VERIFYING,
    READY,
    FAILED,
    BROKEN,
}

@Serializable
data class RuntimeManifest(
    val schemaVersion: Int = 1,
    val rootfsVersion: String,
    val terminalCoreCommit: String,
    val nodeVersion: String,
    val piVersion: String,
    val abi: String,
    val installedAt: String,
    val lastVerifiedAt: String,
)

data class RuntimeStatus(
    val state: RuntimeInstallState,
    val stage: String? = null,
    val exitCode: Int? = null,
    val detail: String? = null,
)

data class RuntimeLogEntry(
    val sequence: Long,
    val stage: String,
    val message: String,
    val exitCode: Int? = null,
)

data class HealthCheckResult(
    val bashAvailable: Boolean,
    val architecture: String? = null,
    val nodeVersion: String? = null,
    val piVersion: String? = null,
) {
    val isHealthy: Boolean
        get() = bashAvailable &&
            architecture in SUPPORTED_ARCHITECTURES &&
            nodeVersion?.let(::isSupportedNodeVersion) == true &&
            piVersion == PINNED_PI_VERSION

    companion object {
        const val PINNED_PI_VERSION = "0.81.1"
        const val TERMINAL_CORE_COMMIT = "f85be57944b806de4d863dee8b10d80d04daa236"
        const val ROOTFS_VERSION = "ubuntu-noble-pd-v4.18.0"
        val SUPPORTED_ARCHITECTURES = setOf("aarch64", "arm64", "arm64-v8a")

        internal fun isSupportedNodeVersion(value: String): Boolean {
            val parts = value.removePrefix("v").split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
            val patch = parts.getOrNull(2)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: return false
            return major > 22 || major == 22 && (minor > 19 || minor == 19 && patch >= 0)
        }
    }
}
