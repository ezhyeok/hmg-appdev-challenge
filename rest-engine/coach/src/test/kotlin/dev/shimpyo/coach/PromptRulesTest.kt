package dev.shimpyo.coach

import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptRulesTest {
    private fun prompt(kind: PromptKind, userRequested: Boolean = false) =
        Prompt("p", kind, "…", stop = null, expiresAt = 0, userRequested = userRequested)

    @Test
    fun drivingQuestionsAreWithdrawnWhenParked() {
        for (k in listOf(PromptKind.LAST_CHANCE, PromptKind.REST_SUGGESTION, PromptKind.CHECK_IN)) {
            assertEquals(k.name, true, PromptRules.withdrawWhenParked(prompt(k), parked = true))
            assertEquals(k.name, false, PromptRules.withdrawWhenParked(prompt(k), parked = false))
        }
        for (k in listOf(PromptKind.URGENT_RESULT, PromptKind.FRESH_AIR, PromptKind.INFO)) {
            assertEquals(k.name, false, PromptRules.withdrawWhenParked(prompt(k), parked = true))
        }
    }

    @Test
    fun anAnswerToWhatSomeoneSaidStaysWhileParked() {
        // 엔진은 서 있는 동안 정차를 먼저 묻지 않지만 사용자의 말("아이가 화장실 가고 싶대", "쉬고 싶어")에는 서 있어도 답한다
        // (엔진 닫힘 검증 E6). 그 답은 주행 질문과 같은 종류(LAST_CHANCE, REST_SUGGESTION)로 온다: 종류만 보고 거두면 답이 보이지 않는다
        for (k in PromptKind.entries) {
            assertEquals(k.name, false, PromptRules.withdrawWhenParked(prompt(k, userRequested = true), parked = true))
            assertEquals(k.name, false, PromptRules.withdrawWhenParked(prompt(k, userRequested = true), parked = false))
        }
    }

    @Test
    fun onlyInfoIsSpokenWithoutACard() {
        for (k in PromptKind.entries) assertEquals(k.name, k == PromptKind.INFO, PromptRules.speakOnly(k))
    }
}
