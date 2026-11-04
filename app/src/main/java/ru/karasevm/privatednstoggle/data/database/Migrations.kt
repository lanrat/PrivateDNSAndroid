package ru.karasevm.privatednstoggle.data.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/**
 * Sort position of a row. v1 listed rows by `ORDER BY sortOrder`, where SQLite puts NULL first,
 * so a missing sortOrder sorts first here too.
 */
private const val ORDER_KEY = "COALESCE(%s.sortOrder, -2147483648)"

/**
 * Ranking of duplicate rows, best first: enabled, then labelled, then earlier in the list.
 * A row value, compared left to right (SQLite 3.15+, Android 9 ships 3.22).
 */
private fun rank(table: String) =
    "(1 - $table.enabled, $table.label = '', ${ORDER_KEY.format(table)}, $table.id)"

/**
 * Version 2 makes server addresses unique. Duplicates broke cycling: the next server is looked
 * up by address, so with [a, b, a] the tile went a → b → a → b and never reached off/auto.
 *
 * The migration keeps the best copy of each duplicate (see [rank]) where it is in the list,
 * renumbers sortOrder to 0..n-1 without gaps (which moving and deleting rely on) and adds the
 * unique index.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    val statements = listOf(
        """
        DELETE FROM dns_servers WHERE EXISTS (
            SELECT 1 FROM dns_servers AS other
            WHERE other.server = dns_servers.server AND ${rank("other")} < ${rank("dns_servers")}
        )
        """,
        """
        CREATE TEMP TABLE dns_servers_order AS
        SELECT s.id AS id, (
            SELECT COUNT(*) FROM dns_servers AS o
            WHERE (${ORDER_KEY.format("o")}, o.id) < (${ORDER_KEY.format("s")}, s.id)
        ) AS newOrder
        FROM dns_servers AS s
        """,
        """
        UPDATE dns_servers SET sortOrder =
            (SELECT newOrder FROM dns_servers_order WHERE dns_servers_order.id = dns_servers.id)
        """,
        "DROP TABLE dns_servers_order",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_dns_servers_server` ON `dns_servers` (`server`)",
    ).map { it.trimIndent() }

    // Room 2.8 calls this overload, also in the default SupportSQLite mode, where it executes
    // the statements through the SupportSQLiteDatabase. The JVM unit test uses it as well.
    override fun migrate(connection: SQLiteConnection) = statements.forEach { connection.execSQL(it) }

    // Not called by Room 2.8 when the overload above exists; kept for direct SupportSQLite use
    override fun migrate(db: SupportSQLiteDatabase) = statements.forEach { db.execSQL(it) }
}
