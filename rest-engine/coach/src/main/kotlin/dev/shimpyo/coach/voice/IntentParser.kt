package dev.shimpyo.coach.voice

import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.VoiceIntent

enum class YesNo { YES, NO, UNKNOWN }

/**
 * 규칙 기반 한국어 의도 분류. LLM이 없거나 실패해도 이것만으로 동작한다.
 * 주행 중 대화는 짧은 답("응", "아니")과 정해진 요청이 대부분이라 규칙으로 충분히 받는다.
 */
class IntentParser {

    fun yesNo(text: String?): YesNo {
        val t = normalize(text) ?: return YesNo.UNKNOWN
        // "안 들러"처럼 긍정 단어를 포함한 부정이 있어서 부정을 먼저 본다
        if (NO.any { t.contains(it) }) return YesNo.NO
        if (YES.any { t.contains(it) }) return YesNo.YES
        return YesNo.UNKNOWN
    }

    /**
     * 자유 발화를 엔진 의도로 바꾼다. 질문에 대한 대답이면 [activePromptId]로 Accept/Decline을 만든다.
     * 알 수 없으면 null.
     */
    fun parse(text: String?, occupants: List<Occupant>, activePromptId: String?): VoiceIntent? {
        val t = normalize(text) ?: return null
        if (URGENT.any { t.contains(it) }) return VoiceIntent.UrgentRestroom

        val who = mentionedOccupant(t, occupants)
        val purpose = when {
            FEEDING.any { t.contains(it) } -> Purpose.FEEDING
            who?.type == OccupantType.DOG && PET_RELIEF.any { t.contains(it) } -> Purpose.PET
            RESTROOM.any { t.contains(it) } -> Purpose.RESTROOM
            else -> null
        }
        if (purpose != null) return VoiceIntent.OccupantNeed(who?.id, purpose)

        if (SLEEPY.any { t.contains(it) }) return VoiceIntent.Sleepy
        if (HOW_LONG.any { t.contains(it) }) return VoiceIntent.HowLong
        if (HUNGRY.any { t.contains(it) }) return VoiceIntent.Hungry
        if (REST.any { t.contains(it) }) return VoiceIntent.WantRest

        if (activePromptId != null) {
            when (yesNo(text)) {
                YesNo.YES -> return VoiceIntent.Accept(activePromptId)
                YesNo.NO -> return VoiceIntent.Decline(activePromptId)
                YesNo.UNKNOWN -> Unit
            }
        }
        return null
    }

    /** 발화에서 탑승자 유형을 찾아 설정된 탑승자와 맞춘다 */
    private fun mentionedOccupant(t: String, occupants: List<Occupant>): Occupant? {
        val type = when {
            INFANT_WORDS.any { t.contains(it) } -> OccupantType.INFANT
            CHILD_WORDS.any { t.contains(it) } -> OccupantType.CHILD
            ELDER_WORDS.any { t.contains(it) } -> OccupantType.ELDERLY
            DOG_WORDS.any { t.contains(it) } -> OccupantType.DOG
            PREGNANT_WORDS.any { t.contains(it) } -> OccupantType.PREGNANT
            else -> return null
        }
        return occupants.firstOrNull { it.type == type }
            // 아기를 아이로 부르는 경우가 많아서 서로 대신한다
            ?: if (type == OccupantType.INFANT) occupants.firstOrNull { it.type == OccupantType.CHILD } else null
            ?: if (type == OccupantType.CHILD) occupants.firstOrNull { it.type == OccupantType.INFANT } else null
    }

    private fun normalize(text: String?): String? =
        text?.lowercase()?.replace(Regex("[\\s.,!?~]"), "")?.takeIf { it.isNotEmpty() }

    private companion object {
        val NO = listOf("아니", "괜찮", "됐어", "싫어", "나중", "패스", "안들", "안가", "그냥가", "계속가", "말고", "노노", "no")
        val YES = listOf("응", "어어", "네", "예", "그래", "좋아", "들러", "들르", "가자", "오케이", "ok", "콜", "그러자", "부탁", "ㅇㅇ", "당연", "가줘", "넣어")
        val URGENT = listOf("급해", "급하", "급함", "못참", "쌀것같", "지릴", "화장실빨리", "빨리화장실", "화장실급")
        val RESTROOM = listOf("화장실", "소변", "오줌", "쉬마려", "쉬하고", "응가", "똥", "대변", "볼일")
        val FEEDING = listOf("기저귀", "수유", "분유", "젖", "이유식")
        val PET_RELIEF = listOf("쉬", "산책", "화장실", "배변", "볼일")
        val SLEEPY = listOf("졸려", "졸리", "졸음", "피곤", "눈이감", "잠와", "잠이와", "하품")
        val HOW_LONG = listOf("얼마나남", "몇분", "언제나와", "언제쯤", "얼마나더", "남았어")
        val HUNGRY = listOf("배고파", "배고프", "밥먹", "식사", "점심", "저녁먹", "간식", "출출")
        val REST = listOf("쉬고싶", "쉬자", "쉬어가", "좀쉬", "휴게소", "쉼터")
        val INFANT_WORDS = listOf("아기", "애기", "갓난", "신생아")
        val CHILD_WORDS = listOf("아이", "애가", "애들", "첫째", "둘째", "셋째", "딸", "아들", "꼬맹이")
        val ELDER_WORDS = listOf("할머니", "할아버지", "어르신", "부모님", "어머님", "아버님")
        val DOG_WORDS = listOf("강아지", "멍멍", "반려견", "댕댕", "개가")
        val PREGNANT_WORDS = listOf("임산부", "임신", "만삭")
    }
}
