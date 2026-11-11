package dev.shimpyo.coach

import dev.shimpyo.engine.PromptKind

/** 질문 종류별 규칙 (PromptMachine). 순수 규칙이라 PromptRulesTest로 지킨다 */
object PromptRules {
    /** 달리는 동안에만 뜻이 있는 질문 (앞쪽 정차·휴식·상태 확인) */
    private val DRIVING_KINDS = setOf(PromptKind.LAST_CHANCE, PromptKind.REST_SUGGESTION, PromptKind.CHECK_IN)

    /**
     * 차를 P로 세웠으면 달리는 동안의 질문은 거둔다. 예전에는 앱이 막 켜졌을 때 나온 '혹시 졸리세요?'가 휴게소에 서 있는
     * 동안에도 남아 있었다(2026-11-11 에뮬레이터). 급해 결과·환기·안내는 서 있어도 쓸모가 있어 남긴다.
     */
    fun withdrawWhenParked(kind: PromptKind, parked: Boolean): Boolean = parked && kind in DRIVING_KINDS

    /**
     * 말로만 알리고 카드·마이크를 쓰지 않는 질문. 안내(INFO, "얼마나 남았어?"의 대답 등)가 마이크를 열고 45초 동안 다른 질문을
     * 막던 것(서비스 감사 D16)을 막는다.
     */
    fun speakOnly(kind: PromptKind): Boolean = kind == PromptKind.INFO
}
