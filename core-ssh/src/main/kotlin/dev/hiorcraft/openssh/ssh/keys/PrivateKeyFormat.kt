package dev.hiorcraft.openssh.ssh.keys

import java.nio.ByteBuffer
import java.util.Base64

/** Unterstützte Formate privater Schlüssel. */
enum class PrivateKeyFormat(val displayName: String) {
    OPENSSH("OpenSSH"),
    PEM("PEM (traditionell)"),
    PKCS8("PKCS#8"),
    PUTTY("PuTTY (.ppk)"),
    UNKNOWN("Unbekannt");

    companion object {
        private val pemHeader = Regex("^-----BEGIN (RSA|DSA|EC) PRIVATE KEY-----")
        private val puttyEncryption = Regex("^Encryption:\\s*(\\S+)", RegexOption.MULTILINE)

        fun detect(text: String): PrivateKeyFormat {
            val t = text.trimStart()
            return when {
                t.startsWith("PuTTY-User-Key-File-") -> PUTTY
                t.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----") -> OPENSSH
                t.startsWith("-----BEGIN PRIVATE KEY-----") ||
                    t.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----") -> PKCS8
                pemHeader.containsMatchIn(t) -> PEM
                else -> UNKNOWN
            }
        }

        /** Prüft, ob der Schlüssel durch eine Passphrase geschützt ist. */
        fun isEncrypted(text: String): Boolean = when (detect(text)) {
            PUTTY -> puttyEncryption.find(text)?.groupValues?.get(1)?.let { it != "none" } ?: false
            PKCS8 -> text.contains("BEGIN ENCRYPTED PRIVATE KEY")
            PEM -> text.contains("Proc-Type: 4,ENCRYPTED")
            OPENSSH -> openSshCipher(text) != "none"
            UNKNOWN -> false
        }

        /** Liest den Cipher-Namen aus dem Header eines `openssh-key-v1`-Schlüssels. */
        internal fun openSshCipher(text: String): String {
            val body = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("-----") }
                .joinToString("")
            val bytes = Base64.getDecoder().decode(body)
            val magic = "openssh-key-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
            require(bytes.size > magic.size && bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
                "Kein gültiger OpenSSH-Schlüssel"
            }
            val buf = ByteBuffer.wrap(bytes, magic.size, bytes.size - magic.size)
            val len = buf.int
            require(len in 1..64) { "Ungültiger Cipher-Name im Schlüssel" }
            val name = ByteArray(len).also { buf.get(it) }
            return String(name, Charsets.US_ASCII)
        }
    }
}
