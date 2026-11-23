package dev.hiorcraft.openssh.db

import java.nio.file.Path

enum class DbType(val displayName: String, val defaultPort: Int, val driverClass: String) {
    MARIADB("MariaDB / MySQL", 3306, "org.mariadb.jdbc.Driver"),
    POSTGRESQL("PostgreSQL", 5432, "org.postgresql.Driver"),
    SQLITE("SQLite", 0, "org.sqlite.JDBC"),
    ;

    val supportsTunnel: Boolean get() = this != SQLITE
}

/** Verbindungsdaten einer JDBC-Datenbank. Host/Port sind aus Sicht des SSH-Servers, falls getunnelt wird. */
data class DbConnectionSpec(
    val type: DbType,
    val host: String = "127.0.0.1",
    val port: Int = type.defaultPort,
    val database: String = "",
    val username: String = "",
    val password: String = "",
    /** Nur für SQLite: Pfad zur Datenbankdatei. */
    val sqliteFile: Path? = null,
    val properties: Map<String, String> = emptyMap(),
) {
    override fun toString() = "DbConnectionSpec(type=$type, host=$host, port=$port, database=$database, user=$username)"
}

data class ColumnInfo(val name: String, val typeName: String)

sealed interface StatementResult {
    val sql: String
    val durationMillis: Long

    data class Table(
        override val sql: String,
        val columns: List<ColumnInfo>,
        val rows: List<List<String?>>,
        /** `true`, wenn es mehr Zeilen gab als `maxRows`. */
        val truncated: Boolean,
        override val durationMillis: Long,
    ) : StatementResult

    data class Update(
        override val sql: String,
        val count: Long,
        override val durationMillis: Long,
    ) : StatementResult
}

data class TableInfo(val schema: String?, val name: String, val type: String) {
    val isView: Boolean get() = type.contains("VIEW", ignoreCase = true)
}

data class ColumnMeta(
    val name: String,
    val typeName: String,
    val size: Int,
    val nullable: Boolean,
    val primaryKey: Boolean,
    val defaultValue: String?,
    val autoIncrement: Boolean,
)

/** Baut JDBC-URLs für die unterstützten Datenbanken. */
object JdbcUrls {
    fun build(spec: DbConnectionSpec, host: String = spec.host, port: Int = spec.port): String = when (spec.type) {
        DbType.MARIADB -> "jdbc:mariadb://$host:$port/${spec.database}" + query(spec.properties)
        DbType.POSTGRESQL -> "jdbc:postgresql://$host:$port/${spec.database.ifBlank { "postgres" }}" + query(spec.properties)
        DbType.SQLITE -> {
            val file = requireNotNull(spec.sqliteFile) { "Für SQLite muss eine Datei angegeben werden" }
            "jdbc:sqlite:" + file.toAbsolutePath().toString().replace('\\', '/')
        }
    }

    private fun query(props: Map<String, String>): String =
        if (props.isEmpty()) "" else props.entries.joinToString("&", prefix = "?") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)
}

/** Quoting von Bezeichnern je nach SQL-Dialekt. */
object SqlDialect {
    fun quote(type: DbType, identifier: String): String = when (type) {
        DbType.MARIADB -> "`" + identifier.replace("`", "``") + "`"
        DbType.POSTGRESQL, DbType.SQLITE -> "\"" + identifier.replace("\"", "\"\"") + "\""
    }

    fun qualified(type: DbType, schema: String?, table: String): String =
        if (schema.isNullOrBlank() || type == DbType.SQLITE) quote(type, table) else quote(type, schema) + "." + quote(type, table)

    fun selectPreview(type: DbType, schema: String?, table: String, limit: Int = 200): String =
        "SELECT * FROM ${qualified(type, schema, table)} LIMIT $limit"
}
