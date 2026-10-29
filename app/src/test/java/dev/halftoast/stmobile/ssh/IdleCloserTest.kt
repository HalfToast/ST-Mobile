package dev.halftoast.stmobile.ssh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleCloserTest {
    private var closes = 0

    private fun TestScope.closer() = IdleCloser(backgroundScope, 60_000) { isIdle -> if (isIdle()) closes++ }

    @Test fun closesAfterSixtySecondsWithoutSessions() = runTest {
        val c = closer()
        c.begin(); c.end()
        advanceTimeBy(59_000); runCurrent()
        assertEquals(0, closes)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, closes)
    }

    @Test fun neverClosesUnderARunningSession() = runTest {
        val c = closer()
        c.begin() // e.g. a 5-minute Update, or a log stream
        advanceTimeBy(600_000); runCurrent()
        assertEquals(0, closes)
        c.onBackground(); runCurrent()
        assertEquals(0, closes)
        c.end()
        advanceTimeBy(61_000); runCurrent()
        assertEquals(1, closes)
    }

    @Test fun newSessionCancelsThePendingClose() = runTest {
        val c = closer()
        c.begin(); c.end()
        advanceTimeBy(50_000)
        c.begin(); c.end() // a poll every few seconds keeps it open
        advanceTimeBy(50_000); runCurrent()
        assertEquals(0, closes)
        advanceTimeBy(11_000); runCurrent()
        assertEquals(1, closes)
    }

    @Test fun overlappingSessionsCloseOnlyAfterTheLastEnds() = runTest {
        val c = closer()
        c.begin(); c.begin()
        c.end()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, closes)
        c.end()
        advanceTimeBy(61_000); runCurrent()
        assertEquals(1, closes)
    }

    @Test fun backgroundClosesAtOnceWhenIdle() = runTest {
        val c = closer()
        c.begin(); c.end()
        c.onBackground(); runCurrent()
        assertEquals(1, closes)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, closes) // the idle timer was cancelled, no second close
    }

    @Test fun closeDecisionRechecksUnderTheLock() = runTest {
        // A session that begins after the timer fired but before the lock check is not cut off.
        var sawBusy = false
        lateinit var c: IdleCloser
        c = IdleCloser(backgroundScope, 60_000) { isIdle ->
            c.begin() // races in
            sawBusy = !isIdle()
            if (isIdle()) closes++
        }
        c.begin(); c.end()
        advanceTimeBy(61_000); runCurrent()
        assertEquals(true, sawBusy)
        assertEquals(0, closes)
    }
}
