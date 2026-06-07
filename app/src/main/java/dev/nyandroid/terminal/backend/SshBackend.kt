package dev.nyandroid.terminal.backend

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.OutputStream
import java.security.Security
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TerminalBackend] that connects to the Android 16 Debian VM over SSH.
 *
 * Automatically reconnects when the connection drops (VM IP change, timeout,
 * network loss). The user sees status messages during reconnection.
 */
class SshBackend(
    private val context: Context,
    private val username: String = DEFAULT_USER,
) : TerminalBackend {

    private val running = AtomicBoolean(false)
    /** True when close() was explicitly called — suppresses reconnection. */
    private val closed = AtomicBoolean(false)
    private val vmConnector = VmConnector(context)
    private val keyManager = SshKeyManager(File(context.filesDir, "ssh"))

    private var sshClient: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var connectorThread: Thread? = null
    private val writeQueue = LinkedBlockingQueue<ByteArray>()

    /** Saved geometry for reconnection. */
    private var lastCols = 80
    private var lastRows = 24

    override val isRunning: Boolean get() = running.get()

    override var onOutput: ((ByteArray, Int) -> Unit)? = null
    override var onExit: ((Int) -> Unit)? = null

    override fun start(cols: Int, rows: Int) {
        check(!running.get()) { "Backend already started" }
        lastCols = cols
        lastRows = rows

        connectorThread = Thread({
            connectWithRetry(cols, rows)
        }, "ssh-connector").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Connection loop: connects, streams, and on disconnect retries
     * unless [closed] is set.
     */
    private fun connectWithRetry(cols: Int, rows: Int) {
        var attempt = 0
        while (!closed.get()) {
            attempt++
            try {
                if (attempt == 1) {
                    emit("[Connecting to Debian VM...]\r\n")
                } else {
                    emit("\r\n[Reconnecting (attempt $attempt)...]\r\n")
                }
                connectAndStream(cols, rows)
                // connectAndStream blocks until the connection drops.
                // If we get here, the connection ended.
                attempt = 0 // Reset on successful connection
            } catch (e: AuthSetupRequiredException) {
                Log.i(TAG, "SSH key not yet provisioned in VM")
                showKeySetupInstructions()
                onExit?.invoke(-1)
                return // Don't retry auth issues
            } catch (e: Exception) {
                if (closed.get()) return
                Log.e(TAG, "SSH connection failed (attempt $attempt)", e)
                emit("[Connection lost: ${e.message?.take(80)}]\r\n")
            } finally {
                disconnect()
            }

            if (closed.get()) return

            // Backoff: 2s, 4s, 8s, max 15s
            val delaySec = minOf(2L shl minOf(attempt - 1, 3), 15L)
            emit("[Retrying in ${delaySec}s...]\r\n")
            try {
                Thread.sleep(delaySec * 1000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun connectAndStream(cols: Int, rows: Int) {
        val endpoint = vmConnector.ensureVmReady()
        emit("[VM found at ${endpoint.host}. Authenticating...]\r\n")

        val client = SSHClient().apply {
            addHostKeyVerifier(PromiscuousVerifier())
            connectTimeout = CONNECT_TIMEOUT_MS
            connect(endpoint.host, endpoint.port)
            connection.keepAlive.keepAliveInterval = KEEPALIVE_INTERVAL_SEC
        }
        sshClient = client

        // Try public key auth with our generated keypair.
        val keyPair = keyManager.getOrCreateKeyPair()
        try {
            client.authPublickey(username, KeyPairWrapper(keyPair))
        } catch (e: Exception) {
            Log.w(TAG, "Public key auth failed", e)
            // Try password auth as fallback.
            try {
                client.authPassword(username, "")
            } catch (e2: Exception) {
                Log.w(TAG, "Password auth also failed", e2)
                client.disconnect()
                throw AuthSetupRequiredException()
            }
        }

        val sess = client.startSession()
        session = sess
        sess.allocatePTY("xterm-256color", cols, rows, 0, 0, Collections.emptyMap())
        val sh = sess.startShell()
        shell = sh

        running.set(true)
        lastCols = cols
        lastRows = rows
        emit("\u001b[2J\u001b[H") // Clear the status messages

        // Writer thread for this connection.
        writeQueue.clear()
        val wt = Thread({ writeLoop(sh.outputStream) }, "ssh-writer").apply {
            isDaemon = true
            start()
        }
        writerThread = wt

        // Read loop blocks until EOF or error.
        readLoop(sh)

        // Connection ended — clean up writer.
        running.set(false)
        wt.interrupt()
    }

    private fun readLoop(shell: Session.Shell) {
        val buf = ByteArray(READ_BUFFER_SIZE)
        try {
            val input = shell.inputStream
            while (running.get() && !closed.get()) {
                val n = input.read(buf)
                if (n <= 0) break
                onOutput?.invoke(buf, n)
            }
        } catch (e: Exception) {
            if (running.get() && !closed.get()) {
                Log.e(TAG, "SSH read error", e)
            }
        }
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        if (!running.get()) return
        writeQueue.offer(data.copyOfRange(offset, offset + length))
    }

    private fun writeLoop(out: OutputStream) {
        try {
            while (running.get()) {
                val data = writeQueue.take()
                out.write(data)
                out.flush()
            }
        } catch (_: InterruptedException) {
            // shutdown
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "SSH write error", e)
        }
    }

    override fun resize(cols: Int, rows: Int, pxWidth: Int, pxHeight: Int) {
        lastCols = cols
        lastRows = rows
        if (!running.get()) return
        try {
            shell?.changeWindowDimensions(cols, rows, pxWidth, pxHeight)
        } catch (e: Exception) {
            Log.w(TAG, "SSH resize failed", e)
        }
    }

    override fun close() {
        closed.set(true)
        running.set(false)
        disconnect()
        connectorThread?.interrupt()
        readerThread?.interrupt()
        writerThread?.interrupt()
    }

    private fun disconnect() {
        running.set(false)
        try { shell?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { sshClient?.disconnect() } catch (_: Exception) {}
        shell = null
        session = null
        sshClient = null
    }

    private fun emit(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        onOutput?.invoke(bytes, bytes.size)
    }

    private fun showKeySetupInstructions() {
        val pubKey = keyManager.publicKeyOpenSsh()
        val command = "mkdir -p ~/.ssh && echo '$pubKey' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"

        // Copy the command to clipboard (must run on main thread).
        Handler(Looper.getMainLooper()).post {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SSH setup", command))
        }

        emit("\u001b[2J\u001b[H") // Clear screen
        emit("\u001b[1;33m") // Bold yellow
        emit("=== SSH Key Setup Required ===\r\n")
        emit("\u001b[0m\r\n")
        emit("Nyandroid needs to register its SSH key with the Debian VM.\r\n")
        emit("Open the stock \u001b[1mLinux Terminal\u001b[0m app and paste the\r\n")
        emit("command (already copied to clipboard):\r\n\r\n")
        emit("\u001b[32m") // Green
        emit("  mkdir -p ~/.ssh && \\\r\n")
        emit("  echo '$pubKey' \\\r\n")
        emit("  >> ~/.ssh/authorized_keys && \\\r\n")
        emit("  chmod 600 ~/.ssh/authorized_keys\r\n")
        emit("\u001b[0m\r\n")
        emit("\u001b[1;36m[Copied to clipboard]\u001b[0m\r\n\r\n")
        emit("Then restart Nyandroid.\r\n")
    }

    private class AuthSetupRequiredException : Exception()

    private companion object {
        const val TAG = "SshBackend"
        const val DEFAULT_USER = "droid"
        const val READ_BUFFER_SIZE = 8192
        const val CONNECT_TIMEOUT_MS = 10_000
        const val KEEPALIVE_INTERVAL_SEC = 30

        init {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}
