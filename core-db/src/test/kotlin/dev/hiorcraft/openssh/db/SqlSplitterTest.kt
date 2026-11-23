package dev.hiorcraft.openssh.db

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

class SqlSplitterTest {

    private val maria = SqlSplitter.dialectFor(DbType.MARIADB)
    private val postgres = SqlSplitter.dialectFor(DbType.POSTGRESQL)

    @Test
    fun `einfache Anweisungen`() {
        assertEquals(listOf("SELECT 1", "SELECT 2"), SqlSplitter.split("SELECT 1; SELECT 2;"))
        assertEquals(listOf("SELECT 1"), SqlSplitter.split("  SELECT 1  "))
        assertEquals(emptyList(), SqlSplitter.split(" ; ;\n"))
    }

    @Test
    fun `Semikolons in Strings, Bezeichnern und Kommentaren`() {
        val sql = """
            INSERT INTO t VALUES ('a;b', 'it''s; fine');
            SELECT "col;umn" FROM `x;y`; -- Kommentar; mit Semikolon
            /* block; kommentar */ SELECT 3
        """.trimIndent()
        val parts = SqlSplitter.split(sql)
        assertEquals(3, parts.size)
        assertEquals("INSERT INTO t VALUES ('a;b', 'it''s; fine')", parts[0])
        assertEquals("SELECT \"col;umn\" FROM `x;y`", parts[1])
        assertEquals("-- Kommentar; mit Semikolon\n/* block; kommentar */ SELECT 3", parts[2])
    }

    @Test
    fun `reine Kommentare ergeben keine Anweisung`() {
        // Der führende Kommentar gehört zur Anweisung, der abschließende Block-Kommentar wird verworfen
        assertEquals(listOf("-- nur ein Kommentar;\nSELECT 1"), SqlSplitter.split("-- nur ein Kommentar;\nSELECT 1;\n/* ende */"))
        assertEquals(emptyList(), SqlSplitter.split("-- a;\n# b\n/* c; */", maria))
    }

    @Test
    fun `MariaDB kennt Hash-Kommentare und Backslash-Escapes`() {
        assertEquals(listOf("SELECT 'a\\'; b'", "SELECT 2"), SqlSplitter.split("SELECT 'a\\'; b'; # x;y\nSELECT 2", maria).map { it.lines().last() })
    }

    @Test
    fun `PostgreSQL Dollar-Quotes, Hash-Operator und Backslash als Literal`() {
        val fn = """
            CREATE FUNCTION f() RETURNS int AS ${'$'}body${'$'}
            BEGIN RETURN 1; END;
            ${'$'}body${'$'} LANGUAGE plpgsql;
            SELECT data #> '{a,b}' FROM docs;
            SELECT 'C:\'; SELECT ${'$'}1
        """.trimIndent()
        val parts = SqlSplitter.split(fn, postgres)
        assertEquals(4, parts.size, parts.toString())
        assertEquals(true, parts[0].contains("BEGIN RETURN 1; END;"))
        assertEquals("SELECT data #> '{a,b}' FROM docs", parts[1])
        assertEquals("SELECT 'C:\\'", parts[2])
        assertEquals("SELECT ${'$'}1", parts[3])
    }
}

class SqlDialectTest {

    @Test
    fun `JDBC-URLs`() {
        assertEquals(
            "jdbc:mariadb://127.0.0.1:40000/shop?useSSL=false",
            JdbcUrls.build(DbConnectionSpec(DbType.MARIADB, database = "shop", properties = mapOf("useSSL" to "false")), "127.0.0.1", 40000),
        )
        assertEquals("jdbc:postgresql://db:5432/postgres", JdbcUrls.build(DbConnectionSpec(DbType.POSTGRESQL, host = "db")))
        val sqlite = JdbcUrls.build(DbConnectionSpec(DbType.SQLITE, sqliteFile = Path.of("data", "app.db")))
        assertEquals(true, sqlite.startsWith("jdbc:sqlite:") && sqlite.endsWith("data/app.db"))
    }

    @Test
    fun `Bezeichner werden je nach Dialekt korrekt gequotet`() {
        assertEquals("`my``table`", SqlDialect.quote(DbType.MARIADB, "my`table"))
        assertEquals("\"we\"\"ird\"", SqlDialect.quote(DbType.POSTGRESQL, "we\"ird"))
        assertEquals("SELECT * FROM \"public\".\"users\" LIMIT 50", SqlDialect.selectPreview(DbType.POSTGRESQL, "public", "users", 50))
        assertEquals("SELECT * FROM \"users\" LIMIT 200", SqlDialect.selectPreview(DbType.SQLITE, "main", "users"))
    }

    @Test
    fun `Passwort erscheint nicht in toString`() {
        assertEquals(false, DbConnectionSpec(DbType.MARIADB, password = "topsecret").toString().contains("topsecret"))
    }
}
