package dev.hiorcraft.openssh.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.hiorcraft.openssh.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.sql.Blob
import java.sql.Clob
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.concurrent.thread

class DatabaseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Eine JDBC-Verbindung (mit kleinem HikariCP-Pool), optional durch einen SSH-Tunnel.
 * Alle Methoden blockieren nicht den Aufrufer, sondern laufen auf [Dispatchers.IO].
 */
class DatabaseClient private constructor(
    val spec: DbConnectionSpec,
    private val dataSource: HikariDataSource,
    private val endpoint: Endpoint?,
) : Closeable {

    private val running = AtomicReference<Statement?>(null)

    val type: DbType get() = spec.type
    val isTunneled: Boolean get() = endpoint?.isTunneled == true

    /**
     * Führt ein Skript aus (mehrere Anweisungen durch `;` getrennt). Ergebnismengen werden
     * auf [maxRows] Zeilen begrenzt. Abbruch der Coroutine bricht die laufende Anweisung ab.
     */
    suspend fun execute(script: String, maxRows: Int = 1000): List<StatementResult> {
        val statements = SqlSplitter.split(script, SqlSplitter.dialectFor(type))
        if (statements.isEmpty()) return emptyList()
        return cancellableIo {
            dataSource.connection.use { conn -> statements.map { executeOne(conn, it, maxRows) } }
        }
    }

    /** Bricht die gerade laufende Anweisung ab (z.B. über einen „Stopp“-Knopf). */
    fun cancelRunning() {
        running.get()?.let { runCatching { it.cancel() } }
    }

    private fun executeOne(conn: Connection, sql: String, maxRows: Int): StatementResult {
        val started = System.nanoTime()
        conn.createStatement().use { stmt ->
            running.set(stmt)
            try {
                stmt.maxRows = maxRows + 1
                val hasResultSet = try {
                    stmt.execute(sql)
                } catch (e: SQLException) {
                    throw DatabaseException(e.message ?: "SQL-Fehler", e)
                }
                val elapsed = { (System.nanoTime() - started) / 1_000_000 }
                return if (hasResultSet) {
                    stmt.resultSet.use { rs -> readTable(sql, rs, maxRows, elapsed) }
                } else {
                    StatementResult.Update(sql, stmt.largeUpdateCountSafe(), elapsed())
                }
            } finally {
                running.set(null)
            }
        }
    }

    private fun readTable(sql: String, rs: ResultSet, maxRows: Int, elapsed: () -> Long): StatementResult.Table {
        val meta = rs.metaData
        val columns = (1..meta.columnCount).map { ColumnInfo(meta.getColumnLabel(it), meta.getColumnTypeName(it) ?: "") }
        val rows = mutableListOf<List<String?>>()
        var truncated = false
        while (rs.next()) {
            if (rows.size >= maxRows) { truncated = true; break }
            rows += (1..columns.size).map { formatCell(rs.getObject(it)) }
        }
        return StatementResult.Table(sql, columns, rows, truncated, elapsed())
    }

    /** Datenbanken (MariaDB) bzw. Schemas (PostgreSQL); bei SQLite nur `main`. */
    suspend fun schemas(): List<String> = io {
        dataSource.connection.use { conn ->
            when (type) {
                DbType.MARIADB -> conn.metaData.catalogs.use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                DbType.POSTGRESQL -> conn.metaData.schemas.use { rs -> generateSequence { if (rs.next()) rs.getString("TABLE_SCHEM") else null }.toList() }
                    .filterNot { it.startsWith("pg_toast") || it.startsWith("pg_temp") }
                DbType.SQLITE -> listOf("main")
            }
        }
    }

    /** Das Schema, mit dem die Verbindung standardmäßig arbeitet. */
    suspend fun defaultSchema(): String? = io {
        dataSource.connection.use { conn ->
            when (type) {
                DbType.MARIADB -> conn.catalog
                DbType.POSTGRESQL -> conn.schema ?: "public"
                DbType.SQLITE -> "main"
            }
        }
    }

    suspend fun tables(schema: String?): List<TableInfo> = io {
        dataSource.connection.use { conn ->
            val types = arrayOf("TABLE", "VIEW")
            val rs = when (type) {
                DbType.MARIADB -> conn.metaData.getTables(schema, null, "%", types)
                DbType.POSTGRESQL -> conn.metaData.getTables(null, schema, "%", types)
                DbType.SQLITE -> conn.metaData.getTables(null, null, "%", types)
            }
            rs.use {
                generateSequence {
                    if (rs.next()) TableInfo(schema, rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE") ?: "TABLE") else null
                }.toList()
            }.filterNot { type == DbType.SQLITE && it.name.startsWith("sqlite_") }
                .sortedWith(compareBy<TableInfo> { it.isView }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
    }

    suspend fun columns(schema: String?, table: String): List<ColumnMeta> = io {
        dataSource.connection.use { conn ->
            val (catalog, schemaPattern) = when (type) {
                DbType.MARIADB -> schema to null
                DbType.POSTGRESQL -> null to schema
                DbType.SQLITE -> null to null
            }
            val pks = conn.metaData.getPrimaryKeys(catalog, schemaPattern, table).use { rs ->
                generateSequence { if (rs.next()) rs.getString("COLUMN_NAME") else null }.toSet()
            }
            conn.metaData.getColumns(catalog, schemaPattern, table, "%").use { rs ->
                generateSequence {
                    if (!rs.next()) return@generateSequence null
                    val name = rs.getString("COLUMN_NAME")
                    ColumnMeta(
                        name = name,
                        typeName = rs.getString("TYPE_NAME") ?: "",
                        size = rs.getInt("COLUMN_SIZE"),
                        nullable = rs.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls,
                        primaryKey = name in pks,
                        defaultValue = rs.getString("COLUMN_DEF"),
                        autoIncrement = runCatching { rs.getString("IS_AUTOINCREMENT") == "YES" }.getOrDefault(false),
                    )
                }.toList()
            }
        }
    }

    /** Liefert die ersten [limit] Zeilen einer Tabelle. */
    suspend fun preview(schema: String?, table: String, limit: Int = 200): StatementResult.Table =
        execute(SqlDialect.selectPreview(type, schema, table, limit), maxRows = limit).single() as StatementResult.Table

    /** Name und Version des Servers, z.B. „PostgreSQL 16.2“. */
    suspend fun serverVersion(): String = io {
        dataSource.connection.use { "${it.metaData.databaseProductName} ${it.metaData.databaseProductVersion}" }
    }

    override fun close() {
        runCatching { dataSource.close() }
        endpoint?.close()
    }

    private suspend fun <T> cancellableIo(block: () -> T): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val worker = thread(isDaemon = true, name = "db-query") {
                try {
                    cont.resume(block())
                } catch (e: Throwable) {
                    cont.resumeWithException(if (e is SQLException) DatabaseException(e.message ?: "SQL-Fehler", e) else e)
                }
            }
            cont.invokeOnCancellation {
                cancelRunning()
                worker.interrupt()
            }
        }
    }

    companion object {
        /**
         * Öffnet die Verbindung. Mit [tunnel] wird die Datenbank über den SSH-Server erreicht
         * (Host/Port der [spec] aus dessen Sicht, z.B. `127.0.0.1:3306`).
         */
        suspend fun connect(spec: DbConnectionSpec, tunnel: SshSession? = null): DatabaseClient = withContext(Dispatchers.IO) {
            val endpoint = if (spec.type.supportsTunnel) Endpoint.open(spec.host, spec.port, tunnel) else null
            try {
                val config = HikariConfig().apply {
                    jdbcUrl = if (endpoint != null) JdbcUrls.build(spec, endpoint.host, endpoint.port) else JdbcUrls.build(spec)
                    driverClassName = spec.type.driverClass
                    if (spec.username.isNotEmpty()) username = spec.username
                    if (spec.password.isNotEmpty()) password = spec.password
                    maximumPoolSize = if (spec.type == DbType.SQLITE) 1 else 3
                    minimumIdle = 0
                    connectionTimeout = 15_000
                    initializationFailTimeout = 1 // genau ein Versuch, damit Fehler sofort sichtbar werden
                    poolName = "openssh-${spec.type.name.lowercase()}"
                }
                DatabaseClient(spec, HikariDataSource(config), endpoint)
            } catch (e: Exception) {
                endpoint?.close()
                val root = generateSequence<Throwable>(e) { it.cause }.last()
                throw DatabaseException("Verbindung zu ${spec.type.displayName} fehlgeschlagen: ${root.message ?: e.message}", e)
            }
        }

        private const val MAX_CELL_CHARS = 10_000

        /** Wandelt einen JDBC-Wert in eine anzeigbare Zeichenkette um; `null` bleibt `null`. */
        fun formatCell(value: Any?): String? = when (value) {
            null -> null
            is ByteArray -> hex(value)
            is Blob -> hex(value.getBytes(1, minOf(value.length(), 64L).toInt())) + if (value.length() > 64) "… (${value.length()} Byte)" else ""
            is Clob -> value.getSubString(1, minOf(value.length(), MAX_CELL_CHARS.toLong()).toInt())
            is java.sql.Array -> runCatching { (value.array as? Array<*>)?.joinToString(", ", "{", "}") }.getOrNull() ?: value.toString()
            else -> value.toString().let { if (it.length > MAX_CELL_CHARS) it.take(MAX_CELL_CHARS) + "…" else it }
        }

        private fun hex(bytes: ByteArray): String {
            val shown = bytes.take(64).joinToString("") { "%02X".format(it) }
            return "0x$shown" + if (bytes.size > 64) "… (${bytes.size} Byte)" else ""
        }
    }
}

private fun Statement.largeUpdateCountSafe(): Long = try {
    largeUpdateCount
} catch (e: UnsupportedOperationException) {
    updateCount.toLong()
} catch (e: SQLException) {
    updateCount.toLong()
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: SQLException) {
        throw DatabaseException(e.message ?: "SQL-Fehler", e)
    }
}
