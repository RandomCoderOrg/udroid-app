package org.randomcoder.udroid.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() =
        baselineProfileRule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
        }

    @Test
    fun linuxCatalogue() =
        baselineProfileRule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = false,
        ) {
            pressHome()
            startActivityAndWait()
            val showAll =
                device.wait(
                    Until.findObject(By.text("Browse all images")),
                    UI_TIMEOUT_MS,
                )
            assertNotNull("Linux catalogue did not become ready", showAll)
            showAll.click()
            device.waitForIdle()

            repeat(2) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 3 / 4,
                    device.displayWidth / 2,
                    device.displayHeight / 4,
                    24,
                )
                device.waitForIdle()
            }
        }

    @Test
    fun catalogueSearch() =
        baselineProfileRule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = false,
        ) {
            pressHome()
            startActivityAndWait()
            val search =
                device.wait(
                    Until.findObject(By.textContains("Search Ubuntu")),
                    UI_TIMEOUT_MS,
                )
            assertNotNull("Linux catalogue search did not become ready", search)
            search.click()
            device.executeShellCommand("input text debian")
            device.waitForIdle()
        }

    private companion object {
        const val PACKAGE_NAME = "org.randomcoder.udroid"
        const val UI_TIMEOUT_MS = 10_000L
    }
}
