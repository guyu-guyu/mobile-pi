package dev.mobilepi.workspaces

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import dev.mobilepi.workspaces.persistence.JsonSyncBaselineStore
import dev.mobilepi.workspaces.saf.SafWorkspaceFileTree
import dev.mobilepi.workspaces.sync.LocalWorkspaceFileTree
import dev.mobilepi.workspaces.sync.WorkspaceProgressListener
import dev.mobilepi.workspaces.sync.WorkspaceStorageException
import dev.mobilepi.workspaces.sync.WorkspaceSyncEngine
import dev.mobilepi.workspaces.sync.WorkspaceSyncPreview
import dev.mobilepi.workspaces.sync.WorkspaceSyncResult
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ManagedWorkspace(
    val id: String,
    val displayName: String,
    val treeUri: Uri,
    val filesDirectory: File,
    val importedAtEpochMillis: Long,
    val lastSyncedAtEpochMillis: Long,
)

data class WorkspaceImportResult(
    val workspace: ManagedWorkspace,
    val importedFiles: Int,
)

data class ManagedWorkspaceSyncPreview(
    val workspace: ManagedWorkspace,
    val sync: WorkspaceSyncPreview,
)

class WorkspacePermissionException : SecurityException(
    "Access to the selected directory is unavailable; select the directory again",
)

class WorkspaceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val workspacesRoot = File(appContext.filesDir, WORKSPACES_DIRECTORY)
    private val activeStore = ActiveWorkspaceStore(File(workspacesRoot, ACTIVE_FILE_NAME))
    private val mutex = Mutex()

    suspend fun activeWorkspace(): ManagedWorkspace? = withContext(Dispatchers.IO) {
        activeStore.read()?.toWorkspace()
    }

    fun hasPersistedPermission(workspace: ManagedWorkspace): Boolean =
        contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == workspace.treeUri &&
                permission.isReadPermission &&
                permission.isWritePermission
        }

    suspend fun importWorkspace(
        treeUri: Uri,
        progress: WorkspaceProgressListener = WorkspaceProgressListener {},
    ): WorkspaceImportResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            requirePersistedPermission(treeUri)
            val id = UUID.randomUUID().toString()
            val workspaceDirectory = workspaceDirectory(id)
            val filesDirectory = File(workspaceDirectory, FILES_DIRECTORY)
            val external = SafWorkspaceFileTree(appContext, treeUri)
            val displayName = external.displayName().trim().take(MAX_DISPLAY_NAME_CHARS)
                .ifBlank { "Selected folder" }
            val engine = engine(workspaceDirectory, filesDirectory, external)
            try {
                val result = engine.importExternal(progress)
                val now = System.currentTimeMillis()
                val workspace = ManagedWorkspace(
                    id = id,
                    displayName = displayName,
                    treeUri = treeUri,
                    filesDirectory = filesDirectory,
                    importedAtEpochMillis = now,
                    lastSyncedAtEpochMillis = now,
                )
                activeStore.write(workspace.toRecord())
                WorkspaceImportResult(workspace, result.snapshot.files.size)
            } catch (error: Throwable) {
                workspaceDirectory.deleteRecursively()
                throw error
            }
        }
    }

    suspend fun previewSync(
        progress: WorkspaceProgressListener = WorkspaceProgressListener {},
    ): ManagedWorkspaceSyncPreview = mutex.withLock {
        withContext(Dispatchers.IO) {
            val workspace = requireActiveWorkspace()
            requirePersistedPermission(workspace.treeUri)
            requireManagedFiles(workspace)
            val external = SafWorkspaceFileTree(appContext, workspace.treeUri)
            ManagedWorkspaceSyncPreview(
                workspace,
                engine(
                    workspace.filesDirectory.parentFile
                        ?: throw WorkspaceStorageException("Workspace directory is invalid"),
                    workspace.filesDirectory,
                    external,
                ).preview(progress),
            )
        }
    }

    suspend fun applySync(
        preview: ManagedWorkspaceSyncPreview,
        progress: WorkspaceProgressListener = WorkspaceProgressListener {},
    ): WorkspaceSyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val workspace = requireActiveWorkspace()
            if (workspace.id != preview.workspace.id) {
                throw WorkspaceStorageException("The active workspace changed after the preview")
            }
            requirePersistedPermission(workspace.treeUri)
            requireManagedFiles(workspace)
            val external = SafWorkspaceFileTree(appContext, workspace.treeUri)
            val result = engine(
                workspace.filesDirectory.parentFile
                    ?: throw WorkspaceStorageException("Workspace directory is invalid"),
                workspace.filesDirectory,
                external,
            ).apply(preview.sync, progress)
            activeStore.write(
                workspace.copy(lastSyncedAtEpochMillis = System.currentTimeMillis()).toRecord(),
            )
            result
        }
    }

    private fun engine(
        workspaceDirectory: File,
        filesDirectory: File,
        external: SafWorkspaceFileTree,
    ): WorkspaceSyncEngine = WorkspaceSyncEngine(
        managed = LocalWorkspaceFileTree(filesDirectory),
        external = external,
        baselineStore = JsonSyncBaselineStore(File(workspaceDirectory, BASELINE_FILE_NAME)),
    )

    private fun requireActiveWorkspace(): ManagedWorkspace =
        activeStore.read()?.toWorkspace()
            ?: throw WorkspaceStorageException("No managed workspace is selected")

    private fun requireManagedFiles(workspace: ManagedWorkspace) {
        if (!workspace.filesDirectory.isDirectory) {
            throw WorkspaceStorageException(
                "The managed workspace copy is missing; select the source directory to import it again",
            )
        }
    }

    private fun requirePersistedPermission(treeUri: Uri) {
        val granted = contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri &&
                permission.isReadPermission &&
                permission.isWritePermission
        }
        if (!granted) throw WorkspacePermissionException()
    }

    private fun workspaceDirectory(id: String): File {
        val normalized = runCatching { UUID.fromString(id).toString() }
            .getOrElse { throw WorkspaceStorageException("Workspace ID is invalid", it) }
        if (normalized != id) throw WorkspaceStorageException("Workspace ID is not canonical")
        val directory = File(workspacesRoot, id).canonicalFile
        if (!directory.toPath().startsWith(workspacesRoot.canonicalFile.toPath())) {
            throw WorkspaceStorageException("Workspace path escapes private storage")
        }
        return directory
    }

    private fun WorkspaceRecord.toWorkspace(): ManagedWorkspace {
        if (schemaVersion != WORKSPACE_SCHEMA_VERSION) {
            throw WorkspaceStorageException("Unsupported workspace metadata version: $schemaVersion")
        }
        if (displayName.isBlank()) throw WorkspaceStorageException("Workspace name is invalid")
        val directory = workspaceDirectory(id)
        return ManagedWorkspace(
            id = id,
            displayName = displayName,
            treeUri = Uri.parse(treeUri),
            filesDirectory = File(directory, FILES_DIRECTORY),
            importedAtEpochMillis = importedAtEpochMillis,
            lastSyncedAtEpochMillis = lastSyncedAtEpochMillis,
        )
    }

    private fun ManagedWorkspace.toRecord(): WorkspaceRecord = WorkspaceRecord(
        schemaVersion = WORKSPACE_SCHEMA_VERSION,
        id = id,
        displayName = displayName,
        treeUri = treeUri.toString(),
        importedAtEpochMillis = importedAtEpochMillis,
        lastSyncedAtEpochMillis = lastSyncedAtEpochMillis,
    )

    @Serializable
    private data class WorkspaceRecord(
        val schemaVersion: Int,
        val id: String,
        val displayName: String,
        val treeUri: String,
        val importedAtEpochMillis: Long,
        val lastSyncedAtEpochMillis: Long,
    )

    private class ActiveWorkspaceStore(file: File) {
        private val atomicFile = AtomicFile(file)
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

        fun read(): WorkspaceRecord? {
            if (!atomicFile.baseFile.isFile) return null
            return runCatching {
                atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    json.decodeFromString<WorkspaceRecord>(reader.readText())
                }
            }.getOrElse { throw WorkspaceStorageException("Cannot read active workspace metadata", it) }
        }

        fun write(record: WorkspaceRecord) {
            val bytes = json.encodeToString(record).toByteArray(StandardCharsets.UTF_8)
            atomicFile.baseFile.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs()) {
                    throw WorkspaceStorageException("Cannot create workspace metadata directory")
                }
            }
            val output = atomicFile.startWrite()
            try {
                output.write(bytes)
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw WorkspaceStorageException("Cannot persist active workspace metadata", error)
            }
        }
    }

    companion object {
        private const val WORKSPACE_SCHEMA_VERSION = 1
        private const val WORKSPACES_DIRECTORY = "workspaces"
        private const val ACTIVE_FILE_NAME = "active-workspace.json"
        private const val FILES_DIRECTORY = "files"
        private const val BASELINE_FILE_NAME = "sync-manifest.json"
        private const val MAX_DISPLAY_NAME_CHARS = 200
    }
}
