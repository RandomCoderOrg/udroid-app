package org.randomcoder.udroid.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.x11.input.InputStub
import org.randomcoder.udroid.x11.X11DisplayView
import org.randomcoder.udroid.x11.OverlayKeyboard
import org.randomcoder.udroid.x11.OverlayKeyState
import kotlin.math.roundToInt

internal class X11DesktopHostView(context: Context) : FrameLayout(context) {
    val displayView = X11DisplayView(context)

    private val density = resources.displayMetrics.density
    private val palette = LinearLayout(context)
    private val keyboardPalette = LinearLayout(context)
    private val keyboardButtons = mutableListOf<TextView>()
    private val keyboardState = OverlayKeyState { binding, pressed ->
        when (binding) {
            OverlayKeyboard.MOUSE_LEFT ->
                displayView.setOverlayMouseButton(InputStub.BUTTON_LEFT, pressed)
            OverlayKeyboard.MOUSE_MIDDLE ->
                displayView.setOverlayMouseButton(InputStub.BUTTON_MIDDLE, pressed)
            OverlayKeyboard.MOUSE_RIGHT ->
                displayView.setOverlayMouseButton(InputStub.BUTTON_RIGHT, pressed)
            OverlayKeyboard.SCROLL_UP -> if (pressed) displayView.scrollMouse(120f)
            OverlayKeyboard.SCROLL_DOWN -> if (pressed) displayView.scrollMouse(-120f)
            else -> displayView.setOverlayKey(binding, pressed)
        }
    }
    private val heldKeyboardSlots = mutableSetOf<Int>()
    private val chordUsedSlots = mutableSetOf<Int>()
    private var keyboardBindings = OverlayKeyboard.defaults
    private var keyboardPaletteLocked = false
    private val mouseButtons = mutableMapOf<Int, TextView>()
    private val scrollButtons = mutableListOf<TextView>()
    private val paletteTextViews = mutableListOf<TextView>()
    private var paletteLocked = false
    private var latchedButton = InputStub.BUTTON_UNDEFINED
    private var momentaryButton = InputStub.BUTTON_UNDEFINED
    private var primaryContainer = Color.DKGRAY
    private var surfaceContainer = Color.rgb(35, 35, 35)
    private var onSurface = Color.WHITE
    private var outline = Color.GRAY

    init {
        isMotionEventSplittingEnabled = true
        addView(
            displayView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        buildPalette()
        addView(
            palette,
            LayoutParams(dp(160), LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(dp(12), dp(12), dp(12), dp(12))
            },
        )
        palette.visibility = View.GONE
        buildKeyboardPalette()
        addView(
            keyboardPalette,
            LayoutParams(dp(224), LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(dp(12), dp(12), dp(12), dp(12))
            },
        )
        keyboardPalette.visibility = View.GONE
        displayView.setOnInputReset {
            keyboardState.clear()
            heldKeyboardSlots.clear()
            chordUsedSlots.clear()
            updateKeyboardButtons()
            releaseMouseButtons()
        }
        displayView.setOnCompanionInput(::markKeyboardChordUsed)
    }

    fun setPaletteVisible(visible: Boolean) {
        if (!visible) releaseMouseButtons()
        palette.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setKeyboardPaletteVisible(visible: Boolean) {
        if (!visible) releaseKeyboard()
        keyboardPalette.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setKeyboardBindings(bindings: List<Int>) {
        val normalized = OverlayKeyboard.normalized(bindings)
        if (normalized == keyboardBindings) return
        releaseKeyboard()
        keyboardBindings = normalized
        keyboardButtons.forEachIndexed { index, view ->
            val label = OverlayKeyboard.label(normalized[index])
            view.text = label
            view.contentDescription = "Hold $label key"
        }
    }

    fun setPaletteColors(
        primaryContainer: Int,
        surfaceContainer: Int,
        onSurface: Int,
        outline: Int,
    ) {
        this.primaryContainer = primaryContainer
        this.surfaceContainer = surfaceContainer
        this.onSurface = onSurface
        this.outline = outline
        palette.background = roundedBackground(surfaceContainer, outline, 30)
        keyboardPalette.background = roundedBackground(surfaceContainer, outline, 30)
        paletteTextViews.forEach { it.setTextColor(onSurface) }
        mouseButtons.forEach { (button, view) -> updateMouseButton(view, button) }
        scrollButtons.forEach { it.background = mouseButtonBackground(true) }
        updateKeyboardButtons()
    }

    private fun buildPalette() {
        palette.orientation = LinearLayout.VERTICAL
        palette.setPadding(dp(8), dp(8), dp(8), dp(8))
        palette.background = roundedBackground(surfaceContainer, outline, 30)

        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val title = textButton("☰  Mouse", "Move mouse controls").apply {
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(typeface, Typeface.BOLD)
            isClickable = false
        }
        val lock = textButton("Lock", "Lock mouse controls")
        header.addView(title, LinearLayout.LayoutParams(0, dp(36), 1f))
        header.addView(lock, LinearLayout.LayoutParams(dp(52), dp(36)))
        palette.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))

        lock.setOnClickListener {
            paletteLocked = !paletteLocked
            lock.text = if (paletteLocked) "Move" else "Lock"
            lock.contentDescription =
                if (paletteLocked) "Unlock mouse controls" else "Lock mouse controls"
        }
        installPaletteDrag(header, palette) { paletteLocked }

        val mouseBody =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        fun addMouseButton(button: Int, label: String, width: Int, height: Int) {
            val view = textButton(label, "Hold $label mouse button")
            mouseButtons[button] = view
            installMouseButton(view, button)
            mouseBody.addView(view, LinearLayout.LayoutParams(dp(width), dp(height)))
        }

        addMouseButton(InputStub.BUTTON_LEFT, "L", 48, 108)
        mouseBody.addView(View(context), LinearLayout.LayoutParams(dp(4), 1))
        val wheel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        wheel.addView(scrollButton("↑", 120f), LinearLayout.LayoutParams(dp(36), dp(36)))
        val middle = textButton("M", "Hold middle mouse button")
        mouseButtons[InputStub.BUTTON_MIDDLE] = middle
        installMouseButton(middle, InputStub.BUTTON_MIDDLE)
        wheel.addView(middle, LinearLayout.LayoutParams(dp(36), dp(36)))
        wheel.addView(scrollButton("↓", -120f), LinearLayout.LayoutParams(dp(36), dp(36)))
        mouseBody.addView(wheel, LinearLayout.LayoutParams(dp(36), dp(108)))
        mouseBody.addView(View(context), LinearLayout.LayoutParams(dp(4), 1))
        addMouseButton(InputStub.BUTTON_RIGHT, "R", 48, 108)
        palette.addView(mouseBody)
    }

    private fun buildKeyboardPalette() {
        keyboardPalette.orientation = LinearLayout.VERTICAL
        keyboardPalette.isMotionEventSplittingEnabled = true
        keyboardPalette.setPadding(dp(8), dp(8), dp(8), dp(8))
        keyboardPalette.background = roundedBackground(surfaceContainer, outline, 30)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = textButton("☰  Keys", "Move keyboard controls").apply {
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(typeface, Typeface.BOLD)
            isClickable = false
        }
        val lock = textButton("Lock", "Lock keyboard controls")
        header.addView(title, LinearLayout.LayoutParams(0, dp(36), 1f))
        header.addView(lock, LinearLayout.LayoutParams(dp(52), dp(36)))
        keyboardPalette.addView(header)
        lock.setOnClickListener {
            keyboardPaletteLocked = !keyboardPaletteLocked
            lock.text = if (keyboardPaletteLocked) "Move" else "Lock"
            lock.contentDescription =
                if (keyboardPaletteLocked) "Unlock keyboard controls" else "Lock keyboard controls"
        }
        installPaletteDrag(header, keyboardPalette) { keyboardPaletteLocked }

        repeat(3) { rowIndex ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isMotionEventSplittingEnabled = true
            }
            repeat(4) { columnIndex ->
                val slot = rowIndex * 4 + columnIndex
                val label = OverlayKeyboard.label(keyboardBindings[slot])
                val view = textButton(label, "Hold $label key").apply {
                    textSize = 12f
                    background = mouseButtonBackground(false)
                }
                installKeyboardButton(view, slot)
                keyboardButtons += view
                row.addView(view, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            keyboardPalette.addView(row, LinearLayout.LayoutParams(dp(208), dp(48)))
        }
    }

    private fun installPaletteDrag(
        header: View,
        targetPalette: View,
        isLocked: () -> Boolean,
    ) {
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0f
        var startY = 0f
        header.setOnTouchListener { _, event ->
            if (isLocked()) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = targetPalette.x
                    startY = targetPalette.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    targetPalette.x =
                        (startX + event.rawX - startRawX)
                            .coerceIn(0f, (width - targetPalette.width).coerceAtLeast(0).toFloat())
                    targetPalette.y =
                        (startY + event.rawY - startRawY)
                            .coerceIn(0f, (height - targetPalette.height).coerceAtLeast(0).toFloat())
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun installMouseButton(view: TextView, button: Int) {
        var momentaryPress = false
        val beginMomentaryPress =
            Runnable {
                if (view.isPressed && latchedButton != button) {
                    releaseMouseButtons()
                    momentaryButton = button
                    momentaryPress = true
                    displayView.setMouseButton(button, true)
                    updateMouseButtons()
                }
            }
        view.setOnClickListener { toggleMouseButton(button) }
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    markKeyboardChordUsed()
                    target.isPressed = true
                    momentaryPress = false
                    target.postDelayed(
                        beginMomentaryPress,
                        ViewConfiguration.getTapTimeout().toLong(),
                    )
                    true
                }

                MotionEvent.ACTION_UP -> {
                    target.removeCallbacks(beginMomentaryPress)
                    target.isPressed = false
                    if (momentaryPress) {
                        displayView.setMouseButton(button, false)
                        momentaryButton = InputStub.BUTTON_UNDEFINED
                        updateMouseButtons()
                    } else {
                        target.performClick()
                    }
                    momentaryPress = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    target.removeCallbacks(beginMomentaryPress)
                    target.isPressed = false
                    if (momentaryPress) {
                        displayView.setMouseButton(button, false)
                        momentaryButton = InputStub.BUTTON_UNDEFINED
                        updateMouseButtons()
                    }
                    momentaryPress = false
                    true
                }

                else -> true
            }
        }
    }

    private fun installKeyboardButton(view: TextView, slot: Int) {
        var downTime = 0L
        var handledTouchClick = false
        view.setOnClickListener {
            if (handledTouchClick) {
                handledTouchClick = false
            } else if (displayView.isRendererAttached) {
                val keyCode = keyboardBindings[slot]
                if (OverlayKeyboard.isModifier(keyCode)) {
                    toggleLatchedKey(slot)
                } else {
                    keyboardState.press(touchOwner(slot), keyCode)
                    keyboardState.release(touchOwner(slot))
                }
                updateKeyboardButtons()
            }
        }
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!displayView.isRendererAttached) return@setOnTouchListener true
                    if (heldKeyboardSlots.isNotEmpty()) {
                        chordUsedSlots.addAll(heldKeyboardSlots)
                        chordUsedSlots.add(slot)
                    }
                    heldKeyboardSlots.add(slot)
                    downTime = event.eventTime
                    target.isPressed = true
                    keyboardState.press(touchOwner(slot), keyboardBindings[slot])
                    updateKeyboardButtons()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    target.isPressed = false
                    val keyCode = keyboardBindings[slot]
                    if (shouldLatchModifier(
                            isHeld = slot in heldKeyboardSlots,
                            isModifier = OverlayKeyboard.isModifier(keyCode),
                            usedInChord = slot in chordUsedSlots,
                            heldMillis = event.eventTime - downTime,
                            tapThresholdMillis = ViewConfiguration.getLongPressTimeout().toLong(),
                        )) {
                        toggleLatchedKey(slot)
                    }
                    keyboardState.release(touchOwner(slot))
                    heldKeyboardSlots.remove(slot)
                    chordUsedSlots.remove(slot)
                    updateKeyboardButtons()
                    handledTouchClick = true
                    target.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    target.isPressed = false
                    keyboardState.release(touchOwner(slot))
                    heldKeyboardSlots.remove(slot)
                    chordUsedSlots.remove(slot)
                    updateKeyboardButtons()
                    true
                }

                else -> true
            }
        }
    }

    private fun toggleLatchedKey(slot: Int) {
        val owner = latchOwner(slot)
        if (keyboardState.isPressed(owner)) {
            keyboardState.release(owner)
        } else {
            keyboardState.press(owner, keyboardBindings[slot])
        }
    }

    private fun markKeyboardChordUsed() {
        chordUsedSlots.addAll(heldKeyboardSlots)
    }

    private fun releaseKeyboard() {
        keyboardState.releaseAll()
        heldKeyboardSlots.clear()
        chordUsedSlots.clear()
        updateKeyboardButtons()
    }

    private fun updateKeyboardButtons() {
        keyboardButtons.forEachIndexed { slot, view ->
            view.background =
                mouseButtonBackground(
                    keyboardState.isPressed(touchOwner(slot)) ||
                        keyboardState.isPressed(latchOwner(slot)),
                )
        }
    }

    private fun touchOwner(slot: Int) = slot * 2

    private fun latchOwner(slot: Int) = slot * 2 + 1

    private fun toggleMouseButton(button: Int) {
        val next = nextLatchedMouseButton(latchedButton, button)
        releaseMouseButtons()
        latchedButton = next
        if (next != InputStub.BUTTON_UNDEFINED) displayView.setMouseButton(next, true)
        updateMouseButtons()
    }

    private fun releaseMouseButtons() {
        if (latchedButton != InputStub.BUTTON_UNDEFINED) {
            displayView.setMouseButton(latchedButton, false)
        }
        if (momentaryButton != InputStub.BUTTON_UNDEFINED) {
            displayView.setMouseButton(momentaryButton, false)
        }
        latchedButton = InputStub.BUTTON_UNDEFINED
        momentaryButton = InputStub.BUTTON_UNDEFINED
        updateMouseButtons()
    }

    private fun updateMouseButtons() {
        mouseButtons.forEach { (button, view) -> updateMouseButton(view, button) }
    }

    private fun updateMouseButton(view: TextView, button: Int) {
        val active = latchedButton == button || momentaryButton == button
        view.background = mouseButtonBackground(active)
    }

    private fun scrollButton(label: String, amount: Float) =
        textButton(label, if (amount > 0) "Scroll up" else "Scroll down").apply {
            setOnClickListener {
                markKeyboardChordUsed()
                displayView.scrollMouse(amount)
            }
            background = mouseButtonBackground(true)
            scrollButtons += this
        }

    private fun textButton(label: String, description: String) =
        TextView(context)
            .apply {
                text = label
                contentDescription = description
                gravity = Gravity.CENTER
                setTextColor(onSurface)
                textSize = 14f
                isClickable = true
                isFocusable = true
            }.also { paletteTextViews += it }

    private fun mouseButtonBackground(active: Boolean): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedBackground(primaryContainer, outline, 20))
            addState(
                intArrayOf(),
                roundedBackground(if (active) primaryContainer else Color.TRANSPARENT, outline, 20),
            )
        }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    override fun onDetachedFromWindow() {
        releaseKeyboard()
        releaseMouseButtons()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            releaseKeyboard()
            releaseMouseButtons()
        }
    }
}

internal fun nextLatchedMouseButton(current: Int, tapped: Int): Int =
    if (current == tapped) InputStub.BUTTON_UNDEFINED else tapped

internal fun shouldLatchModifier(
    isHeld: Boolean,
    isModifier: Boolean,
    usedInChord: Boolean,
    heldMillis: Long,
    tapThresholdMillis: Long,
): Boolean =
    isHeld && isModifier && !usedInChord &&
        heldMillis < tapThresholdMillis
