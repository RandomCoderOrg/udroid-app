package org.randomcoder.udroid.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinuxCatalogueBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun expandAndScroll() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = null,
            iterations = 5,
            setupBlock = {
                killProcess()
                pressHome()
                startActivityAndWait()
                val showAll =
                    device.wait(
                        Until.findObject(By.text("Show all images")),
                        UI_TIMEOUT_MS,
                    )
                assertNotNull("Linux catalogue did not become ready", showAll)
            },
        ) {
            val showAll =
                device.wait(
                    Until.findObject(By.text("Show all images")),
                    UI_TIMEOUT_MS,
                )
            assertNotNull("Linux catalogue did not become ready", showAll)
            showAll.click()
            device.waitForIdle()
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                48,
            )
            device.waitForIdle()
        }

    private companion object {
        const val PACKAGE_NAME = "org.randomcoder.udroid"
        const val UI_TIMEOUT_MS = 10_000L
    }
}
