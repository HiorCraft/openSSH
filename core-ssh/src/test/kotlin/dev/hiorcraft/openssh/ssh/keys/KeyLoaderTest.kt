package dev.hiorcraft.openssh.ssh.keys

import dev.hiorcraft.openssh.ssh.InvalidPassphraseException
import dev.hiorcraft.openssh.ssh.SshException
import dev.hiorcraft.openssh.ssh.TestKeys
import dev.hiorcraft.openssh.ssh.hostkeys.Fingerprints
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyLoaderTest {

    @ParameterizedTest
    @CsvSource(
        "id_ed25519, OPENSSH, ssh-ed25519",
        "id_rsa_pem, PEM, ssh-rsa",
        "id_ecdsa, OPENSSH, ecdsa-sha2-nistp256",
    )
    fun `laedt unverschluesselte Schluessel und berechnet den Fingerprint wie ssh-keygen`(
        file: String, format: PrivateKeyFormat, algorithm: String,
    ) {
        val info = KeyLoader.inspect(TestKeys.resource(file))
        assertEquals(format, info.format)
        assertEquals(algorithm, info.algorithm)
        assertEquals(TestKeys.fingerprintOfPubFile("$file.pub"), info.fingerprint)
        assertFalse(info.encrypted)
        assertEquals(TestKeys.resource("$file.pub").trim().split(" ").take(2).joinToString(" "), info.publicKeyLine)
    }

    @Test
    fun `fingerprint stimmt exakt mit ssh-keygen ueberein`() {
        val info = KeyLoader.inspect(TestKeys.resource("id_ed25519"))
        assertEquals("SHA256:Tkjt13gQQl8obYJ3LaQRizoJcLyrWAMsZ/rcPQHzrGg", info.fingerprint)
    }

    @Test
    fun `verschluesselter Schluessel mit richtiger Passphrase`() {
        val text = TestKeys.resource("id_ed25519_enc")
        assertTrue(PrivateKeyFormat.isEncrypted(text))
        val info = KeyLoader.inspect(text, "geheim123".toCharArray())
        assertEquals(TestKeys.fingerprintOfPubFile("id_ed25519_enc.pub"), info.fingerprint)
        assertTrue(info.encrypted)
    }

    @Test
    fun `verschluesselter Schluessel ohne oder mit falscher Passphrase schlaegt fehl`() {
        val text = TestKeys.resource("id_ed25519_enc")
        assertFailsWith<InvalidPassphraseException> { KeyLoader.load(text) }
        assertFailsWith<InvalidPassphraseException> { KeyLoader.load(text, "falsch".toCharArray()) }
    }

    @Test
    fun `PuTTY ppk wird geladen`() {
        val pair = TestKeys.rsaKeyPair()
        val ppk = TestKeys.puttyV2Rsa(pair)
        assertEquals(PrivateKeyFormat.PUTTY, PrivateKeyFormat.detect(ppk))
        assertFalse(PrivateKeyFormat.isEncrypted(ppk))
        val provider = KeyLoader.load(ppk)
        assertEquals(Fingerprints.sha256(pair.public), Fingerprints.sha256(provider.public))
        val expected = pair.private as java.security.interfaces.RSAPrivateKey
        val loaded = provider.private as java.security.interfaces.RSAPrivateKey
        assertEquals(expected.modulus, loaded.modulus)
        assertEquals(expected.privateExponent, loaded.privateExponent)
    }

    @Test
    fun `PuTTY ppk mit manipuliertem MAC wird abgelehnt`() {
        val ppk = TestKeys.puttyV2Rsa(TestKeys.rsaKeyPair())
        val tampered = ppk.replace(Regex("Private-MAC: ([0-9a-f])")) {
            "Private-MAC: " + if (it.groupValues[1] == "0") "1" else "0"
        }
        assertFailsWith<SshException> { KeyLoader.load(tampered) }
    }

    @Test
    fun `unbekanntes Format wird klar gemeldet`() {
        val e = assertFailsWith<SshException> { KeyLoader.load("das ist kein schluessel") }
        assertTrue(e.message!!.contains("Unbekanntes Schlüsselformat"))
    }
}
