package org.randomcoder.udroid.media

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream

data class MediaAccelerationRuntime(
    val daemon: File,
    val vaDriver: File,
)

data class MediaAccelerationEndpoint(
    val hostDirectory: File,
) {
    val guestDirectory: String = GUEST_DIRECTORY
    val guestSocket: String = "$GUEST_DIRECTORY/$SOCKET_NAME"
    val guestDriverDirectory: String = GUEST_DIRECTORY

    companion object {
        const val GUEST_DIRECTORY = "/tmp/.udroid-media"
        const val SOCKET_NAME = "fake-media-accel.sock"
        const val DRIVER_NAME = "fma_drv_video.so"
    }
}

object MediaAccelerationRuntimeInstaller {
    private const val MANIFEST_ASSET = "runtime/fma-assets.manifest"
    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

    fun install(context: Context): MediaAccelerationRuntime {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis }
        checkNotNull(abi) {
            "No packaged media bridge supports ${Build.SUPPORTED_ABIS.joinToString()}"
        }
        val runtimeVersion =
            context.assets.open(MANIFEST_ASSET).bufferedReader().use { reader ->
                parseSourceRevision(reader.readText())
            }
        val directory =
            File(context.filesDir, "media/fma-$runtimeVersion-$abi").apply {
                check(mkdirs() || isDirectory) { "Could not prepare the media runtime" }
            }
        val daemon = File(directory, "fake-media-acceld")
        val driver = File(directory, MediaAccelerationEndpoint.DRIVER_NAME)
        installAsset(context, "runtime/$abi/fake-media-acceld", daemon, executable = true)
        installAsset(context, "runtime/$abi/${MediaAccelerationEndpoint.DRIVER_NAME}", driver)
        return MediaAccelerationRuntime(daemon, driver)
    }

    internal fun supportsRootfs(rootfs: File): Boolean =
        GLIBC_PATHS.any { File(rootfs, it).isFile }

    internal fun parseSourceRevision(manifest: String): String {
        val revisions =
            manifest.lineSequence()
                .filter { it.startsWith("source_revision=") }
                .map { it.substringAfter('=') }
                .toList()
        check(revisions.size == 1 && revisions.single().matches(Regex("[0-9a-f]{40}"))) {
            "Packaged media bridge has an invalid source revision"
        }
        return revisions.single()
    }

    private fun installAsset(
        context: Context,
        assetPath: String,
        destination: File,
        executable: Boolean = false,
    ) {
        if (destination.isFile && destination.canRead() &&
            (!executable || destination.canExecute())
        ) {
            return
        }
        val staging = File(destination.parentFile, "${destination.name}.staging")
        staging.delete()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(staging).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        check(staging.setReadable(true, true)) { "Could not make $assetPath readable" }
        check(staging.setWritable(true, true)) { "Could not make $assetPath writable" }
        if (executable) {
            check(staging.setExecutable(true, true)) {
                "Could not make $assetPath executable"
            }
        }
        if (destination.exists()) {
            check(destination.delete()) { "Could not replace $assetPath" }
        }
        check(staging.renameTo(destination)) { "Could not activate $assetPath" }
    }

    private val GLIBC_PATHS =
        listOf(
            "lib/aarch64-linux-gnu/libc.so.6",
            "usr/lib/aarch64-linux-gnu/libc.so.6",
            "lib/arm-linux-gnueabihf/libc.so.6",
            "usr/lib/arm-linux-gnueabihf/libc.so.6",
            "lib/x86_64-linux-gnu/libc.so.6",
            "usr/lib/x86_64-linux-gnu/libc.so.6",
            "lib64/libc.so.6",
            "usr/lib64/libc.so.6",
            "usr/lib/libc.so.6",
        )
}
