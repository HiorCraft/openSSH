package dev.hiorcraft.openssh.db.redis

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Minimaler Redis-Server (RESP2) mit den Befehlen, die der [RedisBrowser] verwendet. */
class FakeRedisServer(private val requiredPassword: String? = null) : Closeable {
    val strings = ConcurrentHashMap<String, String>()
    val lists = ConcurrentHashMap<String, MutableList<String>>()
    val hashes = ConcurrentHashMap<String, MutableMap<String, String>>()
    val sets = ConcurrentHashMap<String, MutableSet<String>>()
    val zsets = ConcurrentHashMap<String, MutableMap<String, Double>>()
    val ttls = ConcurrentHashMap<String, Long>()

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "fake-redis") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { handle(socket) }
            }
        }
    }

    private fun allKeys(): List<String> = (strings.keys + lists.keys + hashes.keys + sets.keys + zsets.keys).sorted()

    private fun handle(socket: Socket) = socket.use {
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        var authed = requiredPassword == null
        while (true) {
            val cmd = readCommand(input) ?: break
            val name = cmd[0].uppercase()
            if (!authed && name != "AUTH" && name != "CLIENT") { out.error("NOAUTH Authentication required."); continue }
            when (name) {
                "AUTH" -> if (cmd.last() == requiredPassword) { authed = true; out.simple("OK") } else out.error("WRONGPASS invalid password")
                "PING" -> out.simple("PONG")
                "SELECT", "CLIENT" -> out.simple("OK")
                "DBSIZE" -> out.int(allKeys().size.toLong())
                "SCAN" -> {
                    val cursor = cmd[1].toInt()
                    val pattern = option(cmd, "MATCH") ?: "*"
                    val count = option(cmd, "COUNT")?.toInt() ?: 10
                    val keys = allKeys()
                    val page = keys.drop(cursor).take(count)
                    val next = if (cursor + count >= keys.size) 0 else cursor + count
                    out.write("*2\r\n".toByteArray())
                    out.bulk(next.toString())
                    out.array(page.filter { glob(pattern).matches(it) })
                }
                "TYPE" -> out.simple(
                    when {
                        strings.containsKey(cmd[1]) -> "string"
                        lists.containsKey(cmd[1]) -> "list"
                        hashes.containsKey(cmd[1]) -> "hash"
                        sets.containsKey(cmd[1]) -> "set"
                        zsets.containsKey(cmd[1]) -> "zset"
                        else -> "none"
                    },
                )
                "TTL" -> out.int(if (cmd[1] in allKeys()) ttls[cmd[1]] ?: -1 else -2)
                "GET" -> out.bulk(strings[cmd[1]])
                "SET" -> { strings[cmd[1]] = cmd[2]; out.simple("OK") }
                "DEL" -> {
                    val removed = cmd.drop(1).count { k -> listOf(strings, lists, hashes, sets, zsets).any { it.remove(k) != null } }
                    out.int(removed.toLong())
                }
                "EXPIRE" -> { ttls[cmd[1]] = cmd[2].toLong(); out.int(1) }
                "PERSIST" -> out.int(if (ttls.remove(cmd[1]) != null) 1 else 0)
                "LLEN" -> out.int(lists[cmd[1]]?.size?.toLong() ?: 0)
                "LRANGE" -> {
                    val list = lists[cmd[1]].orEmpty()
                    val stop = cmd[3].toInt().let { if (it < 0) list.size + it else it }.coerceAtMost(list.size - 1)
                    out.array(if (list.isEmpty()) emptyList() else list.subList(cmd[2].toInt(), stop + 1))
                }
                "SCARD" -> out.int(sets[cmd[1]]?.size?.toLong() ?: 0)
                "SSCAN" -> { out.write("*2\r\n".toByteArray()); out.bulk("0"); out.array(sets[cmd[1]].orEmpty().toList()) }
                "HLEN" -> out.int(hashes[cmd[1]]?.size?.toLong() ?: 0)
                "HSCAN" -> {
                    out.write("*2\r\n".toByteArray()); out.bulk("0")
                    out.array(hashes[cmd[1]].orEmpty().flatMap { listOf(it.key, it.value) })
                }
                "ZCARD" -> out.int(zsets[cmd[1]]?.size?.toLong() ?: 0)
                "ZRANGE" -> out.array(
                    zsets[cmd[1]].orEmpty().entries.sortedBy { it.value }.flatMap { listOf(it.key, it.value.toString()) },
                )
                "INFO" -> out.bulk("# Server\r\nredis_version:7.2.4\r\nredis_mode:standalone\r\n")
                else -> out.error("ERR unknown command '${cmd[0]}'")
            }
            out.flush()
        }
    }

    private fun option(cmd: List<String>, name: String): String? {
        val idx = cmd.indexOfFirst { it.equals(name, ignoreCase = true) }
        return if (idx > 0 && idx + 1 < cmd.size) cmd[idx + 1] else null
    }

    private fun glob(pattern: String) = Regex(
        pattern.map { c ->
            when (c) {
                '*' -> ".*"; '?' -> "."
                else -> Regex.escape(c.toString())
            }
        }.joinToString(""),
    )

    private fun readCommand(input: InputStream): List<String>? {
        val header = readLine(input) ?: return null
        require(header.startsWith("*")) { "Unerwartet: $header" }
        return (0 until header.substring(1).toInt()).map {
            val len = readLine(input)!!.substring(1).toInt()
            val bytes = input.readNBytes(len)
            readLine(input)
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\r'.code) { input.read(); return sb.toString() }
            sb.append(b.toChar())
        }
    }

    private fun OutputStream.simple(s: String) = write("+$s\r\n".toByteArray())
    private fun OutputStream.error(s: String) = write("-$s\r\n".toByteArray())
    private fun OutputStream.int(n: Long) = write(":$n\r\n".toByteArray())
    private fun OutputStream.bulk(s: String?) {
        if (s == null) { write("$-1\r\n".toByteArray()); return }
        val bytes = s.toByteArray(Charsets.UTF_8)
        write("$${bytes.size}\r\n".toByteArray()); write(bytes); write("\r\n".toByteArray())
    }
    private fun OutputStream.array(items: List<String>) {
        write("*${items.size}\r\n".toByteArray())
        items.forEach { bulk(it) }
    }

    override fun close() = server.close()
}
