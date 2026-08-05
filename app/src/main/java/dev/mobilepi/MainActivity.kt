package dev.mobilepi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ai.assistance.operit.terminal.LocalTerminalRuntimeConfig
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.provider.filesystem.PRootBindMount
import dev.mobilepi.runtime.process.RuntimePaths
import dev.mobilepi.runtime.process.TerminalCoreRawProcessLauncher
import dev.mobilepi.ui.MobilePiApp
import dev.mobilepi.ui.theme.MobilePiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureTerminalRuntime()
        enableEdgeToEdge()
        setContent {
            MobilePiTheme {
                MobilePiApp()
            }
        }
    }

    private fun configureTerminalRuntime() {
        val paths = RuntimePaths.from(this)
        paths.prepareSharedDirectories()
        val guestPiHome = TerminalCoreRawProcessLauncher.GUEST_PI_HOME
        TerminalManager.configureLocalRuntime(
            LocalTerminalRuntimeConfig(
                bindMounts = listOf(
                    PRootBindMount(
                        paths.workspace.absolutePath,
                        TerminalCoreRawProcessLauncher.GUEST_WORKSPACE,
                    ),
                    PRootBindMount(paths.pi.absolutePath, guestPiHome),
                ),
                environment = mapOf(
                    "PI_CODING_AGENT_DIR" to "$guestPiHome/config",
                    "PI_SKIP_VERSION_CHECK" to "1",
                    "PI_TELEMETRY" to "0",
                ),
                initialWorkingDirectory = TerminalCoreRawProcessLauncher.GUEST_WORKSPACE,
                allowChroot = false,
            ),
        )
    }
}
