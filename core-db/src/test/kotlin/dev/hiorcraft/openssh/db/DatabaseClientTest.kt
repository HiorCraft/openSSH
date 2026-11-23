package dev.hiorcraft.openssh.db

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Echte JDBC-Tests gegen SQLite (MariaDB/PostgreSQL nutzen denselben Code-Pfad über HikariCP). */
class DatabaseClientTest {

    @TempDir
    lateinit var dir: Path

    private suspend fun client() = DatabaseClient.connect(DbConnectionSpec(DbType.SQLITE, sqliteFile = dir.resolve("test.db")))

    @Test
    fun `Skript ausfuehren und Ergebnisse als Tabelle erhalten`(): Unit = runBlocking {
        client().use { db ->
            val results = db.execute(
                """
                CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, avatar BLOB, note TEXT DEFAULT 'x');
                INSERT INTO users (name, avatar) VALUES ('Alice', X'CAFE'), ('Bob', NULL), ('Carol; mit Semikolon', NULL);
                SELECT id, name, avatar, note FROM users ORDER BY id;
                """.trimIndent(),
            )
            assertEquals(3, results.size)
            assertEquals(3L, assertIs<StatementResult.Update>(results[1]).count)
            val table = assertIs<StatementResult.Table>(results[2])
            assertEquals(listOf("id", "name", "avatar", "note"), table.columns.map { it.name })
            assertEquals(listOf("1", "Alice", "0xCAFE", "x"), table.rows[0])
            assertNull(table.rows[1][2])
            assertEquals("Carol; mit Semikolon", table.rows[2][1])
            assertFalse(table.truncated)
        }
    }

    @Test
    fun `maxRows begrenzt und markiert gekuerzte Ergebnisse`(): Unit = runBlocking {
        client().use { db ->
            db.execute("CREATE TABLE n (v INTEGER)")
            db.execute((1..25).joinToString(";") { "INSERT INTO n VALUES ($it)" })
            val table = db.execute("SELECT v FROM n ORDER BY v", maxRows = 10).single() as StatementResult.Table
            assertEquals(10, table.rows.size)
            assertTrue(table.truncated)
            val preview = db.preview("main", "n", limit = 5)
            assertEquals(listOf("1", "2", "3", "4", "5"), preview.rows.map { it.single() })
        }
    }

    @Test
    fun `Metadaten zu Tabellen, Views und Spalten`(): Unit = runBlocking {
        client().use { db ->
            db.execute(
                """
                CREATE TABLE orders (id INTEGER PRIMARY KEY, customer TEXT NOT NULL, total REAL DEFAULT 0);
                CREATE TABLE "Kunden" (nr INTEGER);
                CREATE VIEW big_orders AS SELECT * FROM orders WHERE total > 100;
                """.trimIndent(),
            )
            assertEquals(listOf("main"), db.schemas())
            assertEquals("main", db.defaultSchema())
            val tables = db.tables("main")
            assertEquals(listOf("Kunden", "orders", "big_orders"), tables.map { it.name })
            assertTrue(tables.last().isView)

            val columns = db.columns("main", "orders")
            assertEquals(listOf("id", "customer", "total"), columns.map { it.name })
            assertTrue(columns[0].primaryKey)
            assertFalse(columns[1].nullable)
            assertEquals("0", columns[2].defaultValue)
            assertTrue(db.serverVersion().startsWith("SQLite"))
        }
    }

    @Test
    fun `SQL-Fehler werden als DatabaseException gemeldet`(): Unit = runBlocking {
        client().use { db ->
            val e = assertFailsWith<DatabaseException> { db.execute("SELECT * FROM gibt_es_nicht") }
            assertTrue(e.message!!.contains("gibt_es_nicht"))
        }
    }

    @Test
    fun `nicht erreichbarer Server liefert eine verstaendliche Meldung`(): Unit = runBlocking {
        val e = assertFailsWith<DatabaseException> {
            DatabaseClient.connect(DbConnectionSpec(DbType.POSTGRESQL, host = "127.0.0.1", port = 1, username = "u", password = "p"))
        }
        assertTrue(e.message!!.startsWith("Verbindung zu PostgreSQL fehlgeschlagen"), e.message)
    }

    @Test
    fun `Zellformatierung`() {
        assertNull(DatabaseClient.formatCell(null))
        assertEquals("0x0102", DatabaseClient.formatCell(byteArrayOf(1, 2)))
        assertEquals("42", DatabaseClient.formatCell(42))
        assertTrue(DatabaseClient.formatCell(ByteArray(100))!!.endsWith("… (100 Byte)"))
    }
}
