package dev.mobilepi.workspaces.sync

import java.io.InputStream

enum class WorkspaceProgressStage {
    SCANNING_EXTERNAL,
    SCANNING_MANAGED,
    IMPORTING,
    APPLYING,
    VERIFYING,
}

data class WorkspaceProgress(
    val stage: WorkspaceProgressStage,
    val completed: Int,
    val total: Int?,
    val path: WorkspacePath? = null,
)

fun interface WorkspaceProgressListener {
    fun onProgress(progress: WorkspaceProgress)
}

interface WorkspaceFileTree {
    fun scan(
        stage: WorkspaceProgressStage,
        progress: WorkspaceProgressListener = WorkspaceProgressListener {},
    ): WorkspaceSnapshot

    fun open(path: WorkspacePath): InputStream

    fun write(path: WorkspacePath, source: InputStream)

    fun delete(path: WorkspacePath)
}

class WorkspaceStorageException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
