package dev.nyandroid.terminal.backend

/**
 * Skeleton backend for the Android 16 Linux VM (Debian on AVF).
 *
 * **Not implemented yet** — this is the groundwork seam so the VM route can be
 * filled in without touching the emulator or renderer. It deliberately matches
 * [TerminalBackend] exactly; swapping it in is a one-line change in
 * [dev.nyandroid.terminal.view.TerminalController].
 *
 * Two viable strategies, neither needed by the PoC's [LocalPtyBackend]:
 *
 * 1. **Attach to the existing VM** (recommended): the stock Terminal app already
 *    boots a Debian guest. Connect to it over SSH / a vsock socket and stream
 *    bytes. Needs no privileged permission and no `@SystemApi`.
 *
 * 2. **Boot our own VM via AVF**: use `android.system.virtualmachine.*`
 *    (a `@SystemApi`, so compile against system stubs or reflect). Requires
 *    `MANAGE_VIRTUAL_MACHINE` (grantable on a personal device via
 *    `adb shell pm grant <pkg> android.permission.MANAGE_VIRTUAL_MACHINE`);
 *    a *custom* kernel/rootfs additionally needs `USE_CUSTOM_VIRTUAL_MACHINE`,
 *    which is restricted to debuggable/rooted builds.
 *
 * See docs/ARCHITECTURE.md for the decision record.
 */
class AvfVmBackend : TerminalBackend {

    override val isRunning: Boolean = false

    override var onOutput: ((ByteArray, Int) -> Unit)? = null
    override var onExit: ((Int) -> Unit)? = null

    override fun start(cols: Int, rows: Int): Unit =
        TODO("AVF VM backend not implemented; PoC uses LocalPtyBackend")

    override fun write(data: ByteArray, offset: Int, length: Int): Unit =
        TODO("AVF VM backend not implemented")

    override fun resize(cols: Int, rows: Int, pxWidth: Int, pxHeight: Int): Unit =
        TODO("AVF VM backend not implemented")

    override fun close() = Unit
}
