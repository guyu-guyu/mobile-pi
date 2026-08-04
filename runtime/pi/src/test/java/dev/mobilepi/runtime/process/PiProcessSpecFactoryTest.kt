package dev.mobilepi.runtime.process

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PiProcessSpecFactoryTest {
    private val paths = RuntimePaths(
        files = File("/private"),
        nativeLibraryDirectory = File("/native"),
        bin = File("/private/usr/bin"),
        proot = File("/private/usr/bin/proot"),
        loader = File("/private/usr/bin/loader"),
        rootfs = File("/private/rootfs"),
        temporary = File("/private/tmp"),
        pi = File("/private/pi"),
        workspace = File("/private/workspaces/poc/files"),
    )

    @Test
    fun `maps DeepSeek key to environment without leaking it into arguments`() {
        val secret = "sk-secret-value"
        val spec = PiProcessSpecFactory.create(
            PiAgentConfig("deepseek", "deepseek-v4-flash", secret),
            paths,
        )

        assertEquals(secret, spec.environment["DEEPSEEK_API_KEY"])
        assertFalse(spec.command.any { it.contains(secret) })
        assertEquals(paths.workspace.absolutePath, spec.workingDirectory)
        assertTrue(spec.command.containsAll(listOf("--mode", "rpc", "--no-session")))
    }

    @Test
    fun `rejects shell syntax and unknown provider`() {
        assertThrows(IllegalArgumentException::class.java) {
            PiProcessSpecFactory.create(PiAgentConfig("deepseek;echo", "model", "secret"), paths)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PiProcessSpecFactory.create(PiAgentConfig("unknown", "model", "secret"), paths)
        }
    }
}
