package ru.karasevm.privatednstoggle.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Runs the migrations against real SQLite, starting from the exported schema of the old version.
 */
class MigrationTest {

    private lateinit var connection: SQLiteConnection

    /** CREATE statements of [version] from the schema Room exported to app/schemas */
    private fun createStatements(version: Int): List<String> {
        val file = File("schemas/ru.karasevm.privatednstoggle.data.database.DnsServerRoomDatabase/$version.json")
        val database = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
        return database["entities"]!!.jsonArray.flatMap { entity ->
            val obj = entity.jsonObject
            val table = obj["tableName"]!!.jsonPrimitive.content
            val indices = obj["indices"]?.jsonArray.orEmpty().map { it.jsonObject["createSql"]!!.jsonPrimitive.content }
            (listOf(obj["createSql"]!!.jsonPrimitive.content) + indices).map {
                it.replace("\${TABLE_NAME}", table)
            }
        }
    }

    private fun rows(): List<String> {
        val result = mutableListOf<String>()
        connection.prepare("SELECT id, server, label, enabled, sortOrder FROM dns_servers ORDER BY sortOrder").use {
            while (it.step()) {
                result += "${it.getLong(0)} ${it.getText(1)} '${it.getText(2)}' ${it.getLong(3)} ${it.getLong(4)}"
            }
        }
        return result
    }

    @Before
    fun setUp() {
        connection = BundledSQLiteDriver().open(":memory:")
        createStatements(1).forEach { connection.execSQL(it) }
    }

    @After
    fun tearDown() = connection.close()

    @Test
    fun migrate1To2_keepsOneOfEachDuplicateAndRenumbers() {
        connection.execSQL(
            """
            INSERT INTO dns_servers (id, server, label, enabled, sortOrder) VALUES
                (1, 'a.example', 'A', 1, 0),
                (2, 'b.example', '', 1, 1),
                (3, 'a.example', 'duplicate', 0, 2),
                (4, 'c.example', '', 0, 3),
                (5, 'b.example', '', 1, 4),
                (6, 'd.example', '', 1, NULL),
                (7, 'e.example', '', 1, 6),
                (8, 'e.example', '', 1, 5)
            """.trimIndent()
        )

        MIGRATION_1_2.migrate(connection)

        assertEquals(
            listOf(
                // v1 listed rows without a sortOrder first (SQLite sorts NULL first)
                "6 d.example '' 1 0",
                // the enabled copy wins over the disabled one
                "1 a.example 'A' 1 1",
                "2 b.example '' 1 2",
                "4 c.example '' 0 3",
                // between equal copies, the one earlier in the list wins, not the lower id
                "8 e.example '' 1 4",
            ),
            rows()
        )
    }

    @Test
    fun migrate1To2_keepsTheMostUsefulCopyWhereItIs() {
        connection.execSQL(
            """
            INSERT INTO dns_servers (id, server, label, enabled, sortOrder) VALUES
                (1, 'a.example', '', 0, 0),
                (2, 'b.example', '', 1, 1),
                (3, 'a.example', 'Work', 1, 2),
                (4, 'c.example', '', 1, 3),
                (5, 'c.example', 'Home', 1, 4)
            """.trimIndent()
        )

        MIGRATION_1_2.migrate(connection)

        assertEquals(
            listOf(
                "2 b.example '' 1 0",
                // enabled and labelled beats disabled, and keeps its own position
                "3 a.example 'Work' 1 1",
                // labelled beats unlabelled
                "5 c.example 'Home' 1 2",
            ),
            rows()
        )
    }

    @Test
    fun migrate1To2_addsTheIndexOfTheNewSchema() {
        MIGRATION_1_2.migrate(connection)

        val expected = createStatements(2).single { it.contains("INDEX") }
        assertEquals(expected, indexSql().replace("CREATE UNIQUE INDEX", "CREATE UNIQUE INDEX IF NOT EXISTS"))
        connection.execSQL("INSERT INTO dns_servers (server, label) VALUES ('a.example', '')")
        assertThrows(SQLiteException::class.java) {
            connection.execSQL("INSERT INTO dns_servers (server, label) VALUES ('a.example', '')")
        }
    }

    @Test
    fun migrate1To2_handlesAnEmptyTable() {
        MIGRATION_1_2.migrate(connection)
        assertEquals(emptyList<String>(), rows())
    }

    private fun indexSql(): String =
        connection.prepare("SELECT sql FROM sqlite_master WHERE name = 'index_dns_servers_server'").use {
            check(it.step()) { "index missing" }
            it.getText(0)
        }
}
