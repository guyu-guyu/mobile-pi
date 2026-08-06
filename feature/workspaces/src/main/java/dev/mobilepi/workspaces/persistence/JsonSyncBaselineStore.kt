package dev.mobilepi.workspaces.persistence

import android.util.AtomicFile
import dev.mobilepi.workspaces.sync.SyncBaselineStore
import dev.mobilepi.workspaces.sync.WorkspaceFileSnapshot
import dev.mobilepi.workspaces.sync.WorkspacePath
import dev.mobilepi.workspaces.sync.WorkspaceSnapshot
import dev.mobilepi.workspaces.sync.WorkspaceStorageException
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class JsonSyncBaselineStore(
    file: File,
    private val json: Json = DEFAULT_JSON,
) : SyncBaselineStore {
    private val atomicFile = AtomicFile(file)

    override fun read(): WorkspaceSnapshot {
        if (!atomicFile.baseFile.isFile) {
            throw WorkspaceStorageException("Workspace synchronization baseline is missing")
        }
        val manifest = runCatching {
            atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { reader ->
                json.decodeFromString<SyncManifest>(reader.readText())
            }
        }.getOrElse { throw WorkspaceStorageException("Cannot read workspace synchronization baseline", it) }
        if (manifest.schemaVersion != SCHEMA_VERSION) {
            throw WorkspaceStorageException(
                "Unsupported workspace synchronization baseline version: ${manifest.schemaVersion}",
            )
        }
        return runCatching {
            WorkspaceSnapshot.of(
                manifest.files.map { file ->
                    WorkspaceFileSnapshot(WorkspacePath(file.path), file.sizeBytes, file.sha256)
                },
            )
        }.getOrElse { throw WorkspaceStorageException("Workspace synchronization baseline is invalid", it) }
    }

    override fun write(snapshot: WorkspaceSnapshot) {
        val manifest = SyncManifest(
            schemaVersion = SCHEMA_VERSION,
            files = snapshot.files
                .sortedBy { it.path }
                .map { SnapshotFile(it.path.value, it.sizeBytes, it.sha256) },
        )
        val bytes = json.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8)
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
            throw WorkspaceStorageException("Cannot persist workspace synchronization baseline", error)
        }
    }

    @Serializable
    private data class SyncManifest(
        val schemaVersion: Int,
        val files: List<SnapshotFile>,
    )

    @Serializable
    private data class SnapshotFile(
        val path: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    companion object {
        private const val SCHEMA_VERSION = 1
        private val DEFAULT_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = false
        }
    }
}
