package ru.karasevm.privatednstoggle.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.karasevm.privatednstoggle.model.DnsServer

class DnsServerDaoTest {

    @Test
    fun insertAllIfAbsent_skipsExistingAndBlankServersAndKeepsOrder() = runTest {
        val dao = FakeDnsServerDao(listOf(DnsServer(server = "a.example", label = "A")))

        dao.insertAllIfAbsent(
            listOf(
                DnsServer(server = "b.example", label = "B", enabled = false),
                DnsServer(server = "a.example", label = "changed"),
                DnsServer(server = " "),
                DnsServer(server = "c.example"),
                DnsServer(server = "b.example"),
            )
        )

        assertEquals(
            listOf(
                DnsServer(1, "a.example", "A", true, 0),
                DnsServer(2, "b.example", "B", false, 1),
                DnsServer(3, "c.example", "", true, 2),
            ),
            dao.rows
        )
    }

    @Test
    fun lookupsIgnoreCase() = runTest {
        val dao = FakeDnsServerDao(listOf(DnsServer(server = "a.example"), DnsServer(server = "b.example")))

        // e.g. the system hostname was typed with different case in the system settings
        assertEquals("a.example", dao.getFirstByServer("A.Example")?.server)
        assertEquals("b.example", dao.getNextEnabledByServer("A.EXAMPLE")?.server)
        dao.insertAllIfAbsent(listOf(DnsServer(server = "B.Example")))
        assertEquals(2, dao.rows.size)
    }
}
