package org.randomcoder.udroid.runtime

import android.content.Context
import org.randomcoder.udroid.UdroidApplication
import org.randomcoder.udroid.install.RootfsInstallationPipeline
import java.io.File

data class InstalledRootfs(
    val name: String,
    val directory: File,
    val readyAtEpochMs: Long,
)

class InstalledRootfsRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val rootfsDirectory = File(appContext.filesDir, "rootfs")
    private val preferences =
        appContext.getSharedPreferences("installed-rootfs-state", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<InstalledRootfs> = InstalledRootfsDiscovery.scan(rootfsDirectory)

    @Synchronized
    fun active(): InstalledRootfs? {
        val installed = all()
        if (installed.isEmpty()) {
            preferences.edit().remove(KEY_ACTIVE_ROOTFS).apply()
            return null
        }

        val persistedName = preferences.getString(KEY_ACTIVE_ROOTFS, null)
        installed.firstOrNull { it.name == persistedName }?.let { return it }

        val legacyPreferredName =
            (appContext as? UdroidApplication)
                ?.installState
                ?.current()
                ?.installationName
        val selected =
            installed.firstOrNull { it.name == legacyPreferredName }
                ?: installed.first()
        persistActive(selected.name)
        return selected
    }

    @Synchronized
    fun setActive(name: String): InstalledRootfs {
        val installed =
            all().firstOrNull { it.name == name }
                ?: error("Linux system $name is not installed or is not ready")
        persistActive(installed.name)
        return installed
    }

    @Synchronized
    fun setActiveIfNone(name: String): InstalledRootfs {
        active()?.let { return it }
        return setActive(name)
    }

    @Synchronized
    fun resolve(name: String? = null): File =
        if (name == null) {
            active()?.directory
                ?: error("Install a Linux image before opening the terminal")
        } else {
            setActive(name).directory
        }

    @Synchronized
    fun delete(name: String) {
        val installed =
            all().firstOrNull { it.name == name }
                ?: error("Linux system $name is not installed or is not ready")
        RootfsTreeDeleter.delete(rootfsDirectory.toPath(), installed.name)
        if (preferences.getString(KEY_ACTIVE_ROOTFS, null) == installed.name) {
            check(preferences.edit().remove(KEY_ACTIVE_ROOTFS).commit()) {
                "Failed to clear the active Linux system"
            }
        }
    }

    private fun persistActive(name: String) {
        check(preferences.edit().putString(KEY_ACTIVE_ROOTFS, name).commit()) {
            "Failed to save the active Linux system"
        }
    }

    private companion object {
        const val KEY_ACTIVE_ROOTFS = "active-rootfs"
    }
}

internal object InstalledRootfsDiscovery {
    fun scan(rootfsDirectory: File): List<InstalledRootfs> =
        rootfsDirectory
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .mapNotNull { directory ->
                val marker = File(directory, RootfsInstallationPipeline.READY_MARKER)
                marker
                    .takeIf(File::isFile)
                    ?.let {
                        InstalledRootfs(
                            name = directory.name,
                            directory = directory,
                            readyAtEpochMs = marker.lastModified(),
                        )
                    }
            }.sortedWith(
                compareByDescending<InstalledRootfs> { it.readyAtEpochMs }
                    .thenBy { it.name },
            ).toList()
}
