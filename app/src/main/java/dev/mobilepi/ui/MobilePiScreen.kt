package dev.mobilepi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import dev.mobilepi.BuildConfig
import dev.mobilepi.runtime.agent.AgentState
import dev.mobilepi.runtime.agent.MessageRole
import dev.mobilepi.runtime.agent.ToolStatus
import dev.mobilepi.runtime.model.RuntimeInstallState
import dev.mobilepi.runtime.model.RuntimeStatus
import dev.mobilepi.workspaces.sync.WorkspaceSyncOperation

@Composable
fun MobilePiApp(viewModel: MobilePiViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val runtimeStatus by viewModel.runtimeStatus.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmSync by remember { mutableStateOf(false) }
    var showTerminal by rememberSaveable { mutableStateOf(false) }
    val workspacePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onSuccess {
                viewModel.importWorkspace(uri)
            }.onFailure(viewModel::reportWorkspaceAccessFailure)
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.startAgent()
    }

    fun startAgentWithNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startAgent()
        }
    }

    BackHandler(enabled = showTerminal) {
        showTerminal = false
    }

    if (showTerminal) {
        LinuxTerminalScreen(onBack = { showTerminal = false })
        return
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear runtime installation?") },
            text = { Text("Ubuntu, Node.js, and Pi will be removed. The private workspace is kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clearRuntime()
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmSync) {
        val operationCount = uiState.syncPreview?.sync?.plan?.operations?.size ?: 0
        AlertDialog(
            onDismissRequest = { confirmSync = false },
            title = { Text("Apply workspace changes?") },
            text = {
                Text("$operationCount confirmed operation(s) will update the managed copy or selected directory.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSync = false
                        viewModel.applyWorkspaceSync()
                    },
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSync = false }) { Text("Cancel") }
            },
        )
    }

    MobilePiScreen(
        state = uiState,
        runtimeStatus = runtimeStatus,
        onProviderChange = viewModel::updateProvider,
        onModelChange = viewModel::updateModel,
        onApiKeyChange = viewModel::updateApiKey,
        onPromptChange = viewModel::updatePrompt,
        onInstall = viewModel::installRuntime,
        onRetryHealth = viewModel::inspectRuntime,
        onClear = { confirmClear = true },
        onOpenTerminal = { showTerminal = true },
        onSelectWorkspace = { workspacePicker.launch(uiState.workspace?.treeUri) },
        onPreviewSync = viewModel::previewWorkspaceSync,
        onApplySync = { confirmSync = true },
        onSaveProfile = viewModel::saveProviderProfile,
        onClearProfile = viewModel::clearProviderProfile,
        onStartAgent = ::startAgentWithNotificationPermission,
        onStopAgent = viewModel::stopAgent,
        onNewSession = viewModel::newSession,
        onSend = viewModel::sendPrompt,
        onAbort = viewModel::abortAgent,
        onVerifyFileTool = viewModel::verifyFileTool,
    )
}

@Composable
private fun MobilePiScreen(
    state: MobilePiUiState,
    runtimeStatus: RuntimeStatus,
    onProviderChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onInstall: () -> Unit,
    onRetryHealth: () -> Unit,
    onClear: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSelectWorkspace: () -> Unit,
    onPreviewSync: () -> Unit,
    onApplySync: () -> Unit,
    onSaveProfile: () -> Unit,
    onClearProfile: () -> Unit,
    onStartAgent: () -> Unit,
    onStopAgent: () -> Unit,
    onNewSession: () -> Unit,
    onSend: () -> Unit,
    onAbort: () -> Unit,
    onVerifyFileTool: () -> Unit,
) {
    Scaffold(
        topBar = { AppHeader(runtimeStatus) },
        bottomBar = {
            PromptBar(
                value = state.prompt,
                enabled = state.agentState == AgentState.READY,
                running = state.agentState == AgentState.RUNNING,
                onValueChange = onPromptChange,
                onSend = onSend,
                onAbort = onAbort,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                RuntimeSection(
                    status = runtimeStatus,
                    busy = state.operationInProgress,
                    onInstall = onInstall,
                    onRetryHealth = onRetryHealth,
                    onClear = onClear,
                    onOpenTerminal = onOpenTerminal,
                )
                HorizontalDivider()
                WorkspaceSection(
                    state = state,
                    onSelectWorkspace = onSelectWorkspace,
                    onPreviewSync = onPreviewSync,
                    onApplySync = onApplySync,
                )
                HorizontalDivider()
                ConfigurationSection(
                    state = state,
                    onProviderChange = onProviderChange,
                    onModelChange = onModelChange,
                    onApiKeyChange = onApiKeyChange,
                    onSaveProfile = onSaveProfile,
                    onClearProfile = onClearProfile,
                    enabled = !state.agentStartInProgress &&
                        state.agentState in setOf(AgentState.STOPPED, AgentState.CRASHED),
                )
                HorizontalDivider()
                AgentSection(
                    state = state,
                    runtimeReady = runtimeStatus.state == RuntimeInstallState.READY,
                    onStart = onStartAgent,
                    onStop = onStopAgent,
                    onNewSession = onNewSession,
                    onVerifyFileTool = onVerifyFileTool,
                )
                HorizontalDivider()
                ConversationSection(state)
                if (state.diagnostics.isNotEmpty()) {
                    DiagnosticsSection(state)
                }
            }
        }
    }
}

@Composable
private fun AppHeader(status: RuntimeStatus) {
    Surface(modifier = Modifier.statusBarsPadding(), tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Mobile Pi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${BuildConfig.VERSION_NAME} managed workspace build",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            StatusPill(status.state.name.replace('_', ' '), runtimeColor(status.state))
        }
    }
}

@Composable
private fun WorkspaceSection(
    state: MobilePiUiState,
    onSelectWorkspace: () -> Unit,
    onPreviewSync: () -> Unit,
    onApplySync: () -> Unit,
) {
    val agentStopped = state.agentState in setOf(AgentState.STOPPED, AgentState.CRASHED)
    Section(title = "Workspace") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.workspace?.displayName ?: "No directory selected",
                    style = MaterialTheme.typography.titleSmall,
                )
                state.workspace?.let {
                    Text(
                        if (state.workspacePermissionAvailable) {
                            "Persisted directory access"
                        } else {
                            "Directory access unavailable"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.workspacePermissionAvailable) {
                            Color(0xFF197344)
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            OutlinedButton(
                onClick = onSelectWorkspace,
                enabled = agentStopped && !state.workspaceOperationInProgress,
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(if (state.workspace == null) "Choose" else "Change")
            }
            if (state.workspace != null) {
                Spacer(Modifier.size(8.dp))
                IconButton(
                    onClick = onPreviewSync,
                    enabled = agentStopped &&
                        state.workspacePermissionAvailable &&
                        !state.workspaceOperationInProgress,
                ) {
                    Icon(Icons.Default.Sync, contentDescription = "Review workspace changes")
                }
            }
        }

        state.workspaceProgress?.let { progress ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    buildString {
                        append(progress.stage.name.replace('_', ' ').lowercase())
                        append(": ")
                        append(progress.completed)
                        progress.total?.let { append("/$it") }
                        progress.path?.let { append("  $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        state.workspaceError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        state.syncPreview?.sync?.plan?.let { plan ->
            Spacer(Modifier.height(12.dp))
            Text(
                "${plan.operations.size} operation(s), ${plan.conflicts.size} conflict(s)",
                style = MaterialTheme.typography.labelLarge,
            )
            plan.operations.take(MAX_SYNC_PREVIEW_ROWS).forEach { operation ->
                Text(
                    operationLabel(operation),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            plan.conflicts.take(MAX_SYNC_PREVIEW_ROWS).forEach { conflict ->
                Text(
                    "${conflict.path}: ${conflict.kind.name.replace('_', ' ').lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (plan.conflicts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Resolve the listed file conflicts, then review the workspace again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (plan.operations.isNotEmpty() || plan.convergedPaths.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onApplySync) {
                    Text("Apply reviewed changes")
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Managed copy and selected directory are up to date.")
            }
        }
    }
}

private fun operationLabel(operation: WorkspaceSyncOperation): String = when (operation) {
    is WorkspaceSyncOperation.Copy ->
        "${operation.path}: ${operation.source.name.lowercase()} -> " +
            "${operation.target.name.lowercase()} (${operation.kind.name.lowercase()})"
    is WorkspaceSyncOperation.Delete ->
        "${operation.path}: delete from ${operation.target.name.lowercase()}"
}

@Composable
private fun RuntimeSection(
    status: RuntimeStatus,
    busy: Boolean,
    onInstall: () -> Unit,
    onRetryHealth: () -> Unit,
    onClear: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    Section(title = "Runtime") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(status.stage ?: "runtime", style = MaterialTheme.typography.titleSmall)
                val detail = status.detail ?: status.exitCode?.let { "Exit code $it" }
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (status.state) {
                RuntimeInstallState.NOT_INSTALLED,
                RuntimeInstallState.FAILED,
                -> Button(onClick = onInstall, enabled = !busy) {
                    Text(if (status.state == RuntimeInstallState.FAILED) "Retry" else "Install")
                }
                RuntimeInstallState.BROKEN -> OutlinedButton(onClick = onRetryHealth, enabled = !busy) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Check")
                }
                RuntimeInstallState.READY -> {
                    if (BuildConfig.DEBUG) {
                        OutlinedButton(onClick = onOpenTerminal, enabled = !busy) {
                            Icon(Icons.Default.Terminal, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Terminal")
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                    ClearRuntimeButton(onClear, !busy)
                }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClearRuntimeButton(onClick: () -> Unit, enabled: Boolean) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { Text("Clear and reinstall runtime") },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Clear runtime")
        }
    }
}

@Composable
private fun ConfigurationSection(
    state: MobilePiUiState,
    onProviderChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onClearProfile: () -> Unit,
    enabled: Boolean,
) {
    Section(title = "Model") {
        OutlinedTextField(
            value = state.provider,
            onValueChange = onProviderChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Provider") },
            singleLine = true,
            enabled = enabled,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.model,
            onValueChange = onModelChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model") },
            singleLine = true,
            enabled = enabled,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = enabled,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onSaveProfile,
                enabled = enabled &&
                    state.provider.isNotBlank() &&
                    state.model.isNotBlank() &&
                    state.apiKey.isNotBlank(),
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Save profile")
            }
            if (state.profileSaved) {
                Spacer(Modifier.size(10.dp))
                Text(
                    "Saved with Android Keystore",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF197344),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClearProfile, enabled = enabled && state.profileSaved) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete saved profile")
            }
        }
        state.profileError?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AgentSection(
    state: MobilePiUiState,
    runtimeReady: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onVerifyFileTool: () -> Unit,
) {
    val configComplete = state.provider.isNotBlank() && state.model.isNotBlank() && state.apiKey.isNotBlank()
    val canStart = runtimeReady && configComplete &&
        state.workspace != null && state.workspacePermissionAvailable &&
        !state.agentStartInProgress &&
        state.agentState in setOf(AgentState.STOPPED, AgentState.CRASHED)
    val canStop = state.agentState in setOf(
        AgentState.STARTING,
        AgentState.READY,
        AgentState.RUNNING,
        AgentState.CRASHED,
    )
    Section(title = "Agent") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(state.agentState.name, agentColor(state.agentState))
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onStart, enabled = canStart) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Start")
            }
            Spacer(Modifier.size(8.dp))
            IconButton(onClick = onStop, enabled = canStop) {
                Icon(Icons.Default.Stop, contentDescription = "Stop Agent")
            }
        }
        Spacer(Modifier.height(10.dp))
        if (state.agentState == AgentState.READY) {
            OutlinedButton(onClick = onNewSession) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("New Session")
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(
            onClick = onVerifyFileTool,
            enabled = state.agentState == AgentState.READY,
        ) {
            Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Verify file tool")
        }
        state.proofResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (result.success) Color(0xFF197344) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = if (result.success) "proof.txt matched the nonce" else "proof.txt verification failed",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        state.recoveryNotice?.let { notice ->
            Spacer(Modifier.height(8.dp))
            Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.sessionId != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                state.sessionName ?: "Session ${state.sessionId.take(8)}",
                style = MaterialTheme.typography.labelLarge,
            )
            val statistics = state.sessionStatistics
            val usage = buildList {
                statistics.totalTokens?.let { add("$it tokens") }
                statistics.costUsd?.let { add("USD ${"%.4f".format(it)}") }
                statistics.contextPercent?.let { add("${"%.1f".format(it)}% context") }
            }.joinToString("  |  ")
            if (usage.isNotBlank()) {
                Text(
                    usage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConversationSection(state: MobilePiUiState) {
    Section(title = "Conversation") {
        if (state.messages.isEmpty() && state.tools.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("No messages", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.messages.forEach { message ->
                    MessageRow(
                        role = message.role,
                        text = message.text,
                        streaming = message.streaming,
                    )
                }
                state.tools.forEach { tool ->
                    ToolRow(tool.name, tool.status, tool.output)
                }
            }
        }
    }
}

@Composable
private fun MessageRow(role: MessageRole, text: String, streaming: Boolean) {
    val label = when (role) {
        MessageRole.USER -> "You"
        MessageRole.ASSISTANT -> "Pi"
        MessageRole.ERROR -> "Error"
    }
    val color = if (role == MessageRole.ERROR) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
        if (text.isNotEmpty()) {
            if (role == MessageRole.ASSISTANT) {
                BasicMarkdown(text)
            } else {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        } else if (streaming) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ToolRow(name: String, status: ToolStatus, output: String?) {
    val (label, color) = when (status) {
        ToolStatus.RUNNING -> "Running" to Color(0xFF1769AA)
        ToolStatus.SUCCEEDED -> "Succeeded" to Color(0xFF197344)
        ToolStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(label, color = color, style = MaterialTheme.typography.labelMedium)
        }
        output?.takeIf(String::isNotBlank)?.let { value ->
            Spacer(Modifier.height(7.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp))
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun DiagnosticsSection(state: MobilePiUiState) {
    Section(title = "Diagnostics") {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.diagnostics, key = { it.sequence }) { entry ->
                Text(
                    text = "${entry.sequence} ${entry.stage}: ${entry.message}" +
                        (entry.exitCode?.let { " [$it]" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun PromptBar(
    value: String,
    enabled: Boolean,
    running: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAbort: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = { Text("Message") },
                maxLines = 4,
            )
            Spacer(Modifier.size(8.dp))
            IconButton(
                onClick = if (running) onAbort else onSend,
                enabled = running || enabled && value.isNotBlank(),
            ) {
                Icon(
                    imageVector = if (running) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (running) "Abort" else "Send",
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun runtimeColor(state: RuntimeInstallState): Color = when (state) {
    RuntimeInstallState.READY -> Color(0xFF197344)
    RuntimeInstallState.FAILED,
    RuntimeInstallState.BROKEN,
    -> MaterialTheme.colorScheme.error
    RuntimeInstallState.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> Color(0xFF1769AA)
}

@Composable
private fun agentColor(state: AgentState): Color = when (state) {
    AgentState.READY -> Color(0xFF197344)
    AgentState.RUNNING -> Color(0xFF1769AA)
    AgentState.CRASHED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private const val MAX_SYNC_PREVIEW_ROWS = 8
