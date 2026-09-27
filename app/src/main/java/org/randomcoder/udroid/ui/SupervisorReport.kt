package org.randomcoder.udroid.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class DiagnosticLogEntry(
    val raw: String,
    val timestamp: String,
    val severity: String,
    val component: String,
    val event: String,
    val message: String,
    val fields: String,
)

internal fun formatDiagnosticLogEntry(entry: DiagnosticLogEntry): String =
    buildString {
        append(entry.timestamp)
        append(' ')
        append(entry.severity.uppercase())
        append(' ')
        append(entry.component)
        append('/')
        appendLine(entry.event)
        append(entry.message)
        if (entry.fields.isNotEmpty()) {
            appendLine()
            append(entry.fields)
        }
    }

internal fun diagnosticLogEntries(
    newestFirstJournalLines: List<String>,
    query: String,
): List<DiagnosticLogEntry> {
    val entries = newestFirstJournalLines.map(::parseDiagnosticLogEntry)
    if (query.isBlank()) return entries
    return entries.filter {
        it.raw.contains(query, ignoreCase = true) ||
            it.displayText.contains(query, ignoreCase = true)
    }
}

private val DiagnosticLogEntry.displayText: String
    get() = "$timestamp $severity $component $event $message $fields"

private fun parseDiagnosticLogEntry(line: String): DiagnosticLogEntry {
    val payload = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
        ?: return DiagnosticLogEntry(line, "", "raw", "journal", "event", line, "")
    val fields = payload["fields"] as? JsonObject
    return DiagnosticLogEntry(
        raw = line,
        timestamp = payload.string("timestamp").replace('T', ' '),
        severity = payload.string("severity").ifBlank { "info" },
        component = payload.string("component").ifBlank { "app" },
        event = payload.string("event").ifBlank { "event" },
        message = payload.string("message").ifBlank { line },
        fields =
            fields
                ?.toSortedMap()
                ?.entries
                ?.joinToString("  ") { (key, value) ->
                    "$key=${(value as? JsonPrimitive)?.content ?: value}"
                }
                .orEmpty(),
    )
}

private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

internal fun buildSupervisorReport(
    appVersion: String,
    androidVersion: String,
    device: String,
    capturedAt: String,
    newestFirstJournalLines: List<String>,
): String =
    buildString {
        appendLine("uDroid diagnostics")
        appendLine("App version: $appVersion")
        appendLine("Android: $androidVersion")
        appendLine("Device: $device")
        appendLine("Captured: $capturedAt")
        appendLine("Supervisor events: ${newestFirstJournalLines.size}")
        appendLine()
        appendLine("Supervisor journal (oldest to newest)")
        if (newestFirstJournalLines.isEmpty()) {
            appendLine("(no supervisor events recorded)")
        } else {
            newestFirstJournalLines.asReversed().forEach(::appendLine)
        }
    }.trimEnd()
