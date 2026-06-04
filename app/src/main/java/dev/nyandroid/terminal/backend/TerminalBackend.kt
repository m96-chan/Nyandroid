package dev.nyandroid.terminal.backend

/**
 * Source/sink of raw terminal bytes.
 *
 * This is the seam that lets Nyandroid target multiple "what actually runs"
 * strategies without touching the emulator or renderer:
 *
 *  - [LocalPtyBackend] — fork a shell in a local PTY (the PoC default; works
 *    on a stock, non-privileged app, Termux-style).
 *  - A future `SshBackend` — attach to a remote host.
 *  - A future `AvfVmBackend` — bridge into the Android 16 Linux VM (Debian on
 *    AVF). That backend needs privileged access (`MANAGE_VIRTUAL_MACHINE`)
 *    and is out of scope for the unprivileged PoC, but the interface is
 *    deliberately shaped so it can slot in later.
 *
 * Output is delivered on a background thread via [onOutput]; callers must not
 * assume any particular thread and should hand off to the emulator safely.
 */
interface TerminalBackend {

    /** True between a successful [start] and process exit / [close]. */
    val isRunning: Boolean

    /**
     * Invoked from a backend-owned reader thread with a buffer and the number
     * of valid bytes in it. The buffer is reused after the callback returns,
     * so implementations must consume (or copy) it synchronously.
     */
    var onOutput: ((buffer: ByteArray, length: Int) -> Unit)?

    /** Invoked once when the underlying process exits, with its status code. */
    var onExit: ((status: Int) -> Unit)?

    /** Spawns the process with the given initial terminal size. */
    fun start(cols: Int, rows: Int)

    /** Writes user input (keystrokes, paste, etc.) toward the process. */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size)

    /** Reports a new terminal geometry. Pixel dims are best-effort hints. */
    fun resize(cols: Int, rows: Int, pxWidth: Int = 0, pxHeight: Int = 0)

    /** Tears the backend down and releases all resources. */
    fun close()
}
