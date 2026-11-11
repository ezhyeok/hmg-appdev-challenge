package dev.shimpyo.coach.voice

import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.VoiceIntent

/*
 * 임시 파일 (코어 수정 브랜치 wt-corefix). 코어가 쓰는 말 해석 API 둘의 타입과 얇은 임시 구현이다.
 * 정식 구현은 IntentParser 담당이 IntentParser의 멤버 함수(replyTo, parseSpoken)로 넣는다. 그 브랜치를 합칠 때 이 파일을 지운다:
 * 타입(CardReply, Spoken)은 그쪽 것을 쓰고, 멤버 함수가 아래 확장 함수보다 먼저 불린다.
 */

/**
 * 카드 질문에 한 말 하나를 읽은 것. 대답은 하나다: [other]는 그 대답과 다른, 분명하고 부정되지 않은 요청일 때만 있다
 * (리뷰 R-F3: "아니 안 급해"가 거절하고 급해를 열었고, "응 휴게소 들르자"가 수락한 뒤 "쉬어 갈까요?"를 또 물었다)
 */
data class CardReply(val answer: YesNo, val other: VoiceIntent?)

/** 마이크 버튼으로 들은 말 하나를 읽은 것 */
sealed interface Spoken {
    /** 엔진·코어가 처리하는 의도 (질문 대답, 급해, 졸려, 얼마나 남았어 …) */
    data class Intent(val intent: VoiceIntent) : Spoken
    /** "아이 잠들었어" / "깼어": 코치가 드는 상태이고 엔진 의도가 아니다. 누구인지 모르면 occupantId = null */
    data class Asleep(val occupantId: String?, val asleep: Boolean) : Spoken
}

/**
 * 임시 구현: 응·아니가 분명하면 그 말은 대답 하나로만 읽는다(다른 요청을 붙이지 않는다). 대답이 아니면 예전처럼 요청으로 읽는다.
 * 정식 구현은 "아니 화장실 급해"처럼 대답 뒤의 분명한 다른 요청을 [CardReply.other]로 돌려준다 ([cardStopName]으로 카드의 장소를
 * 가리킨 말을 가려낸다).
 */
@Suppress("UNUSED_PARAMETER")
fun IntentParser.replyTo(text: String?, occupants: List<Occupant>, cardStopName: String?): CardReply {
    val answer = yesNo(text)
    if (answer != YesNo.UNKNOWN) return CardReply(answer, null)
    val other = parse(text, occupants, activePromptId = null)?.takeIf { it !is VoiceIntent.Accept && it !is VoiceIntent.Decline }
    return CardReply(answer, other)
}

/** 임시 구현: 예전 parse 그대로, 못 읽은 말에서 잠듦·깸만 더 본다 (누구인지는 가리지 않는다) */
fun IntentParser.parseSpoken(text: String?, occupants: List<Occupant>, activePromptId: String?): Spoken? {
    parse(text, occupants, activePromptId)?.let { return Spoken.Intent(it) }
    val t = text?.replace(Regex("[\\s.,!?~]"), "") ?: return null
    return when {
        AWAKE.any { it in t } -> Spoken.Asleep(null, asleep = false)
        ASLEEP.any { it in t } -> Spoken.Asleep(null, asleep = true)
        else -> null
    }
}

private val ASLEEP = listOf("잠들", "자고있", "자는중", "자요", "잠잔다", "잠자")
private val AWAKE = listOf("깼", "깨어났", "일어났")
