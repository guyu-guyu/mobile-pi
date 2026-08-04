package dev.mobilepi.runtime.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCoreRuntimeSetupTest {
    @Test
    fun `parses labeled health output without depending on surrounding noise`() {
        val result = TerminalCoreRuntimeSetup.parseHealthOutput(
            """
            shell startup detail
            __ARCH__=aarch64
            __NODE__=v24.5.0
            __PI__=0.81.1
            """.trimIndent(),
        )

        assertEquals("aarch64", result.architecture)
        assertEquals("v24.5.0", result.nodeVersion)
        assertEquals("0.81.1", result.piVersion)
        assertTrue(result.isHealthy)
    }
}
