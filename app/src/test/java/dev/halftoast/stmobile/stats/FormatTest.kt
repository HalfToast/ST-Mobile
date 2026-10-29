package dev.halftoast.stmobile.stats

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun neverForZero() = assertEquals("Never", formatLastActive(0))

    @Test fun localTime() =
        assertEquals("2026-01-01 01:00", formatLastActive(1767225600L, ZoneId.of("Europe/Berlin")))
}
