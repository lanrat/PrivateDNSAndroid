package ru.karasevm.privatednstoggle.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.karasevm.privatednstoggle.Device
import ru.karasevm.privatednstoggle.data.database.DnsServerRoomDatabase
import ru.karasevm.privatednstoggle.data.database.MIGRATION_1_2
import ru.karasevm.privatednstoggle.model.DnsServer

/** The real Room database and migration on the device's SQLite */
@RunWith(AndroidJUnit4::class)
class DatabaseInstrumentedTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(Device.instrumentation, DnsServerRoomDatabase::class.java)

    private fun rows(dao: DnsServerDao) = runBlocking {
        dao.getAll().first().map { "${it.server} '${it.label}' ${it.enabled} ${it.sortOrder}" }
    }

    @Test
    fun migration1To2_isValidatedByRoomAndDeduplicates() {
        migrationHelper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO dns_servers (id, server, label, enabled, sortOrder) VALUES
                    (1, 'dns.google', '', 0, 0),
                    (2, 'one.one.one.one', '', 1, 1),
                    (3, 'DNS.Google', 'Google', 1, 2)
                """.trimIndent()
            )
        }

        // Fails if the migrated schema doesn't match what Room expects for version 2
        migrationHelper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).close()

        // Open it like the app does
        val database = Room.databaseBuilder(Device.context, DnsServerRoomDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2)
            .build()
        try {
            val dao = database.dnsServerDao()
            // The enabled, labelled copy is kept, at its own position
            assertEquals(listOf("one.one.one.one '' true 0", "DNS.Google 'Google' true 1"), rows(dao))
            runBlocking {
                assertEquals("DNS.Google", dao.getFirstByServer("dns.google")?.server)
                assertEquals("DNS.Google", dao.getNextEnabledByServer("ONE.ONE.ONE.ONE")?.server)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun daoQueriesOnTheRealDatabase(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(Device.context, DnsServerRoomDatabase::class.java).build()
        try {
            val dao = database.dnsServerDao()
            dao.insertAllIfAbsent(
                listOf(DnsServer(server = "a.example"), DnsServer(server = "b.example", enabled = false), DnsServer(server = "c.example"))
            )

            // Next enabled server, case-insensitively, skipping disabled ones
            assertEquals("c.example", dao.getNextEnabledByServer("A.EXAMPLE")?.server)
            assertNull(dao.getNextEnabledByServer("c.example"))
            assertNull(dao.getNextEnabledByServer("unknown.example"))

            // Duplicates are ignored, also with different case
            dao.insert("A.Example", "", true)
            assertEquals(3, dao.getAll().first().size)
            // An update to an address that is taken changes nothing
            val c = dao.getFirstByServer("c.example")!!
            assertEquals(0, dao.update(c.id, "a.example", null, null, null))
            // The unique index is really there
            assertThrows(SQLiteConstraintException::class.java) {
                database.openHelper.writableDatabase.execSQL("INSERT INTO dns_servers (server, label) VALUES ('B.EXAMPLE', '')")
            }

            // Moving and deleting keep sortOrder 0..n-1
            dao.moveDown(0, 2, dao.getFirstByServer("a.example")!!.id)
            assertEquals(listOf("b.example", "c.example", "a.example"), dao.getAll().first().map { it.server })
            dao.deleteAndDecrement(dao.getFirstByServer("b.example")!!.id)
            assertEquals(listOf(0, 1), dao.getAll().first().map { it.sortOrder })
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
