package dev.mobilepi.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.mobilepi.runtime.agent.AgentSnapshot
import dev.mobilepi.runtime.process.PiAgentConfig
import dev.mobilepi.runtime.rpc.RpcDiagnostic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.withTimeout

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiAgentServiceClient(
    context: Context,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val service = MutableStateFlow<PiAgentService.LocalBinder?>(null)
    private var bound = false

    val snapshot = service.flatMapLatest { binder ->
        binder?.snapshot ?: flowOf(AgentSnapshot())
    }.stateIn(scope, SharingStarted.Eagerly, AgentSnapshot())

    val diagnostics = service.flatMapLatest { binder ->
        binder?.diagnostics ?: flowOf<RpcDiagnostic>()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, value: IBinder?) {
            service.value = value as? PiAgentService.LocalBinder
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service.value = null
        }
    }

    init {
        bound = appContext.bindService(
            Intent(appContext, PiAgentService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    suspend fun start(config: PiAgentConfig, workspaceId: String) {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, PiAgentService::class.java).setAction(PiAgentService.ACTION_START),
        )
        binder().startAgent(config, workspaceId)
    }

    suspend fun stop() = binder().stopAgent()
    suspend fun prompt(text: String) = binder().prompt(text)
    suspend fun abort() = binder().abort()
    suspend fun newSession() = binder().newSession()
    suspend fun verifyFileTool() = binder().verifyFileTool()

    fun close() {
        if (bound) {
            appContext.unbindService(connection)
            bound = false
        }
        service.value = null
    }

    private suspend fun binder(): PiAgentService.LocalBinder =
        withTimeout(BIND_TIMEOUT_MS) { service.filterNotNull().first() }

    companion object {
        private const val BIND_TIMEOUT_MS = 10_000L
    }
}
