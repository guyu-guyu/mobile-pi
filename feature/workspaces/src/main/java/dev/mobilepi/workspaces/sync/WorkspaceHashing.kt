package dev.mobilepi.workspaces.sync

import java.io.InputStream
import java.security.MessageDigest

internal data class HashedContent(val sizeBytes: Long, val sha256: String)

internal fun hashContent(input: InputStream): HashedContent {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var size = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        digest.update(buffer, 0, count)
        size += count
    }
    return HashedContent(
        sizeBytes = size,
        sha256 = digest.digest().joinToString("") { "%02x".format(it) },
    )
}
