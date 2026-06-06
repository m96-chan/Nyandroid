package dev.nyandroid.terminal.backend

import android.util.Base64
import android.util.Log
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Manages an Ed25519 keypair for SSH authentication to the Debian VM.
 *
 * On first use, generates a keypair and persists it in [sshDir]. On
 * subsequent calls, loads the existing keypair. The public key can be
 * exported in OpenSSH `authorized_keys` format for provisioning.
 */
class SshKeyManager(private val sshDir: File) {

    private var cachedKeyPair: KeyPair? = null

    fun getOrCreateKeyPair(): KeyPair {
        cachedKeyPair?.let { return it }

        val privFile = File(sshDir, "id_ed25519")
        val pubFile = File(sshDir, "id_ed25519.pub")

        if (privFile.exists() && pubFile.exists()) {
            try {
                val kp = loadKeyPair(privFile, pubFile)
                cachedKeyPair = kp
                return kp
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load existing keypair, regenerating", e)
            }
        }

        val kp = generateKeyPair()
        saveKeyPair(kp, privFile, pubFile)
        cachedKeyPair = kp
        return kp
    }

    /**
     * Returns the public key in OpenSSH `authorized_keys` format.
     * Uses sshj's [Buffer] to produce the correct SSH wire encoding.
     */
    fun publicKeyOpenSsh(): String {
        val kp = getOrCreateKeyPair()
        val keyType = KeyType.fromKey(kp.public)
        val buf = Buffer.PlainBuffer()
        buf.putPublicKey(kp.public)
        val wireBytes = buf.compactData
        val b64 = Base64.encodeToString(wireBytes, Base64.NO_WRAP)
        return "${keyType.toString()} $b64 nyandroid"
    }

    private fun generateKeyPair(): KeyPair {
        // Must use BouncyCastle provider — sshj recognizes BC's EdDSA key types
        // but not Java 17's standard EdECPublicKey/EdECPrivateKey.
        val kpg = KeyPairGenerator.getInstance("Ed25519", "BC")
        return kpg.generateKeyPair().also {
            Log.i(TAG, "Generated new Ed25519 keypair (BC provider)")
        }
    }

    private fun saveKeyPair(kp: KeyPair, privFile: File, pubFile: File) {
        sshDir.mkdirs()
        privFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        privFile.setReadable(false, false)
        privFile.setReadable(true, true)
        privFile.setWritable(false, false)
        privFile.setWritable(true, true)
    }

    private fun loadKeyPair(privFile: File, pubFile: File): KeyPair {
        val kf = KeyFactory.getInstance("Ed25519", "BC")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
        val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
        return KeyPair(pub, priv)
    }

    private companion object {
        const val TAG = "SshKeyManager"
    }
}
