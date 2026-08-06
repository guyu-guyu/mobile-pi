package dev.mobilepi.workspaces.sync

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceSyncEngineTest {
    @Test
    fun `handles 1000 files through import diff and bidirectional writeback`() {
        val managed = MemoryTree()
        val external = MemoryTree(
            (0 until 1_000).associate { index ->
                "files/file-${index.toString().padStart(4, '0')}.txt" to "base-$index"
            },
        )
        val baseline = MemoryBaseline()
        val engine = WorkspaceSyncEngine(managed, external, baseline)

        val imported = engine.importExternal()
        repeat(1_000) { index ->
            val path = "files/file-${index.toString().padStart(4, '0')}.txt"
            if (index % 2 == 0) {
                managed.put(path, "managed-$index")
            } else {
                external.put(path, "external-$index")
            }
        }
        val preview = engine.preview()
        val synchronized = engine.apply(preview)

        assertEquals(1_000, imported.appliedOperations)
        assertEquals(1_000, preview.plan.operations.size)
        assertEquals(1_000, synchronized.appliedOperations)
        assertEquals(managed.scan(), external.scan())
        assertEquals(managed.scan(), baseline.read())
    }

    @Test
    fun `initial import copies every external file and establishes baseline`() {
        val managed = MemoryTree()
        val external = MemoryTree(
            mapOf(
                "README.md" to "hello",
                "src/main.kt" to "fun main() = Unit",
            ),
        )
        val baseline = MemoryBaseline()

        val result = WorkspaceSyncEngine(managed, external, baseline).importExternal()

        assertEquals(2, result.appliedOperations)
        assertEquals(external.scan(), managed.scan())
        assertEquals(managed.scan(), baseline.read())
    }

    @Test
    fun `apply performs confirmed changes in both directions then advances baseline`() {
        val managed = MemoryTree(mapOf("edit.txt" to "old", "delete.txt" to "old"))
        val external = MemoryTree(mapOf("edit.txt" to "old", "delete.txt" to "old"))
        val baseline = MemoryBaseline(managed.scan())
        val engine = WorkspaceSyncEngine(managed, external, baseline)

        managed.put("edit.txt", "managed edit")
        managed.remove("delete.txt")
        managed.put("created.txt", "managed create")
        external.put("phone.txt", "external create")
        val preview = engine.preview()

        val result = engine.apply(preview)

        assertEquals(4, result.appliedOperations)
        assertEquals(managed.scan(), external.scan())
        assertEquals(managed.scan(), baseline.read())
        assertArrayEquals("managed edit".toByteArray(), external.bytes("edit.txt"))
        assertArrayEquals("external create".toByteArray(), managed.bytes("phone.txt"))
    }

    @Test
    fun `apply refuses changes made after preview without touching the target`() {
        val managed = MemoryTree(mapOf("file.txt" to "base"))
        val external = MemoryTree(mapOf("file.txt" to "base"))
        val baseline = MemoryBaseline(managed.scan())
        val engine = WorkspaceSyncEngine(managed, external, baseline)
        managed.put("file.txt", "planned")
        val preview = engine.preview()
        managed.put("file.txt", "changed again")

        assertThrows(WorkspaceChangedAfterPreviewException::class.java) {
            engine.apply(preview)
        }
        assertArrayEquals("base".toByteArray(), external.bytes("file.txt"))
    }

    @Test
    fun `apply refuses unresolved conflicts`() {
        val managed = MemoryTree(mapOf("file.txt" to "base"))
        val external = MemoryTree(mapOf("file.txt" to "base"))
        val baseline = MemoryBaseline(managed.scan())
        val engine = WorkspaceSyncEngine(managed, external, baseline)
        managed.put("file.txt", "managed")
        external.put("file.txt", "external")
        val preview = engine.preview()

        val error = assertThrows(WorkspaceConflictsException::class.java) {
            engine.apply(preview)
        }

        assertEquals(1, error.conflicts.size)
    }

    @Test
    fun `partial failure reports completed count and keeps old baseline`() {
        val managed = MemoryTree(mapOf("a.txt" to "base", "b.txt" to "base"))
        val external = MemoryTree(mapOf("a.txt" to "base", "b.txt" to "base"))
        val originalBaseline = managed.scan()
        val baseline = MemoryBaseline(originalBaseline)
        val engine = WorkspaceSyncEngine(managed, external, baseline)
        managed.put("a.txt", "one")
        managed.put("b.txt", "two")
        external.failWritePath = WorkspacePath("b.txt")
        val preview = engine.preview()

        val error = assertThrows(WorkspacePartialSyncException::class.java) {
            engine.apply(preview)
        }

        assertEquals(1, error.completedOperations)
        assertEquals(originalBaseline, baseline.read())
    }

    private class MemoryBaseline(
        private var snapshot: WorkspaceSnapshot? = null,
    ) : SyncBaselineStore {
        override fun read(): WorkspaceSnapshot = checkNotNull(snapshot)
        override fun write(snapshot: WorkspaceSnapshot) {
            this.snapshot = snapshot
        }
    }

    private class MemoryTree(initial: Map<String, String> = emptyMap()) : WorkspaceFileTree {
        private val content = linkedMapOf<WorkspacePath, ByteArray>()
        var failWritePath: WorkspacePath? = null

        init {
            initial.forEach { (path, value) -> put(path, value) }
        }

        override fun scan(
            stage: WorkspaceProgressStage,
            progress: WorkspaceProgressListener,
        ): WorkspaceSnapshot = WorkspaceSnapshot.of(
            content.entries.sortedBy { it.key }.map { (path, bytes) ->
                val hash = ByteArrayInputStream(bytes).use(::hashContent)
                WorkspaceFileSnapshot(path, hash.sizeBytes, hash.sha256)
            },
        )

        fun scan(): WorkspaceSnapshot = scan(
            WorkspaceProgressStage.VERIFYING,
            WorkspaceProgressListener {},
        )

        override fun open(path: WorkspacePath): InputStream =
            ByteArrayInputStream(checkNotNull(content[path]))

        override fun write(path: WorkspacePath, source: InputStream) {
            if (path == failWritePath) error("injected write failure")
            content[path] = source.readBytes()
        }

        override fun delete(path: WorkspacePath) {
            content.remove(path)
        }

        fun put(path: String, value: String) {
            content[WorkspacePath(path)] = value.toByteArray()
        }

        fun remove(path: String) {
            content.remove(WorkspacePath(path))
        }

        fun bytes(path: String): ByteArray = checkNotNull(content[WorkspacePath(path)])
    }
}
