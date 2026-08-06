package dev.mobilepi.workspaces.sync

enum class WorkspaceReplica {
    MANAGED,
    EXTERNAL,
}

enum class WorkspaceWriteKind {
    CREATE,
    UPDATE,
}

sealed interface WorkspaceSyncOperation {
    val path: WorkspacePath

    data class Copy(
        override val path: WorkspacePath,
        val source: WorkspaceReplica,
        val target: WorkspaceReplica,
        val kind: WorkspaceWriteKind,
        val file: WorkspaceFileSnapshot,
    ) : WorkspaceSyncOperation {
        init {
            require(source != target) { "Copy source and target must differ" }
            require(file.path == path) { "Copied file path must match the operation path" }
        }
    }

    data class Delete(
        override val path: WorkspacePath,
        val target: WorkspaceReplica,
    ) : WorkspaceSyncOperation
}

enum class WorkspaceConflictKind {
    BOTH_CREATED,
    BOTH_MODIFIED,
    MANAGED_DELETED_EXTERNAL_CHANGED,
    MANAGED_CHANGED_EXTERNAL_DELETED,
}

data class WorkspaceSyncConflict(
    val path: WorkspacePath,
    val kind: WorkspaceConflictKind,
    val baseline: WorkspaceFileSnapshot?,
    val managed: WorkspaceFileSnapshot?,
    val external: WorkspaceFileSnapshot?,
)

data class WorkspaceSyncPlan(
    val operations: List<WorkspaceSyncOperation>,
    val conflicts: List<WorkspaceSyncConflict>,
    val convergedPaths: List<WorkspacePath>,
) {
    val canApply: Boolean
        get() = conflicts.isEmpty()
}

object WorkspaceSyncPlanner {
    fun plan(
        baseline: WorkspaceSnapshot,
        managed: WorkspaceSnapshot,
        external: WorkspaceSnapshot,
    ): WorkspaceSyncPlan {
        val operations = mutableListOf<WorkspaceSyncOperation>()
        val conflicts = mutableListOf<WorkspaceSyncConflict>()
        val convergedPaths = mutableListOf<WorkspacePath>()
        val allPaths = (baseline.paths + managed.paths + external.paths).sorted()

        allPaths.forEach { path ->
            val baselineFile = baseline[path]
            val managedFile = managed[path]
            val externalFile = external[path]
            val managedChanged = managedFile != baselineFile
            val externalChanged = externalFile != baselineFile

            when {
                !managedChanged && !externalChanged -> Unit
                managedChanged && !externalChanged -> operations += operation(
                    path = path,
                    source = WorkspaceReplica.MANAGED,
                    target = WorkspaceReplica.EXTERNAL,
                    sourceFile = managedFile,
                    targetFile = externalFile,
                )
                !managedChanged && externalChanged -> operations += operation(
                    path = path,
                    source = WorkspaceReplica.EXTERNAL,
                    target = WorkspaceReplica.MANAGED,
                    sourceFile = externalFile,
                    targetFile = managedFile,
                )
                managedFile == externalFile -> convergedPaths += path
                else -> conflicts += WorkspaceSyncConflict(
                    path = path,
                    kind = conflictKind(baselineFile, managedFile, externalFile),
                    baseline = baselineFile,
                    managed = managedFile,
                    external = externalFile,
                )
            }
        }

        return WorkspaceSyncPlan(
            operations = operations,
            conflicts = conflicts,
            convergedPaths = convergedPaths,
        )
    }

    private fun operation(
        path: WorkspacePath,
        source: WorkspaceReplica,
        target: WorkspaceReplica,
        sourceFile: WorkspaceFileSnapshot?,
        targetFile: WorkspaceFileSnapshot?,
    ): WorkspaceSyncOperation = if (sourceFile == null) {
        WorkspaceSyncOperation.Delete(path, target)
    } else {
        WorkspaceSyncOperation.Copy(
            path = path,
            source = source,
            target = target,
            kind = if (targetFile == null) WorkspaceWriteKind.CREATE else WorkspaceWriteKind.UPDATE,
            file = sourceFile,
        )
    }

    private fun conflictKind(
        baseline: WorkspaceFileSnapshot?,
        managed: WorkspaceFileSnapshot?,
        external: WorkspaceFileSnapshot?,
    ): WorkspaceConflictKind = when {
        baseline == null -> WorkspaceConflictKind.BOTH_CREATED
        managed == null -> WorkspaceConflictKind.MANAGED_DELETED_EXTERNAL_CHANGED
        external == null -> WorkspaceConflictKind.MANAGED_CHANGED_EXTERNAL_DELETED
        else -> WorkspaceConflictKind.BOTH_MODIFIED
    }
}
