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
        sessions = File("/private/sessions"),
    )

    @Test
    fun `maps DeepSeek key to environment without leaking it into arguments`() {
        val secret = "sk-secret-value"
        val spec = PiProcessSpecFactory.create(
            PiAgentConfig(
                "deepseek",
                "deepseek-v4-flash",
                secret,
                "/private/workspaces/00000000-0000-0000-0000-000000000000/files",
                "/private/sessions/00000000-0000-0000-0000-000000000000",
                resumeExistingSession = true,
            ),
            paths,
        )

        assertEquals(secret, spec.environment["DEEPSEEK_API_KEY"])
        assertFalse(spec.command.any { it.contains(secret) })
        assertEquals(
            File("/private/workspaces/00000000-0000-0000-0000-000000000000/files").absolutePath,
            spec.workingDirectory,
        )
        assertEquals(
            File("/private/sessions/00000000-0000-0000-0000-000000000000").absolutePath,
            spec.sessionDirectory,
        )
        assertTrue(spec.command.containsAll(listOf("--mode", "rpc", "--session-dir", "--continue")))
        assertFalse(spec.command.contains("--no-session"))
    }

    @Test
    fun `rejects shell syntax and unknown provider`() {
        assertThrows(IllegalArgumentException::class.java) {
            PiProcessSpecFactory.create(config(provider = "deepseek;echo"), paths)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PiProcessSpecFactory.create(config(provider = "unknown"), paths)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PiProcessSpecFactory.create(
                config(provider = "deepseek").copy(
                    sessionDirectory = "/private/sessions/11111111-1111-1111-1111-111111111111",
                ),
                paths,
            )
        }
    }

    private fun config(provider: String) = PiAgentConfig(
        provider = provider,
        model = "model",
        apiKey = "secret",
        workspaceDirectory = "/private/workspaces/00000000-0000-0000-0000-000000000000/files",
        sessionDirectory = "/private/sessions/00000000-0000-0000-0000-000000000000",
        resumeExistingSession = false,
    )
}
