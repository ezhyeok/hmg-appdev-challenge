package dev.shimpyo.coach

import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind

/** 질문 종류별 규칙 (PromptMachine). 순수 규칙이라 PromptRulesTest로 지킨다 */
object PromptRules {
    /** 달리는 동안에만 뜻이 있는 질문 (앞쪽 정차·휴식·상태 확인): 엔진이 먼저 건 것일 때 */
    private val DRIVING_KINDS = setOf(PromptKind.LAST_CHANCE, PromptKind.REST_SUGGESTION, PromptKind.CHECK_IN)

    /**
     * 차를 P로 세웠으면 달리는 동안의 질문은 거둔다. 예전에는 앱이 막 켜졌을 때 나온 '혹시 졸리세요?'가 휴게소에 서 있는
     * 동안에도 남아 있었다(2026-11-11 에뮬레이터). 급해 결과·환기·안내는 서 있어도 쓸모가 있어 남긴다.
     *
     * 사용자의 말에 대한 답([Prompt.userRequested])은 종류와 상관없이 남긴다: 엔진은 서 있는 동안 정차를 먼저 묻지 않지만
     * "아이가 화장실 가고 싶대", "쉬고 싶어"에는 서 있어도 답하고, 그 답은 주행 질문과 같은 종류(LAST_CHANCE, REST_SUGGESTION)로 온다.
     * 종류만 보고 거두면 꽉 막힌 정체에서 P로 서서 한 말에 게이지만 바뀌고 답이 보이지 않는다 (엔진 닫힘 검증 E6).
     */
    fun withdrawWhenParked(prompt: Prompt, parked: Boolean): Boolean = parked && !prompt.userRequested && prompt.kind in DRIVING_KINDS

    /**
     * 말로만 알리고 카드·마이크를 쓰지 않는 질문. 안내(INFO, "얼마나 남았어?"의 대답 등)가 마이크를 열고 45초 동안 다른 질문을
     * 막던 것(서비스 감사 D16)을 막는다.
     */
    fun speakOnly(kind: PromptKind): Boolean = kind == PromptKind.INFO
}
