package dev.hiorcraft.openssh.db.redis

import dev.hiorcraft.openssh.db.DatabaseException
import dev.hiorcraft.openssh.db.Endpoint
import dev.hiorcraft.openssh.ssh.SshSession
import io.lettuce.core.ClientOptions
import io.lettuce.core.Limit
import io.lettuce.core.Range
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.protocol.ProtocolVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.time.Duration

data class RedisSpec(
    /** Host/Port aus Sicht des SSH-Servers, falls getunnelt wird. */
    val host: String = "127.0.0.1",
    val port: Int = 6379,
    val username: String = "",
    val password: String = "",
    val database: Int = 0,
    val useTls: Boolean = false,
) {
    override fun toString() = "RedisSpec(host=$host, port=$port, db=$database, tls=$useTls)"
}

data class ScanPage(val keys: List<String>, val cursor: String, val finished: Boolean)

/** Wert eines Redis-Keys. Sammlungen werden auf [RedisBrowser.MAX_ITEMS] Elemente begrenzt, [total] ist die echte Größe. */
sealed interface RedisValue {
    val total: Long

    data class Text(val value: String) : RedisValue { override val total = 1L }
    data class ListValue(val items: List<String>, override val total: Long) : RedisValue
    data class SetValue(val members: List<String>, override val total: Long) : RedisValue
    data class SortedSetValue(val entries: List<Pair<String, Double>>, override val total: Long) : RedisValue
    data class HashValue(val fields: List<Pair<String, String>>, override val total: Long) : RedisValue
    data class StreamValue(val entries: List<Pair<String, Map<String, String>>>, override val total: Long) : RedisValue
    data object Missing : RedisValue { override val total = 0L }
    data class Unsupported(val type: String) : RedisValue { override val total = 0L }

    val isTruncated: Boolean
        get() = when (this) {
            is ListValue -> items.size < total
            is SetValue -> members.size < total
            is SortedSetValue -> entries.size < total
            is HashValue -> fields.size < total
            is StreamValue -> entries.size < total
            else -> false
        }
}

data class RedisKeyDetails(
    val key: String,
    val type: String,
    /** Restlaufzeit in Sekunden, `-1` = kein Ablauf, `-2` = Key existiert nicht. */
    val ttlSeconds: Long,
    val value: RedisValue,
)

/** Key-Browser für Redis, optional über einen SSH-Tunnel. */
class RedisBrowser private constructor(
    private val client: RedisClient,
    private val connection: StatefulRedisConnection<String, String>,
    private val endpoint: Endpoint,
) : Closeable {

    private val redis: RedisCommands<String, String> get() = connection.sync()

    val isTunneled: Boolean get() = endpoint.isTunneled

    suspend fun ping(): String = io { redis.ping() }

    suspend fun dbSize(): Long = io { redis.dbsize() }

    /** Eine Seite von Keys passend zum Glob-Muster ([pattern] wie bei `SCAN MATCH`). */
    suspend fun scan(pattern: String = "*", cursor: String = "0", count: Long = 200): ScanPage = io {
        val args = ScanArgs.Builder.matches(pattern.ifBlank { "*" }).limit(count)
        val result = redis.scan(ScanCursor.of(cursor), args)
        ScanPage(result.keys, result.cursor, result.isFinished)
    }

    /** Sammelt Keys über mehrere SCAN-Runden, bis [limit] erreicht oder alles durchsucht ist. */
    suspend fun searchKeys(pattern: String, limit: Int = 1000): ScanPage {
        val keys = LinkedHashSet<String>()
        var cursor = "0"
        do {
            val page = scan(pattern, cursor, 500)
            keys += page.keys
            cursor = page.cursor
            if (page.finished) return ScanPage(keys.take(limit), "0", true)
        } while (keys.size < limit)
        return ScanPage(keys.take(limit), cursor, false)
    }

    suspend fun inspect(key: String): RedisKeyDetails = io {
        val type = redis.type(key)
        val ttl = redis.ttl(key)
        val value: RedisValue = when (type) {
            "none" -> RedisValue.Missing
            "string" -> RedisValue.Text(redis.get(key) ?: "")
            "list" -> RedisValue.ListValue(redis.lrange(key, 0, MAX_ITEMS - 1L), redis.llen(key))
            "set" -> {
                val members = mutableListOf<String>()
                var cursor = ScanCursor.INITIAL
                do {
                    val page = redis.sscan(key, cursor, ScanArgs.Builder.limit(200))
                    members += page.values
                    cursor = page
                } while (!page.isFinished && members.size < MAX_ITEMS)
                RedisValue.SetValue(members.take(MAX_ITEMS).sorted(), redis.scard(key))
            }
            "zset" -> RedisValue.SortedSetValue(
                redis.zrangeWithScores(key, 0, MAX_ITEMS - 1L).map { it.value to it.score },
                redis.zcard(key),
            )
            "hash" -> {
                val fields = mutableListOf<Pair<String, String>>()
                var cursor = ScanCursor.INITIAL
                do {
                    val page = redis.hscan(key, cursor, ScanArgs.Builder.limit(200))
                    fields += page.map.entries.map { it.key to it.value }
                    cursor = page
                } while (!page.isFinished && fields.size < MAX_ITEMS)
                RedisValue.HashValue(fields.take(MAX_ITEMS).sortedBy { it.first }, redis.hlen(key))
            }
            "stream" -> RedisValue.StreamValue(
                redis.xrange(key, Range.create("-", "+"), Limit.from(MAX_ITEMS.toLong())).map { it.id to it.body },
                redis.xlen(key),
            )
            else -> RedisValue.Unsupported(type)
        }
        RedisKeyDetails(key, type, ttl, value)
    }

    suspend fun setString(key: String, value: String) = io { redis.set(key, value) }

    suspend fun delete(key: String): Long = io { redis.del(key) }

    suspend fun expire(key: String, seconds: Long): Boolean = io { if (seconds < 0) redis.persist(key) else redis.expire(key, seconds) }

    /** `INFO server` als Schlüssel/Wert-Paare. */
    suspend fun serverInfo(): Map<String, String> = io {
        redis.info("server").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(':') }
            .associate { it.substringBefore(':') to it.substringAfter(':') }
    }

    override fun close() {
        runCatching { connection.close() }
        runCatching { client.shutdown(Duration.ZERO, Duration.ofSeconds(2)) }
        endpoint.close()
    }

    companion object {
        const val MAX_ITEMS = 500

        suspend fun connect(spec: RedisSpec, tunnel: SshSession? = null): RedisBrowser = withContext(Dispatchers.IO) {
            val endpoint = Endpoint.open(spec.host, spec.port, tunnel)
            val uri = RedisURI.Builder.redis(endpoint.host, endpoint.port)
                .withDatabase(spec.database)
                .withSsl(spec.useTls)
                .withTimeout(Duration.ofSeconds(15))
                .apply {
                    if (spec.password.isNotEmpty()) {
                        if (spec.username.isNotEmpty()) withAuthentication(spec.username, spec.password.toCharArray())
                        else withPassword(spec.password.toCharArray())
                    }
                }
                .build()
            val client = RedisClient.create(uri)
            // RESP2 funktioniert mit allen Redis-Versionen (auch < 6 und kompatiblen Servern wie KeyDB/Valkey)
            client.options = ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).build()
            try {
                val connection = client.connect()
                RedisBrowser(client, connection, endpoint)
            } catch (e: Exception) {
                runCatching { client.shutdown(Duration.ZERO, Duration.ofSeconds(1)) }
                endpoint.close()
                val root = generateSequence<Throwable>(e) { it.cause }.last()
                throw DatabaseException("Redis-Verbindung fehlgeschlagen: ${root.message ?: e.message}", e)
            }
        }
    }
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: io.lettuce.core.RedisException) {
        throw DatabaseException("Redis: ${e.message}", e)
    }
}
