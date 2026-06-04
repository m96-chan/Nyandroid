package dev.nyandroid.terminal.render

import android.view.Surface
import dev.nyandroid.terminal.emulator.FrameSnapshot

/**
 * Abstraction over the GPU rendering API.
 *
 * The PoC ships [dev.nyandroid.terminal.render.gles.GlesGpuBackend] (OpenGL ES
 * 3.2). A Vulkan implementation can be dropped in by implementing this same
 * interface against the same [Surface]; nothing above this seam (emulator,
 * controller, view) knows or cares which API is in use. All methods are called
 * on the dedicated render thread.
 */
interface GpuBackend {

    /** A native surface is now available; create the GPU context against it. */
    fun onSurfaceCreated(surface: Surface, width: Int, height: Int)

    /** The surface was resized. */
    fun onSurfaceChanged(width: Int, height: Int)

    /** The surface went away; release the context and all GPU resources. */
    fun onSurfaceDestroyed()

    /** Draw one frame from [frame] and present it. */
    fun renderFrame(frame: FrameSnapshot)
}
