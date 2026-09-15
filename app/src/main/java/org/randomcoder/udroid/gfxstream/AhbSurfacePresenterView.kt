package org.randomcoder.udroid.gfxstream

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.Keep
import java.io.File

/**
 * Android Surface boundary for the optional gfxstream renderer.
 *
 * The production class is intentionally dormant until a selected graphics
 * profile owns the display. The dev-only probe Activity exercises it without
 * changing the existing Termux:X11 desktop path.
 */
@Keep
class AhbSurfacePresenterView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        externalProducer: Boolean = false,
        contractTrace: Boolean = false,
        directSurfaceControl: Boolean = false,
        resourceCycleFrames: Int = 0,
    ) : SurfaceView(context, attrs), SurfaceHolder.Callback, AutoCloseable {
        private val usesExternalProducer = externalProducer
        private val transportSocket =
            File(context.noBackupFilesDir, "graphics/ahb-presenter.sock").also {
                it.parentFile?.let { directory ->
                    check(directory.isDirectory || directory.mkdirs()) {
                        "Could not create the private graphics transport directory"
                    }
                }
            }
        private var nativeHandle: Long =
            nativeCreate(
                transportSocket.absolutePath,
                externalProducer,
                contractTrace,
                directSurfaceControl,
                resourceCycleFrames.coerceAtLeast(0),
            )
        private var frameCallbackPosted = false
        private val frameCallback =
            object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    frameCallbackPosted = false
                    val handle = nativeHandle
                    if (handle == 0L || !holder.surface.isValid) return
                    nativeDoFrame(handle, frameTimeNanos)
                    startFrameCallbacks()
                }
            }

        init {
            holder.addCallback(this)
            keepScreenOn = true
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // The surface must be valid before declaring its cadence.
                holder.surface.setFrameRate(
                    60f,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                )
            }
            attach(holder.surface)
            startFrameCallbacks()
        }

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            if (nativeHandle != 0L && holder.surface.isValid) {
                nativeSurfaceResized(nativeHandle)
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            stopFrameCallbacks()
            if (nativeHandle != 0L) nativeSetSurface(nativeHandle, null)
        }

        fun presenterStats(): String =
            if (nativeHandle == 0L) {
                "presenter stopped"
            } else {
                nativeGetStats(nativeHandle)
            }

        internal fun transportSocketFile(): File = transportSocket

        override fun close() {
            val handle = nativeHandle
            if (handle == 0L) return
            stopFrameCallbacks()
            holder.removeCallback(this)
            nativeSetSurface(handle, null)
            nativeDestroy(handle)
            nativeHandle = 0L
        }

        private fun attach(surface: Surface) {
            if (nativeHandle != 0L && surface.isValid) {
                nativeSetSurface(nativeHandle, surface)
            }
        }

        private fun startFrameCallbacks() {
            if (usesExternalProducer || frameCallbackPosted || nativeHandle == 0L) return
            frameCallbackPosted = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        private fun stopFrameCallbacks() {
            if (!frameCallbackPosted) return
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameCallbackPosted = false
        }

        private external fun nativeCreate(
            socketPath: String,
            externalProducer: Boolean,
            contractTrace: Boolean,
            directSurfaceControl: Boolean,
            resourceCycleFrames: Int,
        ): Long

        private external fun nativeSetSurface(
            handle: Long,
            surface: Surface?,
        )

        private external fun nativeSurfaceResized(handle: Long)

        private external fun nativeDoFrame(
            handle: Long,
            frameTimeNanos: Long,
        )

        private external fun nativeGetStats(handle: Long): String

        private external fun nativeDestroy(handle: Long)

        companion object {
            init {
                System.loadLibrary("udroid_ahb_presenter")
            }
        }
    }
