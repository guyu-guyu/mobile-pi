package dev.mobilepi.workspaces.saf

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import dev.mobilepi.workspaces.sync.WorkspaceFileSnapshot
import dev.mobilepi.workspaces.sync.WorkspaceFileTree
import dev.mobilepi.workspaces.sync.WorkspacePath
import dev.mobilepi.workspaces.sync.WorkspaceProgress
import dev.mobilepi.workspaces.sync.WorkspaceProgressListener
import dev.mobilepi.workspaces.sync.WorkspaceProgressStage
import dev.mobilepi.workspaces.sync.WorkspaceSnapshot
import dev.mobilepi.workspaces.sync.WorkspaceStorageException
import dev.mobilepi.workspaces.sync.hashContent
import java.io.FileNotFoundException
import java.io.InputStream

class SafWorkspaceFileTree(
    context: Context,
    val treeUri: Uri,
) : WorkspaceFileTree {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
        .getOrElse { throw WorkspaceStorageException("Invalid document tree URI", it) }
    @Volatile
    private var cachedFiles: Map<WorkspacePath, DocumentNode> = emptyMap()

    override fun scan(
        stage: WorkspaceProgressStage,
        progress: WorkspaceProgressListener,
    ): WorkspaceSnapshot {
        val files = mutableListOf<DocumentNode>()
        val allDocumentIds = mutableSetOf(rootDocumentId)
        enumerate(rootDocumentId, parentPath = null, allDocumentIds, files)
        files.sortBy { it.path }
        val snapshots = files.mapIndexed { index, node ->
            progress.onProgress(WorkspaceProgress(stage, index, files.size, node.path))
            val hashed = openDocument(node.uri, node.path).use(::hashContent)
            WorkspaceFileSnapshot(node.path, hashed.sizeBytes, hashed.sha256)
        }
        progress.onProgress(WorkspaceProgress(stage, files.size, files.size))
        cachedFiles = files.associateBy { it.path }
        return WorkspaceSnapshot.of(snapshots)
    }

    override fun open(path: WorkspacePath): InputStream {
        val node = cachedFiles[path] ?: resolve(path)
        if (node.isDirectory) throw WorkspaceStorageException("Document is a directory: $path")
        return openDocument(node.uri, path)
    }

    override fun write(path: WorkspacePath, source: InputStream) {
        val segments = path.value.split('/')
        var parentId = rootDocumentId
        segments.dropLast(1).forEach { segment ->
            val existing = findChild(parentId, segment)
            parentId = when {
                existing == null -> createDocument(parentId, DocumentsContract.Document.MIME_TYPE_DIR, segment)
                    .let(DocumentsContract::getDocumentId)
                !existing.isDirectory -> throw WorkspaceStorageException(
                    "Cannot create directory because a file has the same name: $segment",
                )
                else -> existing.documentId
            }
        }
        val name = segments.last()
        val existing = findChild(parentId, name)
        if (existing?.isDirectory == true) {
            throw WorkspaceStorageException("Cannot replace a directory with a file: $path")
        }
        val targetUri = existing?.uri
            ?: createDocument(parentId, "application/octet-stream", name)
        try {
            val output = resolver.openOutputStream(targetUri, "wt")
                ?: throw WorkspaceStorageException("Provider returned no output stream: $path")
            output.buffered().use { destination -> source.copyTo(destination) }
        } catch (error: Throwable) {
            throw WorkspaceStorageException("Cannot write selected-directory file: $path", error)
        } finally {
            cachedFiles = emptyMap()
        }
    }

    override fun delete(path: WorkspacePath) {
        val node = cachedFiles[path] ?: try {
            resolve(path)
        } catch (_: FileNotFoundException) {
            return
        }
        if (node.isDirectory) throw WorkspaceStorageException("Expected a file but found a directory: $path")
        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, node.uri) }
            .getOrElse { throw WorkspaceStorageException("Cannot delete selected-directory file: $path", it) }
        if (!deleted) throw WorkspaceStorageException("Provider refused to delete: $path")
        cachedFiles = emptyMap()
    }

    fun displayName(): String {
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
        return query(rootUri).displayName
    }

    private fun enumerate(
        parentId: String,
        parentPath: WorkspacePath?,
        allDocumentIds: MutableSet<String>,
        files: MutableList<DocumentNode>,
    ) {
        children(parentId).forEach { node ->
            val path = childPath(parentPath, node.displayName)
            val withPath = node.copy(path = path)
            if (!allDocumentIds.add(node.documentId)) {
                throw WorkspaceStorageException("Document provider returned a cycle or duplicate document ID at $path")
            }
            if (node.isDirectory) {
                enumerate(node.documentId, path, allDocumentIds, files)
            } else {
                if (node.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) {
                    throw WorkspaceStorageException("Virtual documents are not supported: $path")
                }
                files += withPath
            }
        }
    }

    private fun resolve(path: WorkspacePath): DocumentNode {
        var parentId = rootDocumentId
        var found: DocumentNode? = null
        path.value.split('/').forEach { segment ->
            found = findChild(parentId, segment)
                ?: throw FileNotFoundException("Selected-directory file is missing: $path")
            parentId = requireNotNull(found).documentId
        }
        return requireNotNull(found).copy(path = path)
    }

    private fun findChild(parentId: String, name: String): DocumentNode? {
        val matches = children(parentId).filter { it.displayName == name }
        if (matches.size > 1) {
            throw WorkspaceStorageException("Document provider returned duplicate name: $name")
        }
        return matches.singleOrNull()
    }

    private fun children(parentId: String): List<DocumentNode> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        return queryRows(childrenUri)
    }

    private fun query(uri: Uri): DocumentNode =
        queryRows(uri).singleOrNull()
            ?: throw WorkspaceStorageException("Document provider returned no metadata for $uri")

    private fun queryRows(uri: Uri): List<DocumentNode> {
        val cursor = try {
            resolver.query(uri, PROJECTION, null, null, null)
                ?: throw WorkspaceStorageException("Document provider returned no cursor for $uri")
        } catch (error: Throwable) {
            throw WorkspaceStorageException("Cannot query selected directory", error)
        }
        return cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) add(rows.toNode())
            }
        }
    }

    private fun Cursor.toNode(): DocumentNode {
        val documentId = requireString(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val displayName = requireString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeType = requireString(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val flagsIndex = getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
        return DocumentNode(
            documentId = documentId,
            displayName = displayName,
            mimeType = mimeType,
            flags = if (flagsIndex >= 0 && !isNull(flagsIndex)) getInt(flagsIndex) else 0,
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            path = PLACEHOLDER_PATH,
        )
    }

    private fun Cursor.requireString(column: String): String {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) {
            throw WorkspaceStorageException("Document provider omitted $column")
        }
        return getString(index)
    }

    private fun childPath(parent: WorkspacePath?, name: String): WorkspacePath {
        if (name.isBlank() || '/' in name || '\\' in name || '\u0000' in name || name == "." || name == "..") {
            throw WorkspaceStorageException("Document provider returned an unsafe name")
        }
        return WorkspacePath(parent?.let { "${it.value}/$name" } ?: name)
    }

    private fun createDocument(parentId: String, mimeType: String, name: String): Uri {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        return runCatching { DocumentsContract.createDocument(resolver, parentUri, mimeType, name) }
            .getOrElse { throw WorkspaceStorageException("Cannot create selected-directory document: $name", it) }
            ?: throw WorkspaceStorageException("Provider refused to create: $name")
    }

    private fun openDocument(uri: Uri, path: WorkspacePath): InputStream =
        try {
            resolver.openInputStream(uri)?.buffered()
                ?: throw WorkspaceStorageException("Provider returned no input stream: $path")
        } catch (error: Throwable) {
            throw WorkspaceStorageException("Cannot read selected-directory file: $path", error)
        }

    private data class DocumentNode(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val flags: Int,
        val uri: Uri,
        val path: WorkspacePath,
    ) {
        val isDirectory: Boolean
            get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        private val PLACEHOLDER_PATH = WorkspacePath("placeholder")
    }
}
