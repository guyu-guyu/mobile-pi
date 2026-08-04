package dev.mobilepi.runtime.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthCheckResultTest {
    @Test
    fun `accepts pinned healthy runtime`() {
        assertTrue(
            HealthCheckResult(
                bashAvailable = true,
                architecture = "aarch64",
                nodeVersion = "v24.5.0",
                piVersion = "0.81.1",
            ).isHealthy,
        )
    }

    @Test
    fun `rejects old Node or unpinned Pi`() {
        assertFalse(
            HealthCheckResult(true, "aarch64", "v22.18.0", "0.81.1").isHealthy,
        )
        assertFalse(
            HealthCheckResult(true, "aarch64", "v24.5.0", "0.82.0").isHealthy,
        )
    }
}
