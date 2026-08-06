package dev.mobilepi.runtime.process

import android.content.Context
import java.io.File

data class RuntimePaths(
    val files: File,
    val nativeLibraryDirectory: File,
    val bin: File,
    val proot: File,
    val loader: File,
    val rootfs: File,
    val temporary: File,
    val pi: File,
    val workspace: File,
    val sessions: File,
) {
    companion object {
        fun from(context: Context): RuntimePaths {
            val files = context.applicationContext.filesDir
            val bin = File(files, "usr/bin")
            return RuntimePaths(
                files = files,
                nativeLibraryDirectory = File(context.applicationInfo.nativeLibraryDir),
                bin = bin,
                proot = File(bin, "proot"),
                loader = File(bin, "loader"),
                rootfs = File(files, "usr/var/lib/proot-distro/installed-rootfs/ubuntu"),
                temporary = File(files, "tmp"),
                pi = File(files, "pi"),
                workspace = File(files, "workspaces/poc/files"),
                sessions = File(files, "sessions"),
            )
        }
    }

    fun preparePrivateDirectories() {
        temporary.mkdirsOrThrow()
        prepareSharedDirectories()
        rootfs.resolve("workspace").mkdirsOrThrow()
        rootfs.resolve("mobile-pi/pi").mkdirsOrThrow()
    }

    fun prepareSharedDirectories() {
        pi.resolve("config").mkdirsOrThrow()
        workspace.mkdirsOrThrow()
        sessions.mkdirsOrThrow()
    }

    fun requireInstalled() {
        requireExecutable(proot, "PRoot")
        requireExecutable(loader, "PRoot loader")
        check(rootfs.isDirectory) { "Ubuntu rootfs is not installed" }
        check(rootfs.resolve("usr/bin/pi").exists()) { "Pi is not installed in the Ubuntu rootfs" }
    }

    private fun requireExecutable(file: File, label: String) {
        check(file.exists()) { "$label is missing" }
        check(file.canExecute()) { "$label is not executable" }
    }

    private fun File.mkdirsOrThrow() {
        check(isDirectory || mkdirs()) { "Cannot create private runtime directory: $absolutePath" }
    }
}
