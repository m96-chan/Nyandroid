package dev.nyandroid.terminal.backend

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Discovers and ensures the Android 16 Debian VM is running and reachable
 * via SSH. The stock Terminal app (`com.android.virtualization.terminal`)
 * manages the VM lifecycle; we just nudge it awake and find its IP.
 *
 * IP discovery uses Java [NetworkInterface] to find the `avf_tap_fixed`
 * bridge, then scans the /24 subnet for SSH on port 22. This avoids
 * `ip neigh` / `/proc/net/arp` which require netlink permissions that
 * `untrusted_app` does not have.
 */
class VmConnector(private val context: Context) {

    data class VmEndpoint(val host: String, val port: Int = 22)

    /**
     * Blocks until the VM is reachable on SSH, or throws on timeout.
     * Safe to call from a background thread only.
     */
    fun ensureVmReady(timeoutMs: Long = TIMEOUT_MS): VmEndpoint {
        val deadline = System.currentTimeMillis() + timeoutMs

        // Check if VM is already reachable.
        findVmSshHost()?.let { return VmEndpoint(it) }

        // VM not reachable — launch it.
        launchVm()

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            findVmSshHost()?.let { ip ->
                Log.i(TAG, "VM ready at $ip:22")
                return VmEndpoint(ip)
            }
        }

        throw VmStartupException("VM did not become reachable within ${timeoutMs / 1000}s")
    }

    private fun launchVm() {
        try {
            val intent = Intent(VM_TERMINAL_ACTION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Sent VM_TERMINAL intent")
        } catch (e: ActivityNotFoundException) {
            throw VmStartupException(
                "Stock Terminal (com.android.virtualization.terminal) not installed", e,
            )
        }
    }

    /**
     * Finds the VM's SSH host by scanning the `avf_tap_fixed` subnet.
     *
     * 1. Look up the `avf_tap_fixed` [NetworkInterface] for its IPv4 address.
     * 2. Derive the /24 subnet.
     * 3. Probe all addresses in parallel for SSH on port 22.
     */
    private fun findVmSshHost(): String? {
        val iface = try {
            NetworkInterface.getByName(AVF_TAP_INTERFACE)
        } catch (_: Exception) {
            null
        } ?: return null

        val hostAddr = iface.inetAddresses.asSequence()
            .filterIsInstance<Inet4Address>()
            .firstOrNull() ?: return null

        val hostBytes = hostAddr.address
        val results = ConcurrentLinkedQueue<String>()
        val latch = CountDownLatch(254)

        for (i in 1..254) {
            if (i == (hostBytes[3].toInt() and 0xFF)) {
                // Skip the host-side IP itself.
                latch.countDown()
                continue
            }
            val probeBytes = byteArrayOf(hostBytes[0], hostBytes[1], hostBytes[2], i.toByte())
            val ip = Inet4Address.getByAddress(probeBytes).hostAddress ?: continue

            Thread {
                try {
                    if (probeSsh(ip)) results.add(ip)
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        latch.await(SSH_SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return results.poll()
    }

    private fun probeSsh(ip: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 22), SSH_PROBE_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    class VmStartupException(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)

    private companion object {
        const val TAG = "VmConnector"
        const val VM_TERMINAL_ACTION = "android.virtualization.VM_TERMINAL"
        const val AVF_TAP_INTERFACE = "avf_tap_fixed"
        const val TIMEOUT_MS = 60_000L
        const val POLL_INTERVAL_MS = 2_000L
        const val SSH_PROBE_TIMEOUT_MS = 300
        const val SSH_SCAN_TIMEOUT_MS = 3_000L
    }
}
