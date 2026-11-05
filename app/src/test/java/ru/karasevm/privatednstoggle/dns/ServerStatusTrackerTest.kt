package ru.karasevm.privatednstoggle.dns

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerStatusTrackerTest {

    private val working = ProbeResult.Working(10)
    private var now = 1_000L

    // The test's own scope: advanceUntilIdle doesn't run backgroundScope work
    private fun TestScope.tracker(probe: ServerProbe, maxConcurrent: Int = 4) =
        ServerStatusTracker(probe, this, { now }, maxConcurrent)

    @Test
    fun showsTestingThenTheResult() = runTest {
        val answer = CompletableDeferred<ProbeResult>()
        val tracker = tracker({ answer.await() })

        tracker.test(listOf("a.example"))
        runCurrent()
        assertEquals(mapOf("a.example" to ServerStatus.Testing), tracker.statuses.value)

        answer.complete(working)
        advanceUntilIdle()
        assertEquals(mapOf("a.example" to ServerStatus.Tested(working, now)), tracker.statuses.value)
    }

    @Test
    fun keysIgnoreCaseAndDuplicatesAreTestedOnce() = runTest {
        val probed = mutableListOf<String>()
        val tracker = tracker({ probed += it; working })

        tracker.test(listOf("A.Example", "a.example"))
        advanceUntilIdle()

        assertEquals(listOf("a.example"), probed)
        assertEquals(setOf("a.example"), tracker.statuses.value.keys)
    }

    @Test
    fun recentResultsAreKeptUnlessForced() = runTest {
        var probes = 0
        val tracker = tracker({ probes++; working })

        tracker.test(listOf("a.example"))
        advanceUntilIdle()
        now += 30_000
        tracker.test(listOf("a.example"), maxAgeMs = 60_000)
        advanceUntilIdle()
        assertEquals(1, probes)

        now += 31_000
        tracker.test(listOf("a.example"), maxAgeMs = 60_000)
        advanceUntilIdle()
        assertEquals(2, probes)

        tracker.test(listOf("a.example"))
        advanceUntilIdle()
        assertEquals(3, probes)
    }

    @Test
    fun aRunningTestIsNotStartedAgain() = runTest {
        val answer = CompletableDeferred<ProbeResult>()
        var probes = 0
        val tracker = tracker({ probes++; answer.await() })

        tracker.test(listOf("a.example"))
        runCurrent()
        tracker.test(listOf("a.example"))
        runCurrent()
        answer.complete(working)
        advanceUntilIdle()

        assertEquals(1, probes)
    }

    @Test
    fun limitsConcurrentTests() = runTest {
        val answer = CompletableDeferred<ProbeResult>()
        var active = 0
        var maxActive = 0
        val tracker = tracker({
            active++
            maxActive = maxOf(maxActive, active)
            answer.await().also { active-- }
        }, maxConcurrent = 2)

        tracker.test(listOf("a.example", "b.example", "c.example", "d.example"))
        runCurrent()
        assertEquals(2, maxActive)
        // all are shown as testing, also the ones waiting for a slot
        assertEquals(List(4) { ServerStatus.Testing }, tracker.statuses.value.values.toList())

        answer.complete(working)
        advanceUntilIdle()
        assertEquals(4, tracker.statuses.value.values.count { it is ServerStatus.Tested })
    }
}
