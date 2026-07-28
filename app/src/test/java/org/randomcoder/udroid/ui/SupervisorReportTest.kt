package org.randomcoder.udroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorReportTest {
    @Test
    fun previewReturnsTheNewestEvents() {
        val journal = listOf("newest", "middle", "oldest")

        assertEquals(listOf("newest", "middle"), newestSupervisorEvents(journal, limit = 2))
    }

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
}
