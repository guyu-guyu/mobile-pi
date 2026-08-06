package dev.mobilepi.workspaces.sync

@JvmInline
value class WorkspacePath(val value: String) : Comparable<WorkspacePath> {
    init {
        require(value.isNotBlank()) { "Workspace path cannot be blank" }
        require(!value.startsWith('/') && !value.endsWith('/')) {
            "Workspace path must be relative and must not end with a separator"
        }
        require('\\' !in value && '\u0000' !in value) {
            "Workspace path contains an unsupported character"
        }
        require(value.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Workspace path contains an invalid segment"
        }
    }

    override fun compareTo(other: WorkspacePath): Int = value.compareTo(other.value)

    override fun toString(): String = value
}

data class WorkspaceFileSnapshot(
    val path: WorkspacePath,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(sizeBytes >= 0) { "File size cannot be negative" }
        require(SHA256_PATTERN.matches(sha256)) { "File hash must be a lowercase SHA-256 value" }
    }

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

class WorkspaceSnapshot private constructor(
    private val filesByPath: Map<WorkspacePath, WorkspaceFileSnapshot>,
) {
    val paths: Set<WorkspacePath>
        get() = filesByPath.keys

    val files: Collection<WorkspaceFileSnapshot>
        get() = filesByPath.values

    operator fun get(path: WorkspacePath): WorkspaceFileSnapshot? = filesByPath[path]

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkspaceSnapshot && filesByPath == other.filesByPath

    override fun hashCode(): Int = filesByPath.hashCode()

    override fun toString(): String = "WorkspaceSnapshot(files=${filesByPath.size})"

    companion object {
        val EMPTY = WorkspaceSnapshot(emptyMap())

        fun of(files: Iterable<WorkspaceFileSnapshot>): WorkspaceSnapshot {
            val filesByPath = linkedMapOf<WorkspacePath, WorkspaceFileSnapshot>()
            files.forEach { file ->
                require(filesByPath.put(file.path, file) == null) {
                    "Workspace snapshot contains duplicate path: ${file.path}"
                }
            }
            return WorkspaceSnapshot(filesByPath.toMap())
        }
    }
}
