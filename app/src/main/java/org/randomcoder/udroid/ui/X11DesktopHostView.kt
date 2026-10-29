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
import org.randomcoder.udroid.x11.DisplayKey
import org.randomcoder.udroid.x11.OverlayKeyboard
import org.randomcoder.udroid.x11.OverlayKeyState
import kotlin.math.roundToInt

internal class X11DesktopHostView(context: Context) : FrameLayout(context) {
    val displayView = X11DisplayView(context)

    private val density = resources.displayMetrics.density
    private val keyboardStripHeight = dp(76)
    private val palette = LinearLayout(context)
    private val keyboardStrip = LinearLayout(context)
    private val displayKeyLayer = FrameLayout(context)
    private val keyboardButtons = mutableListOf<TextView>()
    private val displayKeyButtons = mutableListOf<TextView>()
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
    private val modifierTaps = ModifierDoubleTap(ViewConfiguration.getDoubleTapTimeout().toLong())
    private var keyboardBindings = OverlayKeyboard.defaults
    private var displayKeys = emptyList<DisplayKey>()
    private var displayKeysVisible = false
    private var arrangingDisplayKeys = false
    var onDisplayKeysMoved: ((List<DisplayKey>) -> Unit)? = null
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
        displayKeyLayer.isMotionEventSplittingEnabled = true
        addView(
            displayKeyLayer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        displayKeyLayer.visibility = View.GONE
        buildKeyboardStrip()
        addView(
            keyboardStrip,
            LayoutParams(LayoutParams.MATCH_PARENT, keyboardStripHeight).apply { gravity = Gravity.BOTTOM },
        )
        keyboardStrip.visibility = View.GONE
        displayKeyLayer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionDisplayKeys() }
        displayView.setOnInputReset {
            keyboardState.clear()
            heldKeyboardSlots.clear()
            chordUsedSlots.clear()
            modifierTaps.clear()
            updateKeyboardButtons()
            releaseMouseButtons()
        }
        displayView.setOnCompanionInput(::markKeyboardChordUsed)
    }

    fun setPaletteVisible(visible: Boolean) {
        if (!visible) releaseMouseButtons()
        palette.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setKeyboardStripVisible(visible: Boolean) {
        if ((keyboardStrip.visibility == View.VISIBLE) == visible) return
        if (!visible && keyboardStrip.visibility == View.VISIBLE) releaseKeyboard()
        keyboardStrip.visibility = if (visible) View.VISIBLE else View.GONE
        val bottomMargin = if (visible) keyboardStripHeight else 0
        (displayView.layoutParams as LayoutParams).also {
            it.bottomMargin = bottomMargin
            displayView.layoutParams = it
        }
        (displayKeyLayer.layoutParams as LayoutParams).also {
            it.bottomMargin = bottomMargin
            displayKeyLayer.layoutParams = it
        }
    }

    fun setDisplayKeysVisible(visible: Boolean) {
        if (!visible && displayKeysVisible) releaseKeyboard()
        displayKeysVisible = visible
        displayKeyLayer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setDisplayKeysArranging(arranging: Boolean) {
        if (arranging == arrangingDisplayKeys) return
        releaseKeyboard()
        arrangingDisplayKeys = arranging
        displayKeyButtons.forEach { it.isActivated = arranging }
        updateKeyboardButtons()
    }

    fun setDisplayKeys(keys: List<DisplayKey>) {
        val normalized = OverlayKeyboard.normalizedDisplayKeys(keys)
        if (normalized == displayKeys) return
        releaseKeyboard()
        displayKeys = normalized
        displayKeyLayer.removeAllViews()
        displayKeyButtons.clear()
        normalized.forEachIndexed { index, key ->
            val label = OverlayKeyboard.label(key.keyCode)
            val button = textButton(label, "Hold $label key; arrange to move").apply {
                textSize = 12f
                background = displayKeyBackground(false)
                isActivated = arrangingDisplayKeys
            }
            installKeyboardButton(button, 1000 + index)
            displayKeyButtons += button
            displayKeyLayer.addView(button, FrameLayout.LayoutParams(dp(52), dp(52)))
        }
        positionDisplayKeys()
        updateKeyboardButtons()
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
        updateKeyboardButtons()
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
        keyboardStrip.setBackgroundColor(surfaceContainer)
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

    private fun buildKeyboardStrip() {
        keyboardStrip.orientation = LinearLayout.VERTICAL
        keyboardStrip.isMotionEventSplittingEnabled = true
        keyboardStrip.setBackgroundColor(surfaceContainer)
        keyboardStrip.setPadding(dp(4), dp(2), dp(4), dp(2))
        repeat(2) { rowIndex ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isMotionEventSplittingEnabled = true
            }
            repeat(6) { columnIndex ->
                val slot = rowIndex * 6 + columnIndex
                val label = OverlayKeyboard.label(keyboardBindings[slot])
                val button = textButton(label, "Hold $label key").apply {
                    textSize = 12f
                    background = stripButtonBackground(false)
                }
                installKeyboardButton(button, slot)
                keyboardButtons += button
                row.addView(button, LinearLayout.LayoutParams(0, dp(36), 1f))
            }
            keyboardStrip.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))
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
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0f
        var startY = 0f
        view.setOnClickListener {
            if (handledTouchClick) {
                handledTouchClick = false
            } else if (displayView.isRendererAttached) {
                val keyCode = bindingForSlot(slot)
                modifierTaps.clear()
                keyboardState.press(touchOwner(slot), keyCode)
                keyboardState.release(touchOwner(slot))
                updateKeyboardButtons()
            }
        }
        view.setOnLongClickListener {
            if (!displayView.isRendererAttached || !OverlayKeyboard.isModifier(bindingForSlot(slot))) {
                false
            } else {
                modifierTaps.clear()
                toggleLatchedKey(slot)
                updateKeyboardButtons()
                true
            }
        }
        view.setOnTouchListener { target, event ->
            if (slot >= 1000 && arrangingDisplayKeys) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startX = target.x
                        startY = target.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        target.x = (startX + event.rawX - startRawX)
                            .coerceIn(0f, (displayKeyLayer.width - target.width).coerceAtLeast(0).toFloat())
                        target.y = (startY + event.rawY - startRawY)
                            .coerceIn(0f, (displayKeyLayer.height - target.height).coerceAtLeast(0).toFloat())
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val index = slot - 1000
                        val moved = displayKeys.toMutableList()
                        moved[index] = moved[index].copy(
                            x = target.x / (displayKeyLayer.width - target.width).coerceAtLeast(1),
                            y = target.y / (displayKeyLayer.height - target.height).coerceAtLeast(1),
                        )
                        displayKeys = moved
                        onDisplayKeysMoved?.invoke(moved)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        positionDisplayKeys()
                        true
                    }
                    else -> true
                }
            } else {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!displayView.isRendererAttached) return@setOnTouchListener true
                        modifierTaps.cancelUnless(slot)
                        if (heldKeyboardSlots.isNotEmpty()) {
                            chordUsedSlots.addAll(heldKeyboardSlots)
                            chordUsedSlots.add(slot)
                            modifierTaps.clear()
                        }
                        heldKeyboardSlots.add(slot)
                        downTime = event.eventTime
                        target.isPressed = true
                        keyboardState.press(touchOwner(slot), bindingForSlot(slot))
                        updateKeyboardButtons()
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        target.isPressed = false
                        val keyCode = bindingForSlot(slot)
                        if (isQuickModifierTap(
                                isHeld = slot in heldKeyboardSlots,
                                isModifier = OverlayKeyboard.isModifier(keyCode),
                                usedInChord = slot in chordUsedSlots,
                                heldMillis = event.eventTime - downTime,
                                tapThresholdMillis = ViewConfiguration.getLongPressTimeout().toLong(),
                            )) {
                            if (keyboardState.isPressed(latchOwner(slot))) {
                                toggleLatchedKey(slot)
                                modifierTaps.clear()
                            } else if (modifierTaps.tapped(slot, event.eventTime)) {
                                toggleLatchedKey(slot)
                            }
                        } else {
                            modifierTaps.clear()
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
                        modifierTaps.clear()
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
    }

    private fun bindingForSlot(slot: Int): Int =
        if (slot >= 1000) displayKeys[slot - 1000].keyCode else keyboardBindings[slot]

    private fun positionDisplayKeys() {
        displayKeyButtons.forEachIndexed { index, button ->
            button.x = displayKeys[index].x * (displayKeyLayer.width - dp(52)).coerceAtLeast(0)
            button.y = displayKeys[index].y * (displayKeyLayer.height - dp(52)).coerceAtLeast(0)
        }
    }

    private fun toggleLatchedKey(slot: Int) {
        val owner = latchOwner(slot)
        if (keyboardState.isPressed(owner)) {
            keyboardState.release(owner)
        } else {
            keyboardState.press(owner, bindingForSlot(slot))
        }
    }

    private fun markKeyboardChordUsed() {
        chordUsedSlots.addAll(heldKeyboardSlots)
        modifierTaps.clear()
    }

    private fun releaseKeyboard() {
        keyboardState.releaseAll()
        heldKeyboardSlots.clear()
        chordUsedSlots.clear()
        modifierTaps.clear()
        updateKeyboardButtons()
    }

    private fun updateKeyboardButtons() {
        (keyboardButtons.mapIndexed { slot, view -> slot to view } +
            displayKeyButtons.mapIndexed { index, view -> (1000 + index) to view }).forEach { (slot, view) ->
            val active = keyboardState.isPressed(touchOwner(slot)) || keyboardState.isPressed(latchOwner(slot))
            view.isLongClickable = !arrangingDisplayKeys && OverlayKeyboard.isModifier(bindingForSlot(slot))
            if (slot >= 1000) {
                val label = OverlayKeyboard.label(bindingForSlot(slot))
                view.contentDescription = if (arrangingDisplayKeys) "Drag $label to move" else "Hold $label key"
            }
            view.background =
                if (slot >= 1000) displayKeyBackground(active) else stripButtonBackground(active)
        }
    }

    private fun stripButtonBackground(active: Boolean): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedBackground(primaryContainer, Color.TRANSPARENT, 6))
            addState(intArrayOf(), roundedBackground(if (active) primaryContainer else Color.TRANSPARENT, Color.TRANSPARENT, 6))
        }

    private fun displayKeyBackground(active: Boolean): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedBackground(primaryContainer, onSurface, 12))
            addState(
                intArrayOf(),
                roundedBackground(
                    if (active || arrangingDisplayKeys) primaryContainer else Color.argb(40, 0, 0, 0),
                    onSurface,
                    12,
                ),
            )
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

internal fun isQuickModifierTap(
    isHeld: Boolean,
    isModifier: Boolean,
    usedInChord: Boolean,
    heldMillis: Long,
    tapThresholdMillis: Long,
): Boolean =
    isHeld && isModifier && !usedInChord &&
        heldMillis < tapThresholdMillis

internal class ModifierDoubleTap(private val timeoutMillis: Long) {
    private var slot = -1
    private var time = 0L

    fun tapped(tappedSlot: Int, atMillis: Long): Boolean {
        val doubled = slot == tappedSlot && atMillis - time in 0..timeoutMillis
        if (doubled) clear() else {
            slot = tappedSlot
            time = atMillis
        }
        return doubled
    }

    fun cancelUnless(activeSlot: Int) {
        if (slot != activeSlot) clear()
    }

    fun clear() {
        slot = -1
        time = 0L
    }
}
