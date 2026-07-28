package org.randomcoder.udroid.ui

internal fun newestSupervisorEvents(
    newestFirstJournalLines: List<String>,
    limit: Int = 25,
): List<String> = newestFirstJournalLines.take(limit)

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
