package dev.mobilepi.workspaces.sync

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class LocalWorkspaceFileTree(root: File) : WorkspaceFileTree {
    private val root = root.canonicalFile

    override fun scan(
        stage: WorkspaceProgressStage,
        progress: WorkspaceProgressListener,
    ): WorkspaceSnapshot {
        ensureRoot()
        val files = root.walkTopDown()
            .onEnter { directory ->
                rejectSymbolicLink(directory)
                true
            }
            .filter { file ->
                rejectSymbolicLink(file)
                file.isFile
            }
            .toList()
            .sortedBy { relativePath(it).value }
        val snapshots = files.mapIndexed { index, file ->
            val path = relativePath(file)
            progress.onProgress(WorkspaceProgress(stage, index, files.size, path))
            val hashed = FileInputStream(file).use(::hashContent)
            WorkspaceFileSnapshot(path, hashed.sizeBytes, hashed.sha256)
        }
        progress.onProgress(WorkspaceProgress(stage, files.size, files.size))
        return WorkspaceSnapshot.of(snapshots)
    }

    override fun open(path: WorkspacePath): InputStream {
        val file = resolve(path)
        rejectSymbolicLink(file)
        if (!file.isFile) throw WorkspaceStorageException("Managed file is missing: $path")
        return FileInputStream(file)
    }

    override fun write(path: WorkspacePath, source: InputStream) {
        ensureRoot()
        val target = resolve(path)
        rejectSymbolicLink(target)
        val parent = target.parentFile
            ?: throw WorkspaceStorageException("Managed file has no parent: $path")
        ensureSafeDirectories(parent)
        val temporary = File(parent, ".mobile-pi-${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().buffered().use { output -> source.copyTo(output) }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw WorkspaceStorageException("Cannot write managed file: $path", error)
        }
    }

    override fun delete(path: WorkspacePath) {
        val target = resolve(path)
        rejectSymbolicLink(target)
        if (!target.exists()) return
        if (!target.isFile || !target.delete()) {
            throw WorkspaceStorageException("Cannot delete managed file: $path")
        }
        var directory = target.parentFile
        while (directory != null && directory != root && directory.list()?.isEmpty() == true) {
            if (!directory.delete()) break
            directory = directory.parentFile
        }
    }

    private fun ensureRoot() {
        if (!root.isDirectory && !root.mkdirs()) {
            throw WorkspaceStorageException("Cannot create managed workspace: ${root.absolutePath}")
        }
        rejectSymbolicLink(root)
    }

    private fun ensureSafeDirectories(directory: File) {
        val relative = root.toPath().relativize(directory.toPath()).toList()
        var current = root
        relative.forEach { segment ->
            current = File(current, segment.toString())
            rejectSymbolicLink(current)
            if (!current.isDirectory && !current.mkdir()) {
                throw WorkspaceStorageException("Cannot create managed directory: ${current.absolutePath}")
            }
        }
    }

    private fun resolve(path: WorkspacePath): File {
        val candidate = File(root, path.value)
        var current = root
        path.value.split('/').forEach { segment ->
            current = File(current, segment)
            rejectSymbolicLink(current)
        }
        val canonical = candidate.canonicalFile
        if (!canonical.toPath().startsWith(root.toPath())) {
            throw WorkspaceStorageException("Managed path escapes the workspace: $path")
        }
        return canonical
    }

    private fun relativePath(file: File): WorkspacePath =
        WorkspacePath(root.toPath().relativize(file.toPath()).joinToString("/") { it.toString() })

    private fun rejectSymbolicLink(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            throw WorkspaceStorageException("Symbolic links are not supported: ${file.absolutePath}")
        }
    }
}
