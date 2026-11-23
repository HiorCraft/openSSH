package dev.hiorcraft.openssh.db.redis

import dev.hiorcraft.openssh.db.DatabaseException
import dev.hiorcraft.openssh.ssh.ConnectionSpec
import dev.hiorcraft.openssh.ssh.SshAuth
import dev.hiorcraft.openssh.ssh.SshSession
import dev.hiorcraft.openssh.ssh.hostkeys.KnownHostsVerifier
import dev.hiorcraft.openssh.ssh.testing.TestSshServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RedisBrowserTest {

    private val redis = FakeRedisServer().apply {
        strings["user:1:name"] = "Alice"
        strings["user:2:name"] = "Bob"
        strings["config"] = "{\"a\":1}"
        lists["queue"] = mutableListOf("a", "b", "c")
        hashes["user:1"] = mutableMapOf("name" to "Alice", "age" to "30")
        sets["tags"] = mutableSetOf("kotlin", "ssh")
        zsets["scores"] = mutableMapOf("alice" to 3.0, "bob" to 1.5)
        ttls["config"] = 120L
    }

    @AfterEach
    fun stop() = redis.close()

    @Test
    fun `Keys suchen und Werte aller Typen anzeigen`(): Unit = runBlocking {
        RedisBrowser.connect(RedisSpec(port = redis.port)).use { b ->
            assertEquals("PONG", b.ping())
            assertEquals(7, b.dbSize())
            assertFalse(b.isTunneled)

            val users = b.searchKeys("user:*:name")
            assertEquals(listOf("user:1:name", "user:2:name"), users.keys.sorted())
            assertTrue(users.finished)

            assertEquals(RedisValue.Text("Alice"), b.inspect("user:1:name").value)
            val config = b.inspect("config")
            assertEquals(120, config.ttlSeconds)
            assertEquals("string", config.type)

            val list = assertIs<RedisValue.ListValue>(b.inspect("queue").value)
            assertEquals(listOf("a", "b", "c"), list.items)
            assertEquals(3, list.total)

            val hash = assertIs<RedisValue.HashValue>(b.inspect("user:1").value)
            assertEquals(listOf("age" to "30", "name" to "Alice"), hash.fields)

            val set = assertIs<RedisValue.SetValue>(b.inspect("tags").value)
            assertEquals(listOf("kotlin", "ssh"), set.members)

            val zset = assertIs<RedisValue.SortedSetValue>(b.inspect("scores").value)
            assertEquals(listOf("bob" to 1.5, "alice" to 3.0), zset.entries)

            val missing = b.inspect("nope")
            assertEquals(RedisValue.Missing, missing.value)
            assertEquals(-2, missing.ttlSeconds)

            assertEquals("7.2.4", b.serverInfo()["redis_version"])
        }
    }

    @Test
    fun `schreiben, Ablauf setzen und loeschen`(): Unit = runBlocking {
        RedisBrowser.connect(RedisSpec(port = redis.port)).use { b ->
            b.setString("neu", "wert")
            assertEquals("wert", redis.strings["neu"])
            assertTrue(b.expire("neu", 60))
            assertEquals(60, b.inspect("neu").ttlSeconds)
            assertTrue(b.expire("neu", -1))
            assertEquals(-1, b.inspect("neu").ttlSeconds)
            assertEquals(1, b.delete("neu"))
            assertEquals(RedisValue.Missing, b.inspect("neu").value)
        }
    }

    @Test
    fun `Passwort-Authentifizierung`(): Unit = runBlocking {
        FakeRedisServer(requiredPassword = "pw").use { secured ->
            secured.strings["k"] = "v"
            RedisBrowser.connect(RedisSpec(port = secured.port, password = "pw")).use { b ->
                assertEquals(RedisValue.Text("v"), b.inspect("k").value)
            }
            assertFailsWith<DatabaseException> {
                RedisBrowser.connect(RedisSpec(port = secured.port, password = "falsch")).use { it.ping() }
            }
        }
    }

    @Test
    fun `Verbindung ueber SSH-Tunnel`(): Unit = runBlocking {
        TestSshServer().start().use { ssh ->
            val spec = ConnectionSpec("127.0.0.1", ssh.port, TestSshServer.USER, listOf(SshAuth.Password(TestSshServer.PASSWORD.toCharArray())))
            SshSession.connect(spec, KnownHostsVerifier(Files.createTempFile("kh", "")) { true }).use { session ->
                RedisBrowser.connect(RedisSpec(host = "127.0.0.1", port = redis.port), tunnel = session).use { b ->
                    assertTrue(b.isTunneled)
                    assertEquals(1, session.activeForwards().size)
                    assertEquals(RedisValue.Text("Bob"), b.inspect("user:2:name").value)
                }
                // Schließen des Browsers schließt auch den Tunnel
                assertTrue(session.activeForwards().isEmpty())
            }
        }
    }

    @Test
    fun `isTruncated erkennt gekuerzte Sammlungen`() {
        assertTrue(RedisValue.ListValue(listOf("a"), 5).isTruncated)
        assertFalse(RedisValue.HashValue(listOf("a" to "b"), 1).isTruncated)
        assertFalse(RedisValue.Text("x").isTruncated)
    }
}
