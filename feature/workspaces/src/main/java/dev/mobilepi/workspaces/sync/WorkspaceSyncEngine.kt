package dev.mobilepi.workspaces.sync

interface SyncBaselineStore {
    fun read(): WorkspaceSnapshot
    fun write(snapshot: WorkspaceSnapshot)
}

data class WorkspaceSyncPreview(
    val baseline: WorkspaceSnapshot,
    val managed: WorkspaceSnapshot,
    val external: WorkspaceSnapshot,
    val plan: WorkspaceSyncPlan,
)

data class WorkspaceSyncResult(
    val snapshot: WorkspaceSnapshot,
    val appliedOperations: Int,
)

class WorkspaceChangedAfterPreviewException : IllegalStateException(
    "Workspace changed after the synchronization preview; review the new differences before applying",
)

class WorkspaceConflictsException(val conflicts: List<WorkspaceSyncConflict>) :
    IllegalStateException("Workspace synchronization has ${conflicts.size} unresolved conflict(s)")

class WorkspacePartialSyncException(
    val completedOperations: Int,
    cause: Throwable,
) : IllegalStateException(
    "Workspace synchronization stopped after $completedOperations operation(s): ${cause.message}",
    cause,
)

class WorkspaceSyncEngine(
    private val managed: WorkspaceFileTree,
    private val external: WorkspaceFileTree,
    private val baselineStore: SyncBaselineStore,
) {
    fun importExternal(
        progress: WorkspaceProgressListener = WorkspaceProgressListener {},
    ): WorkspaceSyncResult {
        val externalSnapshot = external.scan(WorkspaceProgressStage.SCANNING_EXTERNAL, progress)
        val files = externalSnapshot.files.sortedBy { it.path }
        files.forEachIndexed { index, file ->
            progress.onProgress(
                WorkspaceProgress(WorkspaceProgressStage.IMPORTING, index, files.size, file.path),
            )
            external.open(file.path).use { source -> managed.write(file.path, source) }
        }
        progress.onProgress(
            WorkspaceProgress(WorkspaceProgressStage.IMPORTING, files.size, files.size),
        )
        val imported = managed.scan(WorkspaceProgressStage.VERIFYING, progress)
        check(imported == externalSnapshot) { "Managed workspace does not match the selected directory after import" }
        baselineStore.write(imported)
        return WorkspaceSyncResult(imported, files.size)
    }

    fun preview(
        progress: WorkspaceProgressListener = WorkspaceProgressListener {},
    ): WorkspaceSyncPreview {
        val baseline = baselineStore.read()
        val managedSnapshot = managed.scan(WorkspaceProgressStage.SCANNING_MANAGED, progress)
        val externalSnapshot = external.scan(WorkspaceProgressStage.SCANNING_EXTERNAL, progress)
        return WorkspaceSyncPreview(
            baseline = baseline,
            managed = managedSnapshot,
            external = externalSnapshot,
            plan = WorkspaceSyncPlanner.plan(baseline, managedSnapshot, externalSnapshot),
        )
    }

    fun apply(
        preview: WorkspaceSyncPreview,
        progress: WorkspaceProgressListener = WorkspaceProgressListener {},
    ): WorkspaceSyncResult {
        if (preview.plan.conflicts.isNotEmpty()) {
            throw WorkspaceConflictsException(preview.plan.conflicts)
        }
        val currentManaged = managed.scan(WorkspaceProgressStage.SCANNING_MANAGED, progress)
        val currentExternal = external.scan(WorkspaceProgressStage.SCANNING_EXTERNAL, progress)
        if (currentManaged != preview.managed || currentExternal != preview.external) {
            throw WorkspaceChangedAfterPreviewException()
        }

        var completed = 0
        try {
            preview.plan.operations.forEachIndexed { index, operation ->
                progress.onProgress(
                    WorkspaceProgress(
                        WorkspaceProgressStage.APPLYING,
                        index,
                        preview.plan.operations.size,
                        operation.path,
                    ),
                )
                when (operation) {
                    is WorkspaceSyncOperation.Copy -> {
                        val source = tree(operation.source)
                        val target = tree(operation.target)
                        source.open(operation.path).use { input -> target.write(operation.path, input) }
                    }
                    is WorkspaceSyncOperation.Delete -> tree(operation.target).delete(operation.path)
                }
                completed++
            }
        } catch (error: Throwable) {
            throw WorkspacePartialSyncException(completed, error)
        }
        progress.onProgress(
            WorkspaceProgress(
                WorkspaceProgressStage.APPLYING,
                preview.plan.operations.size,
                preview.plan.operations.size,
            ),
        )

        val verifiedManaged = managed.scan(WorkspaceProgressStage.VERIFYING, progress)
        val verifiedExternal = external.scan(WorkspaceProgressStage.VERIFYING, progress)
        check(verifiedManaged == verifiedExternal) {
            "Managed and selected directories differ after synchronization"
        }
        baselineStore.write(verifiedManaged)
        return WorkspaceSyncResult(verifiedManaged, completed)
    }

    private fun tree(replica: WorkspaceReplica): WorkspaceFileTree = when (replica) {
        WorkspaceReplica.MANAGED -> managed
        WorkspaceReplica.EXTERNAL -> external
    }
}
