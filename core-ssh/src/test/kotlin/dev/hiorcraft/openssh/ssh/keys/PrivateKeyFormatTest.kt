package dev.hiorcraft.openssh.ssh.keys

import dev.hiorcraft.openssh.ssh.TestKeys
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivateKeyFormatTest {

    @Test
    fun `erkennt Formate anhand des Headers`() {
        assertEquals(PrivateKeyFormat.OPENSSH, PrivateKeyFormat.detect(TestKeys.resource("id_ed25519")))
        assertEquals(PrivateKeyFormat.PEM, PrivateKeyFormat.detect(TestKeys.resource("id_rsa_pem")))
        assertEquals(PrivateKeyFormat.PKCS8, PrivateKeyFormat.detect("-----BEGIN PRIVATE KEY-----\nAAAA\n"))
        assertEquals(PrivateKeyFormat.PUTTY, PrivateKeyFormat.detect("PuTTY-User-Key-File-3: ssh-ed25519\n"))
        assertEquals(PrivateKeyFormat.UNKNOWN, PrivateKeyFormat.detect("ssh-ed25519 AAAA... public"))
    }

    @Test
    fun `liest den Cipher aus OpenSSH-Schluesseln`() {
        assertEquals("none", PrivateKeyFormat.openSshCipher(TestKeys.resource("id_ed25519")))
        assertEquals("aes256-ctr", PrivateKeyFormat.openSshCipher(TestKeys.resource("id_ed25519_enc")))
    }

    @Test
    fun `erkennt Verschluesselung in allen Formaten`() {
        assertTrue(PrivateKeyFormat.isEncrypted("PuTTY-User-Key-File-2: ssh-rsa\nEncryption: aes256-cbc\n"))
        assertFalse(PrivateKeyFormat.isEncrypted("PuTTY-User-Key-File-2: ssh-rsa\nEncryption: none\n"))
        assertTrue(PrivateKeyFormat.isEncrypted("-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n"))
        assertTrue(
            PrivateKeyFormat.isEncrypted("-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES\n"),
        )
        assertFalse(PrivateKeyFormat.isEncrypted(TestKeys.resource("id_rsa_pem")))
    }
}
