package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityProbeTest {
    @Test
    fun `classifies phantom process monitor precedence and failures`() {
        data class Case(
            val name: String,
            val sdk: Int = 31,
            val global: ProbeRead,
            val property: ProbeRead,
            val expected: CapabilityStatus,
            val expectedAction: Boolean = false,
            val expectedActive: Boolean = false,
        )

        val cases =
            listOf(
                Case(
                    "global false overrides property",
                    global = read("false"),
                    property = read("true"),
                    expected = CapabilityStatus.PASS,
                ),
                Case(
                    "global true overrides property",
                    global = read("true"),
                    property = read("false"),
                    expected = CapabilityStatus.WARNING,
                    expectedActive = true,
                ),
                Case(
                    "blank global falls through",
                    global = read(""),
                    property = read("false"),
                    expected = CapabilityStatus.PASS,
                ),
                Case(
                    "both blank use default",
                    global = read(""),
                    property = read(""),
                    expected = CapabilityStatus.WARNING,
                ),
                Case(
                    "blank global falls through to enabled property",
                    global = read(""),
                    property = read("true"),
                    expected = CapabilityStatus.WARNING,
                    expectedActive = true,
                ),
                Case(
                    "failed global is unknown",
                    global = ProbeRead(false),
                    property = read("false"),
                    expected = CapabilityStatus.WARNING,
                ),
                Case(
                    "failed property is unknown",
                    global = read(""),
                    property = ProbeRead(false),
                    expected = CapabilityStatus.WARNING,
                ),
                Case(
                    "invalid global is unknown",
                    global = read("sometimes"),
                    property = read("false"),
                    expected = CapabilityStatus.WARNING,
                ),
                Case(
                    "Android 30 does not use monitor",
                    sdk = 30,
                    global = ProbeRead(false),
                    property = ProbeRead(false),
                    expected = CapabilityStatus.PASS,
                ),
                Case(
                    "API 33 enabled has no settings action",
                    sdk = 33,
                    global = read("true"),
                    property = read(""),
                    expected = CapabilityStatus.WARNING,
                    expectedActive = true,
                ),
                Case(
                    "API 33 unknown has no settings action",
                    sdk = 33,
                    global = ProbeRead(false),
                    property = read(""),
                    expected = CapabilityStatus.WARNING,
                ),
                Case(
                    "API 34 enabled has settings action",
                    sdk = 34,
                    global = read("true"),
                    property = read(""),
                    expected = CapabilityStatus.WARNING,
                    expectedAction = true,
                    expectedActive = true,
                ),
                Case(
                    "API 34 unknown has settings action",
                    sdk = 34,
                    global = ProbeRead(false),
                    property = read(""),
                    expected = CapabilityStatus.WARNING,
                    expectedAction = true,
                ),
            )

        cases.forEach { case ->
            val result = classifyPhantomProcessMonitor(case.sdk, case.global, case.property)
            assertEquals(
                case.name,
                case.expected,
                result.status,
            )
            assertEquals(case.name, case.expectedAction, result.showDeveloperOptionsAction)
            assertEquals(case.name, case.expectedActive, result.linuxProcessRestrictionActive)
        }
    }

    private fun read(value: String) = ProbeRead(succeeded = true, value = value)
}
