package org.randomcoder.udroid.gfxstream

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/** Dev-build-only deterministic AHardwareBuffer to Android Surface probe. */
class GfxstreamPresenterProbeActivity : Activity() {
    private lateinit var presenter: AhbSurfacePresenterView
    private lateinit var stats: TextView
    private var hostController: GfxstreamHostController? = null
    private val refreshStats =
        object : Runnable {
            override fun run() {
                if (!isFinishing) {
                    stats.text =
                        buildString {
                            append(presenter.presenterStats())
                            hostController?.current()?.let { host ->
                                append("\nKumquat: ")
                                append(host.state)
                                append(" · ")
                                append(host.detail)
                            }
                        }
                    stats.postDelayed(this, 500L)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val externalProducer = intent.getBooleanExtra(EXTRA_EXTERNAL_PRODUCER, false)
        presenter =
            AhbSurfacePresenterView(
                this,
                externalProducer = externalProducer,
            )
        if (externalProducer) {
            hostController =
                GfxstreamHostController(this).also {
                    it.startAsync(presenter.transportSocketFile())
                }
        }
        stats =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0xB0000000.toInt())
                textSize = 13f
                setPadding(20, 12, 20, 12)
                typeface = android.graphics.Typeface.MONOSPACE
            }
        val root = FrameLayout(this)
        root.addView(
            presenter,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            stats,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        setContentView(root)
        stats.post(refreshStats)
    }

    override fun onDestroy() {
        stats.removeCallbacks(refreshStats)
        hostController?.close()
        presenter.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_EXTERNAL_PRODUCER = "externalProducer"
    }
}
