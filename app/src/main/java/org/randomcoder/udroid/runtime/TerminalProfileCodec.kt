package org.randomcoder.udroid.runtime

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

internal fun importTerminalProfile(
    name: String,
    contents: String,
): Result<TerminalColorProfile> =
    runCatching {
        val profileName = name.substringBeforeLast('.').ifBlank { "Imported profile" }
        if (contents.trimStart().startsWith("<?xml") || contents.contains("<plist")) {
            parseItermColors(profileName, contents)
        } else {
            parseTextTheme(profileName, contents)
        }
    }

internal fun exportTermuxProfile(profile: TerminalColorProfile): String =
    buildString {
        appendLine("background=${formatTerminalColor(profile.background)}")
        appendLine("foreground=${formatTerminalColor(profile.foreground)}")
        appendLine("cursor=${formatTerminalColor(profile.cursor)}")
        profile.ansiColors.forEachIndexed { index, color ->
            appendLine("color$index=${formatTerminalColor(color)}")
        }
    }

private fun parseTextTheme(
    name: String,
    contents: String,
): TerminalColorProfile {
    val colors = mutableMapOf<String, Int>()
    val palette = mutableMapOf<Int, Int>()
    contents.lineSequence().forEach { source ->
        val line = source.trim()
        if (line.isEmpty() || line.startsWith('#')) return@forEach
        GHOSTTY_PALETTE.matchEntire(line)?.let { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@let
            parseTerminalColor(match.groupValues[2])?.let { palette[index] = it }
            return@forEach
        }
        TEXT_COLOR.matchEntire(line)?.let { match ->
            val key = match.groupValues[1].lowercase()
            val color = parseTerminalColor(match.groupValues[2]) ?: return@let
            when {
                key.startsWith("color") -> key.removePrefix("color").toIntOrNull()?.let { palette[it] = color }
                else -> colors[key] = color
            }
        }
    }
    val foreground = colors["foreground"] ?: error("Theme is missing foreground")
    val background = colors["background"] ?: error("Theme is missing background")
    val ansi = (0 until TERMINAL_ANSI_COLOR_COUNT).map { palette[it] ?: error("Theme is missing color$it") }
    return TerminalProfiles.udroid.copy(
        id = "imported",
        name = name,
        background = background,
        foreground = foreground,
        cursor = colors["cursor"] ?: colors["cursor-color"] ?: foreground,
        ansiColors = ansi,
    )
}

private fun parseItermColors(
    name: String,
    contents: String,
): TerminalColorProfile {
    val factory = DocumentBuilderFactory.newInstance()
    runCatching { factory.isXIncludeAware = false }
    runCatching { factory.isExpandEntityReferences = false }
    XML_FEATURES.forEach { (feature, enabled) -> runCatching { factory.setFeature(feature, enabled) } }
    val builder = factory.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }
    val root = builder.parse(InputSource(StringReader(contents))).documentElement
    val dictionary = root.childElements().firstOrNull { it.tagName == "dict" } ?: error("Invalid iTerm2 palette")
    val entries = dictionary.plistDictionary()
    fun color(key: String): Int? = (entries[key] as? Element)?.plistColor()
    val foreground = color("Foreground Color") ?: error("Palette is missing foreground")
    return TerminalProfiles.udroid.copy(
        id = "imported",
        name = name,
        background = color("Background Color") ?: error("Palette is missing background"),
        foreground = foreground,
        cursor = color("Cursor Color") ?: foreground,
        ansiColors =
            (0 until TERMINAL_ANSI_COLOR_COUNT).map { index ->
                color("Ansi $index Color") ?: error("Palette is missing ANSI color $index")
            },
    )
}

private fun Element.plistDictionary(): Map<String, Element> {
    val children = childElements()
    return buildMap {
        var index = 0
        while (index + 1 < children.size) {
            if (children[index].tagName == "key") put(children[index].textContent, children[index + 1])
            index += 2
        }
    }
}

private fun Element.plistColor(): Int {
    val components = plistDictionary()
    fun component(name: String): Int =
        ((components[name]?.textContent?.toDoubleOrNull() ?: error("Color is missing $name")) * 255)
            .roundToInt()
            .coerceIn(0, 255)
    return 0xFF000000.toInt() or
        (component("Red Component") shl 16) or
        (component("Green Component") shl 8) or
        component("Blue Component")
}

private fun Element.childElements(): List<Element> =
    buildList {
        val nodes = childNodes
        for (index in 0 until nodes.length) (nodes.item(index) as? Element)?.let(::add)
    }

private val TEXT_COLOR = Regex("([A-Za-z0-9-]+)\\s*[:=]\\s*(#?[0-9A-Fa-f]{6})\\s*")
private val GHOSTTY_PALETTE = Regex("palette\\s*=\\s*(\\d+)\\s*=\\s*(#?[0-9A-Fa-f]{6})\\s*")
private val XML_FEATURES =
    mapOf(
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )
