package dev.halftoast.stmobile.logs

import org.junit.Assert.assertEquals
import org.junit.Test

class AppendCappedTest {
    @Test fun keepsNewest() {
        val r = appendCapped(listOf("a", "b", "c"), "d", max = 3)
        assertEquals(listOf("b", "c", "d"), r)
    }
    @Test fun underCapAppends() = assertEquals(listOf("a", "b"), appendCapped(listOf("a"), "b", max = 3))
}
