package dev.hiorcraft.openssh.sftp

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemotePathsTest {

    @Test
    fun normalize() {
        assertEquals("/", RemotePaths.normalize("/"))
        assertEquals("/a/b", RemotePaths.normalize("//a///b/"))
        assertEquals("/a/c", RemotePaths.normalize("/a/b/../c/."))
        assertEquals("/", RemotePaths.normalize("/../.."))
        assertEquals("../x", RemotePaths.normalize("../x"))
        assertEquals(".", RemotePaths.normalize(""))
    }

    @Test
    fun `join, parent und name`() {
        assertEquals("/home/user/file.txt", RemotePaths.join("/home/user", "file.txt"))
        assertEquals("/home/user/file.txt", RemotePaths.join("/home/user/", "file.txt"))
        assertEquals("/etc/hosts", RemotePaths.join("/home/user", "/etc/hosts"))
        assertEquals("/home", RemotePaths.parent("/home/user"))
        assertEquals("/", RemotePaths.parent("/home"))
        assertEquals("/", RemotePaths.parent("/"))
        assertEquals("user", RemotePaths.name("/home/user/"))
    }

    @Test
    fun breadcrumbs() {
        assertEquals(
            listOf("/" to "/", "var" to "/var", "log" to "/var/log"),
            RemotePaths.breadcrumbs("/var/log/"),
        )
    }

    @Test
    fun `Rechte im ls-Stil`() {
        val dir = RemoteEntry("/x", "x", EntryType.DIRECTORY, 0, Instant.EPOCH, "755".toInt(8))
        assertEquals("drwxr-xr-x", dir.permissionString)
        val file = dir.copy(type = EntryType.FILE, mode = "640".toInt(8))
        assertEquals("-rw-r-----", file.permissionString)
        assertTrue(dir.isDirectory)
        assertFalse(file.isDirectory)
        assertTrue(file.copy(type = EntryType.SYMLINK, linkTargetIsDirectory = true).isDirectory)
    }

    @Test
    fun `Windows-sichere lokale Dateinamen`() {
        assertEquals("a_b_c.txt", SftpBrowser.safeLocalName("a:b?c.txt"))
        assertEquals("_CON.log", SftpBrowser.safeLocalName("CON.log"))
        assertEquals("name", SftpBrowser.safeLocalName("name. "))
        assertEquals("normal.txt", SftpBrowser.safeLocalName("normal.txt"))
    }
}
