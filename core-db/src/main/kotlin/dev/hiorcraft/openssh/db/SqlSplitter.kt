package dev.hiorcraft.openssh.db

/**
 * Zerlegt ein SQL-Skript an `;` in einzelne Anweisungen. Semikolons in Strings,
 * Bezeichnern, Kommentaren und PostgreSQL-Dollar-Quotes (`$$ ... $$`, `$tag$ ... $tag$`) zählen nicht.
 */
object SqlSplitter {

    /** Welche Syntax-Elemente der Dialekt kennt. */
    data class Dialect(
        /** `#` leitet einen Zeilenkommentar ein (MySQL/MariaDB). In PostgreSQL ist `#` ein Operator. */
        val hashComments: Boolean,
        /** Backslash escaped in `'...'` (MySQL/MariaDB). */
        val backslashEscapes: Boolean,
        /** `$$ ... $$` und `$tag$ ... $tag$` (PostgreSQL). */
        val dollarQuotes: Boolean,
    )

    val ANSI = Dialect(hashComments = false, backslashEscapes = false, dollarQuotes = false)

    fun dialectFor(type: DbType): Dialect = when (type) {
        DbType.MARIADB -> Dialect(hashComments = true, backslashEscapes = true, dollarQuotes = false)
        DbType.POSTGRESQL -> Dialect(hashComments = false, backslashEscapes = false, dollarQuotes = true)
        DbType.SQLITE -> ANSI
    }

    fun split(script: String, dialect: Dialect = ANSI): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        val n = script.length

        fun flush() {
            val stmt = current.toString().trim()
            if (stmt.isNotEmpty() && !isOnlyComments(stmt, dialect)) result += stmt
            current.clear()
        }

        while (i < n) {
            val c = script[i]
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    val end = skipQuoted(script, i, c, dialect.backslashEscapes)
                    current.append(script, i, end); i = end
                }
                c == '-' && i + 1 < n && script[i + 1] == '-' -> {
                    val end = script.indexOf('\n', i).let { if (it < 0) n else it }
                    current.append(script, i, end); i = end
                }
                c == '#' && dialect.hashComments -> {
                    val end = script.indexOf('\n', i).let { if (it < 0) n else it }
                    current.append(script, i, end); i = end
                }
                c == '/' && i + 1 < n && script[i + 1] == '*' -> {
                    val end = script.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    current.append(script, i, end); i = end
                }
                c == '$' && dialect.dollarQuotes -> {
                    val tag = dollarTag(script, i)
                    if (tag != null) {
                        val close = script.indexOf(tag, i + tag.length)
                        val end = if (close < 0) n else close + tag.length
                        current.append(script, i, end); i = end
                    } else {
                        current.append(c); i++
                    }
                }
                c == ';' -> { flush(); i++ }
                else -> { current.append(c); i++ }
            }
        }
        flush()
        return result
    }

    /** Liefert den Index direkt nach dem schließenden Anführungszeichen. Verdoppelte Quotes (und je nach Dialekt Backslash-Escapes) werden übersprungen. */
    private fun skipQuoted(s: String, start: Int, quote: Char, backslashEscapes: Boolean): Int {
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (backslashEscapes && c == '\\' && quote == '\'' && i + 1 < s.length) { i += 2; continue }
            if (c == quote) {
                if (i + 1 < s.length && s[i + 1] == quote) { i += 2; continue }
                return i + 1
            }
            i++
        }
        return s.length
    }

    private val dollarTagRegex = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)?\$""")

    private fun dollarTag(s: String, start: Int): String? {
        // `$1` (Parameter) ist kein Dollar-Quote
        val match = dollarTagRegex.matchAt(s, start) ?: return null
        if (start > 0 && (s[start - 1].isLetterOrDigit() || s[start - 1] == '_')) return null
        return match.value
    }

    private fun isOnlyComments(stmt: String, dialect: Dialect): Boolean {
        val withoutBlocks = stmt.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        return withoutBlocks.lines().all { line ->
            val t = line.trim()
            t.isEmpty() || t.startsWith("--") || (dialect.hashComments && t.startsWith("#"))
        }
    }
}
