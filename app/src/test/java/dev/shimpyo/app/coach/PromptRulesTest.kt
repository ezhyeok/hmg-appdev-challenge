package dev.shimpyo.app.coach

import dev.shimpyo.engine.PromptKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptRulesTest {
    @Test
    fun drivingQuestionsAreWithdrawnWhenParked() {
        for (k in listOf(PromptKind.LAST_CHANCE, PromptKind.REST_SUGGESTION, PromptKind.CHECK_IN)) {
            assertEquals(k.name, true, PromptRules.withdrawWhenParked(k, parked = true))
            assertEquals(k.name, false, PromptRules.withdrawWhenParked(k, parked = false))
        }
        for (k in listOf(PromptKind.URGENT_RESULT, PromptKind.FRESH_AIR, PromptKind.INFO)) {
            assertEquals(k.name, false, PromptRules.withdrawWhenParked(k, parked = true))
        }
    }
}
