package dev.mobilepi.workspaces.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSyncPlannerTest {
    @Test
    fun `initial import copies external files into the managed workspace in path order`() {
        val plan = WorkspaceSyncPlanner.plan(
            baseline = WorkspaceSnapshot.EMPTY,
            managed = WorkspaceSnapshot.EMPTY,
            external = snapshot(file("z.txt", 'b'), file("a.txt", 'a')),
        )

        assertTrue(plan.canApply)
        assertEquals(listOf("a.txt", "z.txt"), plan.operations.map { it.path.value })
        plan.operations.forEach { operation ->
            operation as WorkspaceSyncOperation.Copy
            assertEquals(WorkspaceReplica.EXTERNAL, operation.source)
            assertEquals(WorkspaceReplica.MANAGED, operation.target)
            assertEquals(WorkspaceWriteKind.CREATE, operation.kind)
        }
    }

    @Test
    fun `managed additions modifications and deletions produce explicit external writes`() {
        val baseline = snapshot(
            file("delete.txt", 'a'),
            file("keep.txt", 'b'),
            file("update.txt", 'c'),
        )
        val managed = snapshot(
            file("create.txt", 'd'),
            file("keep.txt", 'b'),
            file("update.txt", 'e'),
        )

        val plan = WorkspaceSyncPlanner.plan(baseline, managed, baseline)

        assertEquals(
            listOf(
                copy("create.txt", WorkspaceReplica.MANAGED, WorkspaceReplica.EXTERNAL, WorkspaceWriteKind.CREATE),
                delete("delete.txt", WorkspaceReplica.EXTERNAL),
                copy("update.txt", WorkspaceReplica.MANAGED, WorkspaceReplica.EXTERNAL, WorkspaceWriteKind.UPDATE),
            ),
            plan.operations.map(::summary),
        )
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `external additions modifications and deletions produce managed imports`() {
        val baseline = snapshot(
            file("delete.txt", 'a'),
            file("update.txt", 'b'),
        )
        val external = snapshot(
            file("create.txt", 'c'),
            file("update.txt", 'd'),
        )

        val plan = WorkspaceSyncPlanner.plan(baseline, baseline, external)

        assertEquals(
            listOf(
                copy("create.txt", WorkspaceReplica.EXTERNAL, WorkspaceReplica.MANAGED, WorkspaceWriteKind.CREATE),
                delete("delete.txt", WorkspaceReplica.MANAGED),
                copy("update.txt", WorkspaceReplica.EXTERNAL, WorkspaceReplica.MANAGED, WorkspaceWriteKind.UPDATE),
            ),
            plan.operations.map(::summary),
        )
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `independent changes on both replicas become typed conflicts`() {
        val baseline = snapshot(
            file("both-modified.txt", 'a'),
            file("external-deleted.txt", 'b'),
            file("managed-deleted.txt", 'c'),
        )
        val managed = snapshot(
            file("both-created.txt", 'd'),
            file("both-modified.txt", 'e'),
            file("external-deleted.txt", 'f'),
        )
        val external = snapshot(
            file("both-created.txt", '0'),
            file("both-modified.txt", '1'),
            file("managed-deleted.txt", '2'),
        )

        val plan = WorkspaceSyncPlanner.plan(baseline, managed, external)

        assertFalse(plan.canApply)
        assertTrue(plan.operations.isEmpty())
        assertEquals(
            listOf(
                WorkspaceConflictKind.BOTH_CREATED,
                WorkspaceConflictKind.BOTH_MODIFIED,
                WorkspaceConflictKind.MANAGED_CHANGED_EXTERNAL_DELETED,
                WorkspaceConflictKind.MANAGED_DELETED_EXTERNAL_CHANGED,
            ),
            plan.conflicts.map { it.kind },
        )
    }

    @Test
    fun `matching independent changes converge without an operation or conflict`() {
        val baseline = snapshot(file("same.txt", 'a'))
        val changed = snapshot(file("new.txt", 'b'), file("same.txt", 'c'))

        val plan = WorkspaceSyncPlanner.plan(baseline, changed, changed)

        assertTrue(plan.canApply)
        assertTrue(plan.operations.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
        assertEquals(listOf("new.txt", "same.txt"), plan.convergedPaths.map { it.value })
    }

    @Test
    fun `matching deletions converge so the baseline can forget the path`() {
        val baseline = snapshot(file("deleted.txt", 'a'))

        val plan = WorkspaceSyncPlanner.plan(
            baseline = baseline,
            managed = WorkspaceSnapshot.EMPTY,
            external = WorkspaceSnapshot.EMPTY,
        )

        assertTrue(plan.operations.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
        assertEquals(listOf("deleted.txt"), plan.convergedPaths.map { it.value })
    }

    @Test
    fun `plans one thousand ordinary files deterministically`() {
        val externalFiles = (999 downTo 0).map { index ->
            file("folder/file-${index.toString().padStart(4, '0')}.txt", hexCharacter(index))
        }

        val plan = WorkspaceSyncPlanner.plan(
            baseline = WorkspaceSnapshot.EMPTY,
            managed = WorkspaceSnapshot.EMPTY,
            external = WorkspaceSnapshot.of(externalFiles),
        )

        assertEquals(1_000, plan.operations.size)
        assertEquals("folder/file-0000.txt", plan.operations.first().path.value)
        assertEquals("folder/file-0999.txt", plan.operations.last().path.value)
        assertTrue(plan.conflicts.isEmpty())
    }

    private fun snapshot(vararg files: WorkspaceFileSnapshot): WorkspaceSnapshot =
        WorkspaceSnapshot.of(files.asList())

    private fun file(path: String, hashCharacter: Char) = WorkspaceFileSnapshot(
        path = WorkspacePath(path),
        sizeBytes = 1,
        sha256 = hashCharacter.toString().repeat(64),
    )

    private fun hexCharacter(index: Int): Char = "0123456789abcdef"[index % 16]

    private fun summary(operation: WorkspaceSyncOperation): String = when (operation) {
        is WorkspaceSyncOperation.Copy -> copy(operation.path.value, operation.source, operation.target, operation.kind)
        is WorkspaceSyncOperation.Delete -> delete(operation.path.value, operation.target)
    }

    private fun copy(
        path: String,
        source: WorkspaceReplica,
        target: WorkspaceReplica,
        kind: WorkspaceWriteKind,
    ) = "copy:$path:$source:$target:$kind"

    private fun delete(path: String, target: WorkspaceReplica) = "delete:$path:$target"
}
