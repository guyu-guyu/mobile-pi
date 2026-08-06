package dev.mobilepi.workspaces.sync

import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceSnapshotTest {
    @Test
    fun `rejects paths that cannot be safely resolved below a workspace`() {
        listOf("", "/root.txt", "root.txt/", "a//b", "a/../b", "a/./b", "a\\b").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { WorkspacePath(value) }
        }
    }

    @Test
    fun `rejects duplicate paths and invalid hashes`() {
        val file = file("same.txt", 'a')
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSnapshot.of(listOf(file, file.copy(sizeBytes = 2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileSnapshot(WorkspacePath("bad.txt"), 1, "not-a-hash")
        }
    }

    private fun file(path: String, hashCharacter: Char) = WorkspaceFileSnapshot(
        path = WorkspacePath(path),
        sizeBytes = 1,
        sha256 = hashCharacter.toString().repeat(64),
    )
}
