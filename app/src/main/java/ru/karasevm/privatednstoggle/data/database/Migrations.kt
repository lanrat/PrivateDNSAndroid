package ru.karasevm.privatednstoggle.data.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/** Sort key for rows, rows without a sortOrder go last, ties are broken by id */
private const val ORDER_KEY_MAX = "2147483647"

/**
 * Version 2 makes server addresses unique. Duplicates broke cycling: the next server is looked
 * up by address, so with [a, b, a] the tile went a → b → a → b and never reached off/auto.
 *
 * The migration keeps the first of each duplicate (by sortOrder), renumbers sortOrder to
 * 0..n-1 without gaps (which moving and deleting rely on) and adds the unique index.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    val statements = listOf(
        """
        DELETE FROM dns_servers WHERE EXISTS (
            SELECT 1 FROM dns_servers AS other
            WHERE other.server = dns_servers.server AND (
                COALESCE(other.sortOrder, $ORDER_KEY_MAX) < COALESCE(dns_servers.sortOrder, $ORDER_KEY_MAX)
                OR (COALESCE(other.sortOrder, $ORDER_KEY_MAX) = COALESCE(dns_servers.sortOrder, $ORDER_KEY_MAX)
                    AND other.id < dns_servers.id)
            )
        )
        """,
        """
        CREATE TEMP TABLE dns_servers_order AS
        SELECT s.id AS id, (
            SELECT COUNT(*) FROM dns_servers AS o
            WHERE COALESCE(o.sortOrder, $ORDER_KEY_MAX) < COALESCE(s.sortOrder, $ORDER_KEY_MAX)
                OR (COALESCE(o.sortOrder, $ORDER_KEY_MAX) = COALESCE(s.sortOrder, $ORDER_KEY_MAX) AND o.id < s.id)
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

    // Room calls this one while the app uses the framework SQLite (the default)
    override fun migrate(db: SupportSQLiteDatabase) = statements.forEach { db.execSQL(it) }

    // and this one with an SQLiteDriver, e.g. in the JVM unit test
    override fun migrate(connection: SQLiteConnection) = statements.forEach { connection.execSQL(it) }
}
