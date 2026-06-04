package dev.nyandroid.terminal.backend

/**
 * Thin JNI bridge to the native pseudo-terminal helper (`libnyandroid-pty.so`).
 *
 * This object is intentionally dumb: it only marshals calls to native code.
 * Lifecycle, threading and byte buffering live in [LocalPtyBackend].
 *
 * The fully-qualified name of this class is referenced from `pty.c`
 * (`Java_dev_nyandroid_terminal_backend_Pty_*`); keep them in sync.
 */
internal object Pty {

    init {
        System.loadLibrary("nyandroid-pty")
    }

    /**
     * Forks a child attached to a new PTY and execs [exe].
     *
     * @return `[masterFd, childPid]`, or null on failure.
     */
    external fun nativeCreate(
        exe: String,
        argv: Array<String>,
        env: Array<String>,
        cols: Int,
        rows: Int,
    ): IntArray?

    /** Blocking read from the PTY master. Returns bytes read, or -1 on EOF/error. */
    external fun nativeRead(fd: Int, buf: ByteArray, offset: Int, len: Int): Int

    /** Writes [len] bytes from [buf]. Returns bytes written, or -1 on error. */
    external fun nativeWrite(fd: Int, buf: ByteArray, offset: Int, len: Int): Int

    /** Pushes a new window size to the kernel (triggers SIGWINCH in the child). */
    external fun nativeResize(fd: Int, cols: Int, rows: Int, pxWidth: Int, pxHeight: Int)

    /** Blocks until [pid] exits; returns its exit status (128+sig if signalled). */
    external fun nativeWaitFor(pid: Int): Int

    /** Closes the master fd. */
    external fun nativeClose(fd: Int)

    /** Sends [sig] to [pid] (e.g. SIGHUP on teardown). */
    external fun nativeSendSignal(pid: Int, sig: Int)
}
