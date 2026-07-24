package org.randomcoder.udroid.x11

import org.junit.Assert.assertEquals
import org.junit.Test

class X11GeometryCalculatorTest {
    @Test
    fun nativeResolutionUsesCompleteSurface() {
        val viewport =
            X11GeometryCalculator.calculate(
                surfaceWidth = 1080,
                surfaceHeight = 2000,
                settings = X11Settings(),
            )

        assertEquals(X11Viewport(0, 0, 1080, 2000, 1080, 2000), viewport)
    }

    @Test
    fun scaledResolutionChangesGuestSizeWithoutChangingAspect() {
        val viewport =
            X11GeometryCalculator.calculate(
                surfaceWidth = 1080,
                surfaceHeight = 1920,
                settings =
                    X11Settings(
                        resolutionMode = X11ResolutionMode.SCALED,
                        displayScalePercent = 150,
                    ),
            )

        assertEquals(X11Viewport(0, 0, 1080, 1920, 720, 1280), viewport)
    }

    @Test
    fun exactResolutionIsLetterboxedToPreserveAspect() {
        val viewport =
            X11GeometryCalculator.calculate(
                surfaceWidth = 1080,
                surfaceHeight = 1920,
                settings =
                    X11Settings(
                        resolutionMode = X11ResolutionMode.EXACT,
                        exactWidth = 1280,
                        exactHeight = 720,
                    ),
            )

        assertEquals(X11Viewport(0, 656, 1080, 608, 1280, 720), viewport)
    }

    @Test
    fun stretchUsesCompleteSurfaceForExactResolution() {
        val viewport =
            X11GeometryCalculator.calculate(
                surfaceWidth = 1080,
                surfaceHeight = 1920,
                settings =
                    X11Settings(
                        resolutionMode = X11ResolutionMode.EXACT,
                        exactWidth = 1280,
                        exactHeight = 720,
                        stretchDisplay = true,
                    ),
            )

        assertEquals(X11Viewport(0, 0, 1080, 1920, 1280, 720), viewport)
    }
}
