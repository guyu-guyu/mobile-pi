package dev.mobilepi.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.mobilepi.MainActivity
import dev.mobilepi.R
import dev.mobilepi.runtime.agent.AgentState
import dev.mobilepi.runtime.agent.PiAgentController
import dev.mobilepi.runtime.process.PiAgentConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PiAgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = LocalBinder()
    private lateinit var controller: PiAgentController
    private lateinit var recoveryStore: AgentRecoveryStore
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        controller = PiAgentController(this)
        recoveryStore = AgentRecoveryStore(this)
        createNotificationChannel()
        scope.launch {
            controller.snapshot.collectLatest { snapshot ->
                if (foreground) {
                    notificationManager().notify(
                        NOTIFICATION_ID,
                        notification(snapshot.state),
                    )
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { binder.stopAgent() }
            ACTION_START -> enterForeground(AgentState.STARTING)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.launch { binder.stopAgent() }
    }

    override fun onDestroy() {
        controller.close()
        scope.cancel()
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        val snapshot get() = controller.snapshot
        val diagnostics get() = controller.diagnostics

        suspend fun startAgent(config: PiAgentConfig, workspaceId: String) {
            enterForeground(AgentState.STARTING)
            recoveryStore.markActive(workspaceId)
            try {
                controller.start(config)
            } catch (error: Throwable) {
                recoveryStore.markStopped()
                leaveForeground()
                throw error
            }
        }

        suspend fun stopAgent() {
            runCatching { controller.stop() }
            recoveryStore.markStopped()
            leaveForeground()
        }

        suspend fun prompt(text: String) = controller.prompt(text)
        suspend fun abort() = controller.abort()
        suspend fun newSession() = controller.newSession()
        suspend fun verifyFileTool() = controller.verifyFileTool()
    }

    private fun enterForeground(state: AgentState) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(state),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        foreground = true
    }

    private fun leaveForeground() {
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(state: AgentState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PiAgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentTitle("Mobile Pi Agent")
            .setContentText(notificationText(state))
            .setContentIntent(openIntent)
            .setOngoing(state !in setOf(AgentState.STOPPED, AgentState.CRASHED))
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun notificationText(state: AgentState): String = when (state) {
        AgentState.STARTING -> "Starting"
        AgentState.READY -> "Session ready"
        AgentState.RUNNING -> "Working in the managed workspace"
        AgentState.STOPPING -> "Stopping"
        AgentState.CRASHED -> "Agent stopped unexpectedly"
        AgentState.STOPPED -> "Stopped"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent execution",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows the active Mobile Pi Agent and provides a stop action"
            },
        )
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        const val ACTION_START = "dev.mobilepi.action.START_AGENT"
        const val ACTION_STOP = "dev.mobilepi.action.STOP_AGENT"
        private const val CHANNEL_ID = "agent-execution"
        private const val NOTIFICATION_ID = 2001
    }
}
