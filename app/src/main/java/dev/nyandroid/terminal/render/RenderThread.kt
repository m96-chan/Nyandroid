package dev.nyandroid.terminal.render

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import dev.nyandroid.terminal.emulator.FrameSnapshot
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the GPU context thread. All [GpuBackend] calls happen here, off the UI
 * and PTY threads. Render requests are coalesced so a burst of output produces
 * at most one queued frame.
 *
 * @param gpu the backend to drive (GLES today, Vulkan-swappable later).
 * @param frameSource fills a [FrameSnapshot] with the latest screen. Called on
 *   the render thread; must be safe to call concurrently with producers.
 */
class RenderThread(
    private val gpu: GpuBackend,
    private val frameSource: (FrameSnapshot) -> Unit,
) {
    private val thread = HandlerThread("nyandroid-render")
    private lateinit var handler: Handler
    private val frame = FrameSnapshot()
    private val renderPending = AtomicBoolean(false)
    private var surfaceReady = false
    private var lastRenderedRevision = -1L

    fun start() {
        thread.start()
        handler = Handler(thread.looper)
    }

    fun onSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        handler.post {
            try {
                if (!surface.isValid) {
                    Log.w(TAG, "Surface already invalid, skipping EGL init")
                    return@post
                }
                gpu.onSurfaceCreated(surface, width, height)
                surfaceReady = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create GPU surface", e)
                surfaceReady = false
            }
        }
        requestRender()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        handler.post {
            if (surfaceReady) gpu.onSurfaceChanged(width, height)
        }
        requestRender()
    }

    fun onSurfaceDestroyed() {
        handler.post {
            if (surfaceReady) {
                gpu.onSurfaceDestroyed()
                surfaceReady = false
            }
        }
    }

    fun requestRender() {
        if (renderPending.compareAndSet(false, true)) {
            handler.post {
                renderPending.set(false)
                if (!surfaceReady) return@post
                frameSource(frame)
                // Skip rendering if frame hasn't changed (unless cursor blinks).
                if (frame.revision == lastRenderedRevision) return@post
                lastRenderedRevision = frame.revision
                gpu.renderFrame(frame)
                // Schedule next blink redraw if cursor blinks.
                scheduleBlink()
            }
        }
    }

    private var blinkScheduled = false

    private fun scheduleBlink() {
        if (blinkScheduled) return
        blinkScheduled = true
        handler.postDelayed({
            blinkScheduled = false
            if (surfaceReady) {
                frameSource(frame)
                gpu.renderFrame(frame)
                scheduleBlink()
            }
        }, CURSOR_BLINK_MS)
    }

    fun quit() {
        handler.post {
            if (surfaceReady) gpu.onSurfaceDestroyed()
            surfaceReady = false
        }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "RenderThread"
        const val CURSOR_BLINK_MS = 530L
    }
}
