package dev.mobilepi.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.main.TerminalScreen
import com.ai.assistance.operit.terminal.rememberTerminalEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun LinuxTerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { TerminalManager.getInstance(context) }
    val terminalState by manager.terminalState.collectAsState()
    val env = rememberTerminalEnv(manager)
    var startError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }

    val terminalPreferences = remember(context) {
        context.getSharedPreferences(TERMINAL_PREFS, Context.MODE_PRIVATE)
    }
    DisposableEffect(terminalPreferences) {
        terminalPreferences.edit()
            .putBoolean(FIRST_LAUNCH_KEY, false)
            .apply()
        onDispose { }
    }

    LaunchedEffect(manager, retry) {
        startError = null
        try {
            ensureTerminalSession(manager)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            startError = error.message ?: "Unable to start the Ubuntu terminal"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        TerminalHeader(onBack)
        Box(modifier = Modifier.weight(1f)) {
            TerminalScreen(
                env = env,
                useLocalImeHandling = true,
                checkUpdatesOnEnter = false,
            )

            val ready = terminalState.currentSession?.isInitializing == false
            if (!ready) {
                TerminalStartupOverlay(
                    error = startError,
                    onRetry = { retry++ },
                )
            }
        }
    }
}

private suspend fun ensureTerminalSession(manager: TerminalManager) {
    // TerminalManager normally creates the default session itself. Waiting first
    // avoids racing that initialization, while still recovering a manager that
    // was constructed during runtime installation and lost its first session.
    delay(DEFAULT_SESSION_GRACE_MS)
    if (manager.terminalState.value.currentSession?.isInitializing == false) return

    if (manager.terminalState.value.sessions.isNotEmpty()) {
        val result = withTimeoutOrNull(EXISTING_SESSION_TIMEOUT_MS) {
            manager.terminalState.first { state ->
                state.currentSession?.isInitializing == false || state.sessions.isEmpty()
            }
        }
        if (result?.currentSession?.isInitializing == false) return
        if (result != null && result.sessions.isNotEmpty()) return
        if (result == null) throw IllegalStateException("Ubuntu terminal startup timed out")
    }

    manager.createNewSession(DEBUG_SESSION_TITLE)
}

@Composable
private fun TerminalHeader(onBack: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = Color(0xFF17191C),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                tint = Color(0xFF6ECF8B),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                "Ubuntu terminal",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TerminalStartupOverlay(error: String?, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6111315)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (error == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Color(0xFF6ECF8B),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(12.dp))
                Text("Starting Ubuntu...", color = Color.White)
            } else {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.size(12.dp))
                Button(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Retry")
                }
            }
        }
    }
}

private const val TERMINAL_PREFS = "terminal_prefs"
private const val FIRST_LAUNCH_KEY = "is_first_launch"
private const val DEBUG_SESSION_TITLE = "Debug"
private const val DEFAULT_SESSION_GRACE_MS = 750L
private const val EXISTING_SESSION_TIMEOUT_MS = 35_000L
