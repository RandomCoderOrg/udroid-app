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
import kotlin.math.roundToInt

internal class X11DesktopHostView(context: Context) : FrameLayout(context) {
    val displayView = X11DisplayView(context)

    private val density = resources.displayMetrics.density
    private val palette = LinearLayout(context)
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
    }

    fun setPaletteVisible(visible: Boolean) {
        if (!visible) releaseMouseButtons()
        palette.visibility = if (visible) View.VISIBLE else View.GONE
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
        paletteTextViews.forEach { it.setTextColor(onSurface) }
        mouseButtons.forEach { (button, view) -> updateMouseButton(view, button) }
        scrollButtons.forEach { it.background = mouseButtonBackground(true) }
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
        installPaletteDrag(header)

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

    private fun installPaletteDrag(header: View) {
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0f
        var startY = 0f
        header.setOnTouchListener { _, event ->
            if (paletteLocked) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = palette.x
                    startY = palette.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    palette.x =
                        (startX + event.rawX - startRawX)
                            .coerceIn(0f, (width - palette.width).coerceAtLeast(0).toFloat())
                    palette.y =
                        (startY + event.rawY - startRawY)
                            .coerceIn(0f, (height - palette.height).coerceAtLeast(0).toFloat())
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
            setOnClickListener { displayView.scrollMouse(amount) }
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
        releaseMouseButtons()
        super.onDetachedFromWindow()
    }
}

internal fun nextLatchedMouseButton(current: Int, tapped: Int): Int =
    if (current == tapped) InputStub.BUTTON_UNDEFINED else tapped
