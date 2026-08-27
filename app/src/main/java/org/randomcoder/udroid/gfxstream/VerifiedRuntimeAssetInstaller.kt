package org.randomcoder.udroid.gfxstream

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

internal data class VerifiedRuntimeAssetBundle(
    val name: String,
    val assetDirectory: String,
    val destinationPrefix: String,
    val version: String,
    val entries: List<String>,
    val executables: Set<String> = emptySet(),
    val supportedAbis: Set<String> = setOf("arm64-v8a"),
)

/** Atomically installs an immutable, digest-verified runtime from signed APK assets. */
internal object VerifiedRuntimeAssetInstaller {
    private const val MANIFEST_ENTRY = "MANIFEST.properties"
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun install(
        context: Context,
        bundle: VerifiedRuntimeAssetBundle,
    ): File {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in bundle.supportedAbis }
        checkNotNull(abi) {
            "${bundle.name} does not support ${Build.SUPPORTED_ABIS.joinToString()}"
        }
        val assetRoot = "runtime/$abi/${bundle.assetDirectory}"
        val packagedManifest = loadManifest(context, "$assetRoot/$MANIFEST_ENTRY")
        validateManifest(packagedManifest, bundle, abi)

        val runtimeParent = File(context.filesDir, "runtime").apply { mkdirs() }
        val destination = File(runtimeParent, "${bundle.destinationPrefix}-${bundle.version}-$abi")
        if (!isComplete(destination, bundle, abi, packagedManifest)) {
            val staging =
                File(runtimeParent, ".${bundle.destinationPrefix}-${UUID.randomUUID()}.staging")
                    .apply {
                        check(mkdirs()) { "Could not prepare ${bundle.name} staging" }
                    }
            try {
                bundle.entries.forEach { entry ->
                    val output = safeOutput(staging, entry, bundle.name)
                    val parent = checkNotNull(output.parentFile)
                    check(parent.mkdirs() || parent.isDirectory) {
                        "Could not create the parent directory for $entry"
                    }
                    context.assets.open("$assetRoot/$entry").use { input ->
                        FileOutputStream(output).use { stream ->
                            input.copyTo(stream)
                            stream.fd.sync()
                        }
                    }
                    val expectedDigest = checkNotNull(packagedManifest.getProperty("$entry.sha256"))
                    check(output.sha256() == expectedDigest) {
                        "Packaged ${bundle.name} failed integrity verification: $entry"
                    }
                    check(output.setReadable(true, true)) { "Could not make $entry readable" }
                    if (entry in bundle.executables) {
                        check(output.setExecutable(true, true)) {
                            "Could not make $entry executable"
                        }
                    }
                }
                File(staging, MANIFEST_ENTRY).writeText(
                    context.assets.open("$assetRoot/$MANIFEST_ENTRY").bufferedReader().use {
                        it.readText()
                    },
                )
                check(isComplete(staging, bundle, abi, packagedManifest)) {
                    "Packaged ${bundle.name} is incomplete"
                }
                if (destination.exists()) {
                    check(destination.deleteRecursively()) {
                        "Could not replace the previous ${bundle.name}"
                    }
                }
                check(staging.renameTo(destination)) {
                    "Could not atomically activate ${bundle.name}"
                }
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }
        return destination
    }

    internal fun validateManifest(
        manifest: Properties,
        bundle: VerifiedRuntimeAssetBundle,
        abi: String,
    ) {
        check(manifest.getProperty("format") == "1") {
            "Unsupported ${bundle.name} manifest"
        }
        check(manifest.getProperty("runtime") == bundle.version) {
            "Mismatched ${bundle.name} runtime"
        }
        check(manifest.getProperty("abi") == abi) { "Mismatched ${bundle.name} ABI" }
        bundle.entries.forEach { entry ->
            val digest = manifest.getProperty("$entry.sha256")
            check(digest?.matches(digestPattern) == true) {
                "Missing ${bundle.name} digest for $entry"
            }
        }
    }

    private fun isComplete(
        directory: File,
        bundle: VerifiedRuntimeAssetBundle,
        abi: String,
        packagedManifest: Properties,
    ): Boolean {
        if (!directory.isDirectory) return false
        val manifestFile = File(directory, MANIFEST_ENTRY)
        if (!manifestFile.isFile) return false
        val manifest =
            runCatching { Properties().apply { manifestFile.inputStream().use(::load) } }
                .getOrNull() ?: return false
        return runCatching { validateManifest(manifest, bundle, abi) }.isSuccess &&
            bundle.entries.all { entry ->
                manifest.getProperty("$entry.sha256") ==
                    packagedManifest.getProperty("$entry.sha256")
            } &&
            bundle.entries.all { File(directory, it).isFile } &&
            bundle.executables.all { File(directory, it).canExecute() }
    }

    private fun loadManifest(
        context: Context,
        assetPath: String,
    ): Properties = Properties().apply { context.assets.open(assetPath).use(::load) }

    private fun safeOutput(
        root: File,
        entry: String,
        bundleName: String,
    ): File {
        val output = File(root, entry)
        check(output.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            "Unsafe $bundleName entry: $entry"
        }
        return output
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
