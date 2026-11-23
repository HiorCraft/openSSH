package dev.hiorcraft.openssh.ssh.hostkeys

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

object Fingerprints {
    /** Kodiert einen Public Key im SSH-Wire-Format. */
    fun encode(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

    /** Fingerprint im OpenSSH-Format, z.B. `SHA256:abc...` (Base64 ohne Padding). */
    fun sha256(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encode(key))
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** Zeile wie in `authorized_keys`: `ssh-ed25519 AAAA...`. */
    fun openSshPublicKeyLine(key: PublicKey, comment: String? = null): String {
        val line = KeyType.fromKey(key).toString() + " " + Base64.getEncoder().encodeToString(encode(key))
        return if (comment.isNullOrBlank()) line else "$line $comment"
    }
}
