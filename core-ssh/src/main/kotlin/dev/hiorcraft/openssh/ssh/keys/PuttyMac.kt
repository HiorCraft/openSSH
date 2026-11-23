package dev.hiorcraft.openssh.ssh.keys

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Prüft den `Private-MAC` unverschlüsselter PuTTY-Schlüssel (Version 2 und 3).
 * sshj verifiziert ihn in diesem Fall nicht, eine manipulierte Datei würde sonst unbemerkt geladen.
 */
internal object PuttyMac {

    /** `true`, wenn der MAC stimmt oder die Datei verschlüsselt ist (dann prüft die Entschlüsselung). */
    fun verifyUnencrypted(text: String): Boolean {
        val lines = text.lines().map { it.trimEnd('\r') }
        val header = lines.firstOrNull() ?: return false
        val version = header.substringAfter("PuTTY-User-Key-File-").substringBefore(':').toIntOrNull() ?: return false
        val algorithm = header.substringAfter(": ").trim()
        val headers = mutableMapOf<String, String>()
        val blocks = mutableMapOf<String, ByteArray>()
        var i = 1
        while (i < lines.size) {
            val line = lines[i]
            val key = line.substringBefore(": ", missingDelimiterValue = "")
            val value = line.substringAfter(": ", missingDelimiterValue = "")
            if (key.endsWith("-Lines")) {
                val count = value.trim().toInt()
                val data = lines.subList(i + 1, i + 1 + count).joinToString("")
                blocks[key.removeSuffix("-Lines")] = Base64.getDecoder().decode(data)
                i += count + 1
                continue
            }
            if (key.isNotEmpty()) headers[key] = value
            i++
        }
        val encryption = headers["Encryption"] ?: return false
        if (encryption != "none") return true
        val expected = headers["Private-MAC"]?.trim()?.lowercase() ?: return false
        val publicBlob = blocks["Public"] ?: return false
        val privateBlob = blocks["Private"] ?: return false

        val data = ByteArrayOutputStream().apply {
            for (part in listOf(algorithm.toByteArray(), encryption.toByteArray(), (headers["Comment"] ?: "").toByteArray(), publicBlob, privateBlob)) {
                write(ByteBuffer.allocate(4).putInt(part.size).array())
                write(part)
            }
        }.toByteArray()

        val actual = when (version) {
            2 -> hmac("HmacSHA1", MessageDigest.getInstance("SHA-1").digest("putty-private-key-file-mac-key".toByteArray()), data)
            3 -> hmac("HmacSHA256", ByteArray(0), data)
            else -> return false
        }
        return MessageDigest.isEqual(actual.joinToString("") { "%02x".format(it) }.toByteArray(), expected.toByteArray())
    }

    private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        // Ein leerer Schlüssel ist für HMAC erlaubt, SecretKeySpec akzeptiert ihn aber nicht
        mac.init(if (key.isEmpty()) EmptyKey(algorithm) else SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

    private class EmptyKey(private val alg: String) : javax.crypto.SecretKey {
        override fun getAlgorithm() = alg
        override fun getFormat() = "RAW"
        override fun getEncoded() = ByteArray(0)
    }
}
