package org.randomcoder.udroid.linuxapps

import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.system.measureTimeMillis

class DesktopApplicationScanner(
    private val currentDesktops: Set<String> = setOf("UDROID"),
) {
    fun scan(
        rootfs: File,
        locale: Locale = Locale.getDefault(),
    ): LinuxApplicationScanResult {
        require(rootfs.isDirectory) { "Installed rootfs is unavailable" }
        val applications = mutableListOf<LinuxApplication>()
        val claimedIds = mutableSetOf<String>()
        var scannedEntries = 0
        var ignoredEntries = 0

        val elapsed =
            measureTimeMillis {
                applicationDirectories(rootfs).forEach { directory ->
                    if (!directory.hostDirectory.isDirectory) return@forEach
                    directory.hostDirectory
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "desktop" }
                        .sortedBy { it.absolutePath }
                        .forEach { desktopFile ->
                            val relativePath =
                                desktopFile
                                    .relativeTo(directory.hostDirectory)
                                    .invariantSeparatorsPath
                            val desktopId = relativePath.replace('/', '-')
                            if (!claimedIds.add(desktopId)) return@forEach
                            scannedEntries++
                            val application =
                                parseApplication(
                                    rootfs = rootfs,
                                    desktopFile = desktopFile,
                                    desktopFileGuestPath =
                                        "${directory.guestDirectory}/$relativePath",
                                    desktopId = desktopId,
                                    locale = locale,
                                )
                            if (application == null) {
                                ignoredEntries++
                            } else {
                                applications += application
                            }
                        }
                }
            }

        return LinuxApplicationScanResult(
            applications = applications.sortedBy { it.name.lowercase(locale) },
            scannedEntries = scannedEntries,
            ignoredEntries = ignoredEntries,
            elapsedMillis = elapsed,
        )
    }

    private fun parseApplication(
        rootfs: File,
        desktopFile: File,
        desktopFileGuestPath: String,
        desktopId: String,
        locale: Locale,
    ): LinuxApplication? {
        val values = parseDesktopGroup(desktopFile) ?: return null
        if (values["Type"]?.trim() != "Application") return null
        if (values.boolean("Hidden") || values.boolean("NoDisplay")) return null
        if (!matchesDesktop(values)) return null

        val name = localizedValue(values, "Name", locale)?.takeIf(String::isNotBlank)
            ?: return null
        val rawExec = values["Exec"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val iconName = values["Icon"]?.let(::unescape)?.takeIf(String::isNotBlank)
        val arguments =
            DesktopExecParser
                .parse(
                    commandLine = rawExec,
                    applicationName = name,
                    iconName = iconName,
                    desktopFileGuestPath = desktopFileGuestPath,
                ).getOrNull()
                ?: return null
        if (!tryExecutableExists(rootfs, values["TryExec"])) return null

        return LinuxApplication(
            id = desktopId.removeSuffix(".desktop"),
            name = name,
            genericName = localizedValue(values, "GenericName", locale),
            comment = localizedValue(values, "Comment", locale),
            executable = arguments.first(),
            arguments = arguments.drop(1),
            iconName = iconName,
            iconPath = DesktopIconResolver.resolve(rootfs, iconName)?.absolutePath,
            desktopFilePath = desktopFile.absolutePath,
            desktopFileGuestPath = desktopFileGuestPath,
            workingDirectory =
                values["Path"]
                    ?.let(::unescape)
                    ?.takeIf { it.startsWith('/') }
                    ?: "/root",
            categories = values["Categories"].toDesktopList(),
            terminal = values.boolean("Terminal"),
        )
    }

    private fun matchesDesktop(values: Map<String, String>): Boolean {
        val only = values["OnlyShowIn"].toDesktopList().toSet()
        if (only.isNotEmpty() && only.intersect(currentDesktops).isEmpty()) return false
        val excluded = values["NotShowIn"].toDesktopList().toSet()
        return excluded.intersect(currentDesktops).isEmpty()
    }

    private fun tryExecutableExists(
        rootfs: File,
        rawTryExec: String?,
    ): Boolean {
        val tryExec = rawTryExec?.let(::unescape)?.trim().orEmpty()
        if (tryExec.isEmpty()) return true
        return if (tryExec.startsWith('/')) {
            GuestPathResolver.resolve(rootfs, tryExec).isExecutableFile()
        } else {
            EXECUTABLE_SEARCH_PATH.any { path ->
                GuestPathResolver.resolve(rootfs, "$path/$tryExec").isExecutableFile()
            }
        }
    }

    private fun parseDesktopGroup(file: File): Map<String, String>? {
        val values = linkedMapOf<String, String>()
        var inDesktopGroup = false
        file.useLines(Charsets.UTF_8) { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.removeSuffix("\r")
                if (line.startsWith("#") || line.isBlank()) return@forEach
                if (line.startsWith("[") && line.endsWith("]")) {
                    inDesktopGroup = line == "[Desktop Entry]"
                    return@forEach
                }
                if (!inDesktopGroup) return@forEach
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEach
                values.putIfAbsent(
                    line.substring(0, separator),
                    line.substring(separator + 1),
                )
            }
        }
        return values.takeIf { it.isNotEmpty() }
    }

    private fun localizedValue(
        values: Map<String, String>,
        key: String,
        locale: Locale,
    ): String? {
        localeKeys(locale).forEach { suffix ->
            values["$key[$suffix]"]?.let { return unescape(it) }
        }
        return values[key]?.let(::unescape)
    }

    private fun localeKeys(locale: Locale): List<String> =
        buildList {
            val language = locale.language.takeIf(String::isNotBlank) ?: return@buildList
            val country = locale.country.takeIf(String::isNotBlank)
            val variant = locale.variant.takeIf(String::isNotBlank)
            if (country != null && variant != null) add("${language}_${country}@$variant")
            if (country != null) add("${language}_$country")
            add(language)
        }

    private data class ApplicationDirectory(
        val hostDirectory: File,
        val guestDirectory: String,
    )

    private fun applicationDirectories(rootfs: File): List<ApplicationDirectory> =
        listOf(
            "/root/.local/share/applications",
            "/usr/local/share/applications",
            "/usr/share/applications",
            "/var/lib/flatpak/exports/share/applications",
        ).map { guestPath ->
            ApplicationDirectory(
                hostDirectory = File(rootfs, guestPath.removePrefix("/")),
                guestDirectory = guestPath,
            )
        }

    private companion object {
        val EXECUTABLE_SEARCH_PATH =
            listOf(
                "/usr/local/sbin",
                "/usr/local/bin",
                "/usr/sbin",
                "/usr/bin",
                "/sbin",
                "/bin",
            )
    }
}

private object DesktopIconResolver {
    private val extensions = listOf("png", "webp", "jpg", "jpeg", "svg", "xpm")
    private val sizes =
        listOf(
            "512x512",
            "256x256",
            "192x192",
            "128x128",
            "96x96",
            "64x64",
            "48x48",
            "32x32",
            "scalable",
        )
    private val themes = listOf("hicolor", "Adwaita", "Humanity", "Yaru")

    fun resolve(
        rootfs: File,
        iconName: String?,
    ): File? {
        if (iconName.isNullOrBlank()) return null
        if (iconName.startsWith('/')) {
            return GuestPathResolver.resolve(rootfs, iconName)?.takeIf(File::isFile)
        }
        val basename = iconName.substringBeforeLast('.', iconName)
        val requestedExtension =
            iconName.substringAfterLast('.', "").takeIf { it in extensions }
        val candidateExtensions =
            listOfNotNull(requestedExtension) + extensions.filterNot { it == requestedExtension }

        val bases =
            listOf(
                File(rootfs, "root/.local/share/icons"),
                File(rootfs, "root/.icons"),
                File(rootfs, "usr/local/share/icons"),
                File(rootfs, "usr/share/icons"),
            )
        bases.forEach { base ->
            themes.forEach { theme ->
                sizes.forEach { size ->
                    candidateExtensions.forEach { extension ->
                        File(base, "$theme/$size/apps/$basename.$extension")
                            .takeIf(File::isFile)
                            ?.let { return it }
                    }
                }
            }
        }
        candidateExtensions.forEach { extension ->
            listOf(
                File(rootfs, "usr/local/share/pixmaps/$basename.$extension"),
                File(rootfs, "usr/share/pixmaps/$basename.$extension"),
            ).firstOrNull(File::isFile)?.let { return it }
        }
        return null
    }
}

private object GuestPathResolver {
    fun resolve(
        rootfs: File,
        guestPath: String,
    ): File? {
        if (!guestPath.startsWith('/')) return null
        val pending =
            ArrayDeque(
                guestPath
                    .split('/')
                    .filter(String::isNotEmpty),
            )
        val resolved = mutableListOf<String>()
        var symbolicLinks = 0
        while (pending.isNotEmpty()) {
            when (val component = pending.removeFirst()) {
                "." -> Unit
                ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
                else -> {
                    val hostPath =
                        File(
                            rootfs,
                            (resolved + component).joinToString(File.separator),
                        ).toPath()
                    if (Files.isSymbolicLink(hostPath)) {
                        check(++symbolicLinks <= MAX_SYMBOLIC_LINKS) {
                            "Too many symbolic links while resolving $guestPath"
                        }
                        val target = Files.readSymbolicLink(hostPath).toString()
                        if (target.startsWith('/')) resolved.clear()
                        val targetComponents =
                            target
                                .split('/')
                                .filter(String::isNotEmpty)
                        targetComponents.asReversed().forEach(pending::addFirst)
                    } else {
                        resolved += component
                    }
                }
            }
        }
        return File(rootfs, resolved.joinToString(File.separator))
    }

    private const val MAX_SYMBOLIC_LINKS = 40
}

private fun File?.isExecutableFile(): Boolean =
    this?.let { it.isFile && it.canExecute() } == true

private fun Map<String, String>.boolean(key: String): Boolean =
    this[key]?.trim()?.equals("true", ignoreCase = true) == true

private fun String?.toDesktopList(): List<String> {
    if (this.isNullOrBlank()) return emptyList()
    return split(';')
        .map(::unescape)
        .map(String::trim)
        .filter(String::isNotEmpty)
}

private fun unescape(value: String): String {
    val result = StringBuilder()
    var escaped = false
    value.forEach { character ->
        if (!escaped && character == '\\') {
            escaped = true
            return@forEach
        }
        if (escaped) {
            result.append(
                when (character) {
                    's' -> ' '
                    'n' -> '\n'
                    't' -> '\t'
                    'r' -> '\r'
                    else -> character
                },
            )
            escaped = false
        } else {
            result.append(character)
        }
    }
    if (escaped) result.append('\\')
    return result.toString()
}
