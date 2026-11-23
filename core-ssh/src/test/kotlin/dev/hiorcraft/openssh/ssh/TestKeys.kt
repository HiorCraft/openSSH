package dev.hiorcraft.openssh.ssh

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TestKeys {
    fun resource(name: String): String =
        requireNotNull(TestKeys::class.java.getResource("/keys/$name")) { "Fixture fehlt: $name" }.readText()

    /** Fingerprint direkt aus einer `.pub`-Datei berechnet (unabhängig vom Code unter Test). */
    fun fingerprintOfPubFile(name: String): String {
        val blob = Base64.getDecoder().decode(resource(name).trim().split(" ")[1])
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun rsaKeyPair(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    /** Erzeugt eine unverschlüsselte PuTTY-Schlüsseldatei (Format Version 2) inklusive MAC. */
    fun puttyV2Rsa(pair: KeyPair, comment: String = "test-putty"): String {
        val pub = pair.public as RSAPublicKey
        val priv = pair.private as RSAPrivateCrtKey
        val publicBlob = sshBytes { string("ssh-rsa".toByteArray()); mpint(pub.publicExponent); mpint(pub.modulus) }
        val privateBlob = sshBytes {
            mpint(priv.privateExponent); mpint(priv.primeP); mpint(priv.primeQ); mpint(priv.crtCoefficient)
        }
        val macKey = MessageDigest.getInstance("SHA-1").digest("putty-private-key-file-mac-key".toByteArray())
        val macData = sshBytes {
            string("ssh-rsa".toByteArray()); string("none".toByteArray()); string(comment.toByteArray())
            string(publicBlob); string(privateBlob)
        }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(macKey, "HmacSHA1")) }.doFinal(macData)
        val pubLines = Base64.getEncoder().encodeToString(publicBlob).chunked(64)
        val privLines = Base64.getEncoder().encodeToString(privateBlob).chunked(64)
        return buildString {
            appendLine("PuTTY-User-Key-File-2: ssh-rsa")
            appendLine("Encryption: none")
            appendLine("Comment: $comment")
            appendLine("Public-Lines: ${pubLines.size}")
            pubLines.forEach(::appendLine)
            appendLine("Private-Lines: ${privLines.size}")
            privLines.forEach(::appendLine)
            appendLine("Private-MAC: " + mac.joinToString("") { "%02x".format(it) })
        }
    }

    private class SshWriter {
        val out = java.io.ByteArrayOutputStream()
        fun string(bytes: ByteArray) {
            out.write(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); out.write(bytes)
        }
        fun mpint(value: BigInteger) = string(value.toByteArray())
    }

    private fun sshBytes(block: SshWriter.() -> Unit): ByteArray = SshWriter().apply(block).out.toByteArray()
}
