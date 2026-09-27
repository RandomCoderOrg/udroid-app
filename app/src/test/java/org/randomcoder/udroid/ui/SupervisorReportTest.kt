package org.randomcoder.udroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorReportTest {
    @Test
    fun reportIncludesContextAndOrdersEventsChronologically() {
        val report =
            buildSupervisorReport(
                appVersion = "0.0.7",
                androidVersion = "16 (API 36)",
                device = "Google Pixel",
                capturedAt = "2026-07-28T10:00:00Z",
                newestFirstJournalLines = listOf("""{"event":"new"}""", """{"event":"old"}"""),
            )

        assertTrue(report.contains("App version: 0.0.7"))
        assertTrue(report.contains("Android: 16 (API 36)"))
        assertTrue(report.contains("Device: Google Pixel"))
        assertTrue(report.indexOf("""{"event":"old"}""") < report.indexOf("""{"event":"new"}"""))
    }

    @Test
    fun diagnosticEntriesFormatAndSearchStructuredFields() {
        val line =
            """{"timestamp":"2026-09-27T09:00:00Z","component":"x11","severity":"warning","event":"socket_wait","message":"Waiting for display","fields":{"display":":1"}}"""

        val entry = diagnosticLogEntries(listOf(line), "display").single()

        assertEquals("2026-09-27 09:00:00Z", entry.timestamp)
        assertEquals("warning", entry.severity)
        assertEquals("x11", entry.component)
        assertEquals("socket_wait", entry.event)
        assertEquals("Waiting for display", entry.message)
        assertEquals("display=:1", entry.fields)
        assertEquals(1, diagnosticLogEntries(listOf(line), "WAITING").size)
        assertTrue(diagnosticLogEntries(listOf(line), "missing").isEmpty())
        assertEquals(
            "2026-09-27 09:00:00Z WARNING x11/socket_wait\n" +
                "Waiting for display\n" +
                "display=:1",
            formatDiagnosticLogEntry(entry),
        )
    }
}
