package dev.halftoast.stmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class BackPressPolicyTest {
    @Test fun pageConsumes() = assertEquals(BackAction.ConsumedByPage, BackPressPolicy.decide(true, null, 10_000))
    @Test fun firstUnconsumedShowsHint() = assertEquals(BackAction.ShowPressAgainHint, BackPressPolicy.decide(false, null, 10_000))
    @Test fun secondWithinWindowLeaves() = assertEquals(BackAction.MoveTaskToBack, BackPressPolicy.decide(false, 9_000, 10_000))
    @Test fun secondAfterWindowHintsAgain() = assertEquals(BackAction.ShowPressAgainHint, BackPressPolicy.decide(false, 7_000, 10_000))
}
