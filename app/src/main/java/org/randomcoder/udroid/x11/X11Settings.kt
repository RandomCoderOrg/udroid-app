package org.randomcoder.udroid.x11

import android.content.Context
import kotlin.math.roundToInt

enum class X11ResolutionMode {
    NATIVE,
    SCALED,
    EXACT,
}

enum class X11DisplayFilter {
    NEAREST,
    BILINEAR,
}

enum class X11TouchMode {
    DIRECT,
    TRACKPAD,
    NATIVE,
}

data class X11Settings(
    val resolutionMode: X11ResolutionMode = X11ResolutionMode.NATIVE,
    val displayScalePercent: Int = 100,
    val exactWidth: Int = 1280,
    val exactHeight: Int = 720,
    val stretchDisplay: Boolean = false,
    val displayFilter: X11DisplayFilter = X11DisplayFilter.NEAREST,
    val touchMode: X11TouchMode = X11TouchMode.DIRECT,
    val trackpadSpeedPercent: Int = 100,
    val preferScancodes: Boolean = false,
    val keepScreenOn: Boolean = true,
    val startControlsCollapsed: Boolean = false,
)

data class X11Viewport(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val guestWidth: Int,
    val guestHeight: Int,
)

object X11GeometryCalculator {
    @JvmStatic
    fun calculate(
        surfaceWidth: Int,
        surfaceHeight: Int,
        settings: X11Settings,
    ): X11Viewport {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            return X11Viewport(0, 0, 0, 0, 0, 0)
        }

        val guestSize =
            when (settings.resolutionMode) {
                X11ResolutionMode.NATIVE -> surfaceWidth to surfaceHeight
                X11ResolutionMode.SCALED -> {
                    val scale = settings.displayScalePercent.coerceIn(50, 200)
                    (surfaceWidth * 100f / scale).roundToInt().coerceAtLeast(1) to
                        (surfaceHeight * 100f / scale).roundToInt().coerceAtLeast(1)
                }
                X11ResolutionMode.EXACT ->
                    settings.exactWidth.coerceAtLeast(1) to
                        settings.exactHeight.coerceAtLeast(1)
            }

        val (guestWidth, guestHeight) = guestSize
        if (settings.stretchDisplay) {
            return X11Viewport(
                left = 0,
                top = 0,
                width = surfaceWidth,
                height = surfaceHeight,
                guestWidth = guestWidth,
                guestHeight = guestHeight,
            )
        }

        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
        val guestAspect = guestWidth.toFloat() / guestHeight
        val viewportWidth: Int
        val viewportHeight: Int
        if (surfaceAspect > guestAspect) {
            viewportHeight = surfaceHeight
            viewportWidth = (surfaceHeight * guestAspect).roundToInt()
        } else {
            viewportWidth = surfaceWidth
            viewportHeight = (surfaceWidth / guestAspect).roundToInt()
        }

        return X11Viewport(
            left = (surfaceWidth - viewportWidth) / 2,
            top = (surfaceHeight - viewportHeight) / 2,
            width = viewportWidth,
            height = viewportHeight,
            guestWidth = guestWidth,
            guestHeight = guestHeight,
        )
    }
}

class X11SettingsStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    fun load(): X11Settings =
        X11Settings(
            resolutionMode =
                enumValue(
                    preferences.getString(KEY_RESOLUTION_MODE, null),
                    X11ResolutionMode.NATIVE,
                ),
            displayScalePercent =
                preferences.getInt(KEY_DISPLAY_SCALE, 100).coerceIn(50, 200),
            exactWidth = preferences.getInt(KEY_EXACT_WIDTH, 1280).coerceAtLeast(1),
            exactHeight = preferences.getInt(KEY_EXACT_HEIGHT, 720).coerceAtLeast(1),
            stretchDisplay = preferences.getBoolean(KEY_STRETCH_DISPLAY, false),
            displayFilter =
                enumValue(
                    preferences.getString(KEY_DISPLAY_FILTER, null),
                    X11DisplayFilter.NEAREST,
                ),
            touchMode =
                enumValue(
                    preferences.getString(KEY_TOUCH_MODE, null),
                    X11TouchMode.DIRECT,
                ),
            trackpadSpeedPercent =
                preferences.getInt(KEY_TRACKPAD_SPEED, 100).coerceIn(25, 300),
            preferScancodes = preferences.getBoolean(KEY_PREFER_SCANCODES, false),
            keepScreenOn = preferences.getBoolean(KEY_KEEP_SCREEN_ON, true),
            startControlsCollapsed =
                preferences.getBoolean(KEY_START_CONTROLS_COLLAPSED, false),
        )

    fun save(settings: X11Settings): X11Settings {
        preferences
            .edit()
            .putString(KEY_RESOLUTION_MODE, settings.resolutionMode.name)
            .putInt(KEY_DISPLAY_SCALE, settings.displayScalePercent)
            .putInt(KEY_EXACT_WIDTH, settings.exactWidth)
            .putInt(KEY_EXACT_HEIGHT, settings.exactHeight)
            .putBoolean(KEY_STRETCH_DISPLAY, settings.stretchDisplay)
            .putString(KEY_DISPLAY_FILTER, settings.displayFilter.name)
            .putString(KEY_TOUCH_MODE, settings.touchMode.name)
            .putInt(KEY_TRACKPAD_SPEED, settings.trackpadSpeedPercent)
            .putBoolean(KEY_PREFER_SCANCODES, settings.preferScancodes)
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .putBoolean(
                KEY_START_CONTROLS_COLLAPSED,
                settings.startControlsCollapsed,
            ).apply()
        return settings
    }

    private inline fun <reified T : Enum<T>> enumValue(
        value: String?,
        fallback: T,
    ): T = enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private companion object {
        const val PREFERENCES_NAME = "embedded-x11-settings"
        const val KEY_RESOLUTION_MODE = "resolution-mode"
        const val KEY_DISPLAY_SCALE = "display-scale"
        const val KEY_EXACT_WIDTH = "exact-width"
        const val KEY_EXACT_HEIGHT = "exact-height"
        const val KEY_STRETCH_DISPLAY = "stretch-display"
        const val KEY_DISPLAY_FILTER = "display-filter"
        const val KEY_TOUCH_MODE = "touch-mode"
        const val KEY_TRACKPAD_SPEED = "trackpad-speed"
        const val KEY_PREFER_SCANCODES = "prefer-scancodes"
        const val KEY_KEEP_SCREEN_ON = "keep-screen-on"
        const val KEY_START_CONTROLS_COLLAPSED = "start-controls-collapsed"
    }
}
