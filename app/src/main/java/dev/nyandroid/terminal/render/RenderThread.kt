package dev.nyandroid.terminal.render

import android.os.Handler
import android.os.HandlerThread
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

    fun start() {
        thread.start()
        handler = Handler(thread.looper)
    }

    fun onSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        handler.post { gpu.onSurfaceCreated(surface, width, height) }
        requestRender()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        handler.post { gpu.onSurfaceChanged(width, height) }
        requestRender()
    }

    fun onSurfaceDestroyed() {
        handler.post { gpu.onSurfaceDestroyed() }
    }

    fun requestRender() {
        if (renderPending.compareAndSet(false, true)) {
            handler.post {
                renderPending.set(false)
                frameSource(frame)
                gpu.renderFrame(frame)
            }
        }
    }

    fun quit() {
        handler.post { gpu.onSurfaceDestroyed() }
        thread.quitSafely()
    }
}
