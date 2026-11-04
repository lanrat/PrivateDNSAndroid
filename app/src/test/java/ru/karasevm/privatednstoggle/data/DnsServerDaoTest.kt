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
}
