package dev.nyandroid.terminal.backend

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TerminalBackend] that runs a shell in a local pseudo-terminal via the
 * native [Pty] helper.
 *
 * On a stock Android device a non-system app can exec the platform shell
 * (`/system/bin/sh`). That is enough to prove out the GPU terminal pipeline.
 * A richer userland (coreutils, package manager, etc.) would come from
 * bundling a rootfs — that is a backend concern and does not change anything
 * above this class.
 *
 * @param shell absolute path of the program to exec.
 * @param extraEnv additional `KEY=VALUE` environment entries.
 */
class LocalPtyBackend(
    private val shell: String = "/system/bin/sh",
    private val extraEnv: List<String> = emptyList(),
) : TerminalBackend {

    private val running = AtomicBoolean(false)
    private var fd: Int = -1
    private var pid: Int = -1
    private var readerThread: Thread? = null

    override val isRunning: Boolean get() = running.get()

    override var onOutput: ((ByteArray, Int) -> Unit)? = null
    override var onExit: ((Int) -> Unit)? = null

    override fun start(cols: Int, rows: Int) {
        check(!running.get()) { "Backend already started" }

        val env = buildList {
            add("TERM=xterm-256color")
            add("HOME=/data/local/tmp")
            add("PATH=/system/bin:/system/xbin")
            add("LANG=en_US.UTF-8")
            addAll(extraEnv)
        }.toTypedArray()

        val result = Pty.nativeCreate(shell, arrayOf(shell, "-l"), env, cols, rows)
            ?: error("Failed to spawn PTY for $shell")
        fd = result[0]
        pid = result[1]
        running.set(true)

        readerThread = Thread({ readLoop() }, "pty-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop() {
        val buf = ByteArray(READ_BUFFER_SIZE)
        try {
            while (running.get()) {
                val n = Pty.nativeRead(fd, buf, 0, buf.size)
                if (n <= 0) break
                onOutput?.invoke(buf, n)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "PTY read loop crashed", t)
        } finally {
            val status = if (pid > 0) Pty.nativeWaitFor(pid) else -1
            running.set(false)
            onExit?.invoke(status)
        }
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        if (!running.get()) return
        Pty.nativeWrite(fd, data, offset, length)
    }

    override fun resize(cols: Int, rows: Int, pxWidth: Int, pxHeight: Int) {
        if (!running.get()) return
        Pty.nativeResize(fd, cols, rows, pxWidth, pxHeight)
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        if (pid > 0) Pty.nativeSendSignal(pid, SIGHUP)
        if (fd >= 0) Pty.nativeClose(fd)
        readerThread?.interrupt()
    }

    private companion object {
        const val TAG = "LocalPtyBackend"
        const val READ_BUFFER_SIZE = 8192
        const val SIGHUP = 1
    }
}
