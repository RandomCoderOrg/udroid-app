package org.randomcoder.udroid.gfxstream

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.randomcoder.udroid.UdroidApplication
import org.randomcoder.udroid.runtime.InstalledRootfsResolver
import org.randomcoder.udroid.x11.X11ServerController

/** Dev-build-only deterministic AHardwareBuffer to Android Surface probe. */
class GfxstreamPresenterProbeActivity : Activity() {
    private lateinit var presenter: AhbSurfacePresenterView
    private lateinit var stats: TextView
    private var hostController: GfxstreamHostController? = null
    private var x11Controller: X11ServerController? = null
    private var x11Detail = "disabled"
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
                            if (x11Controller != null) {
                                append("\nX11: ")
                                append(x11Detail)
                            }
                        }
                    stats.postDelayed(this, 500L)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val externalProducer = intent.getBooleanExtra(EXTRA_EXTERNAL_PRODUCER, false)
        val contractTrace = intent.getBooleanExtra(EXTRA_CONTRACT_TRACE, false)
        val directSurfaceControl =
            intent.getBooleanExtra(EXTRA_DIRECT_SURFACE_CONTROL, false)
        val resourceCycleFrames = intent.getIntExtra(EXTRA_RESOURCE_CYCLE_FRAMES, 0)
        presenter =
            AhbSurfacePresenterView(
                this,
                externalProducer = externalProducer,
                contractTrace = contractTrace,
                directSurfaceControl = directSurfaceControl,
                resourceCycleFrames = resourceCycleFrames,
            )
        if (externalProducer) {
            hostController =
                GfxstreamHostController(this).also {
                    it.startAsync(
                        presenter.transportSocketFile(),
                        contractTrace = contractTrace,
                    )
                }
        }
        if (intent.getBooleanExtra(EXTRA_X11_SERVER, false)) {
            runCatching {
                val rootfs =
                    InstalledRootfsResolver.resolve(
                        this,
                        intent.getStringExtra(EXTRA_ROOTFS_NAME),
                    )
                X11ServerController(
                    this,
                    (application as UdroidApplication).journal,
                ).also { controller ->
                    x11Controller = controller
                    val socketDirectory = controller.start(rootfs, PROBE_BOOT_ID)
                    x11Detail = "starting · ${socketDirectory.absolutePath}"
                    controller.whenReady { readyDirectory ->
                        x11Detail =
                            readyDirectory?.let { "ready · ${it.absolutePath}" }
                                ?: "failed"
                    }
                }
            }.onFailure { error ->
                x11Detail = "failed · ${error.message ?: error.javaClass.simpleName}"
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
        x11Controller?.stop(PROBE_BOOT_ID)
        hostController?.close()
        presenter.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_EXTERNAL_PRODUCER = "externalProducer"
        const val EXTRA_CONTRACT_TRACE = "contractTrace"
        const val EXTRA_DIRECT_SURFACE_CONTROL = "directSurfaceControl"
        const val EXTRA_RESOURCE_CYCLE_FRAMES = "resourceCycleFrames"
        const val EXTRA_X11_SERVER = "x11Server"
        const val EXTRA_ROOTFS_NAME = "rootfsName"
        private const val PROBE_BOOT_ID = "gfxstream-x11-probe"
    }
}
