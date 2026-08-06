package dev.mobilepi.agent

import android.content.Context

class AgentRecoveryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "agent-recovery",
        Context.MODE_PRIVATE,
    )

    fun wasActive(): Boolean = preferences.getBoolean(KEY_ACTIVE, false)

    fun markActive(workspaceId: String) {
        preferences.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_WORKSPACE_ID, workspaceId)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .commit()
    }

    fun markStopped() {
        preferences.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .commit()
    }

    companion object {
        private const val KEY_ACTIVE = "active"
        private const val KEY_WORKSPACE_ID = "workspace-id"
        private const val KEY_UPDATED_AT = "updated-at"
    }
}
