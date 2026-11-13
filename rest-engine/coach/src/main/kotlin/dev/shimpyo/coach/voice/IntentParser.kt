package dev.shimpyo.coach.voice

import dev.shimpyo.coach.StopNames
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.VoiceIntent

enum class YesNo { YES, NO, UNKNOWN }

/**
 * 질문 카드에 한 대답. 대답은 하나다: [other]는 같은 말에 든 '카드와 다른 요청'이고, 분명히 말했고 부정하지 않았고
 * 카드의 정차가 풀어 주지 못하는 것만 담는다 ("아니 화장실 급해" → NO + 급해, "응 휴게소 들르자" → YES뿐, "아니 안 급해" → NO뿐).
 */
data class CardReply(val answer: YesNo, val other: VoiceIntent?)

/** 마이크로 들은 말의 뜻. 엔진에 넘길 의도이거나, 코치가 직접 다루는 '누가 자고 있다·깼다' */
sealed interface Spoken {
    data class Intent(val intent: VoiceIntent) : Spoken

    /**
     * [occupantId]: 말이 가리킨 탑승자. 아무도 가리키지 않았으면 한 명뿐인 아이. null이면 코어가 고른다
     * (아이가 여럿이거나 설정에 없다: 잠들었다면 가장 그럴듯한 아이, 깼다면 자고 있던 아이).
     */
    data class Asleep(val occupantId: String?, val asleep: Boolean) : Spoken
}

/**
 * 규칙 기반 한국어 의도 분류. LLM이 없거나 실패해도 이것만으로 동작한다. 주행 중 말은 짧은 대답과 정해진 요청이 대부분이다.
 * 규칙과 예문 표는 docs/design/coach-layer/voice.md, 표 그대로의 테스트는 IntentParserTest.
 *
 * 원칙
 * - 띄어쓰기와 문장부호를 지운 글자열에서 찾는다 (STT는 띄어쓰기를 제멋대로 준다).
 * - 낱말이 있어도 부정했거나("안 급해", "급하지 않아", "화장실은 괜찮아") 이미 끝난 일이면("밥 먹었어") 요청이 아니다.
 *   뒤에 오는 "~은 괜찮아", "~ 안 해도 돼", 끝난 일은 일·장소 이름(화장실, 산책, 식사)에만 본다: "급해, 안 가면 안 돼"는 급하다는 말이다.
 * - 필요를 말했으면 필요다. 묻기만 했으면("화장실까지 얼마나 남았어?") 되묻기다: 화장실·휴게소라는 낱말만으로는 필요가 아니다.
 * - 수락은 분명할 때만이다. 틀린 수락은 내비에 경유지를 넣고, 틀린 '모름'은 버튼을 기다릴 뿐이다.
 */
class IntentParser {

    fun yesNo(text: String?): YesNo = flat(text)?.let { answer(it, asked(text)) } ?: YesNo.UNKNOWN

    /**
     * 자유 발화(마이크 버튼)를 엔진 의도로 바꾼다. 알 수 없으면 null.
     * [activePromptId]가 있으면(카드가 떠 있다) 대답은 Accept/Decline이 된다. 의도 하나만 돌려주므로 거절과 다른 요청이 함께 있으면
     * 요청이 먼저고("아니 화장실 급해" → 급해), 수락과 함께면 급해만 먼저다. 카드의 음성 대답은 둘 다 돌려주는 [replyTo]로 받는다.
     */
    fun parse(text: String?, occupants: List<Occupant>, activePromptId: String?): VoiceIntent? {
        val t = flat(text) ?: return null
        val request = request(t, occupants)
        if (activePromptId == null) return request?.intent ?: hint(t, occupants)
        return when (answer(t, asked(text))) {
            // "응 휴게소 들르자", "응 화장실 가자": 수락하면서 한 말은 수락이다. 새 요청으로 넘기면 엔진이 한 번 더 묻고 필요를 잘못 배운다.
            // 급해만은 삼키지 않는다: 어떤 카드인지 모르는 채로 "응 화장실 급해"를 환기 수락으로 끝내면 안 된다
            YesNo.YES -> request?.takeIf { it.kind == Kind.URGENT }?.intent ?: VoiceIntent.Accept(activePromptId)
            YesNo.NO -> request?.takeIf { !it.byPlaceWord && !putOff(t, it) }?.intent ?: VoiceIntent.Decline(activePromptId)
            YesNo.UNKNOWN -> request?.intent ?: hint(t, occupants)
        }
    }

    /**
     * 카드의 음성 질문에 한 대답. [cardStopName]: 카드가 묻는 정차의 이름 (정차 없는 카드면 null).
     * - 수락하면 그 정차에 가서 풀리는 요청(화장실·급해·휴식·졸림·남은 시간)은 전하지 않는다. 식사·수유·주유는 그곳이 휴게소일 때만 풀린다.
     *   정차 없는 카드(환기 등)를 수락했으면 쉬자는 말만 빼고 전한다.
     * - 거절하거나 대답이 아니면 분명한 요청만 전한다. 휴게소·화장실이라는 낱말만 있는 말은 카드를 되받은 것이고,
     *   "다음 휴게소에서", "나중에"는 지금 할 일이 아니고, 카드의 정차 이름을 댄 휴식 요청은 그 정차 이야기다.
     */
    fun replyTo(text: String?, occupants: List<Occupant>, cardStopName: String?): CardReply {
        val t = flat(text) ?: return CardReply(YesNo.UNKNOWN, null)
        val answer = answer(t, asked(text), stopKey = cardStopName?.let(StopNames::key))
        val request = request(t, occupants) ?: return CardReply(answer, null)
        val forward = when (answer) {
            YesNo.YES ->
                if (cardStopName == null) request.kind != Kind.REST && request.kind != Kind.SLEEPY
                else request.kind in NEEDS_A_REST_AREA && StopNames.facilityKind(cardStopName) != StopKind.HIGHWAY_REST_AREA
            else -> !request.byPlaceWord && !putOff(t, request) && !(request.kind == Kind.REST && names(t, cardStopName))
        }
        return CardReply(answer, request.intent.takeIf { forward })
    }

    /**
     * 마이크로 들은 말. "아이 잠들었어", "자고 있어", "깼어" 같은 말은 [Spoken.Asleep](엔진 의도가 아니라 코치가 든다),
     * 나머지는 [parse]와 같다. 화장실 같은 요청이 함께 있으면 요청이 먼저다("아이가 깼는데 화장실 가고 싶대").
     */
    fun parseSpoken(text: String?, occupants: List<Occupant>, activePromptId: String?): Spoken? {
        val t = flat(text) ?: return null
        val sleep = sleepStatement(t, occupants)
        if (sleep != null) {
            val request = request(t, occupants)
            // 누가 잔다는 말에 섞인 '피곤', '졸려'는 그 사람 이야기다 ("피곤했는지 잠들었어")
            if (request == null || request.aboutTiredness) return sleep
        }
        return parse(text, occupants, activePromptId)?.let { Spoken.Intent(it) }
    }

    // ------------------------------------------------------------ 예·아니요

    /**
     * [question]: 물음표로 끝난 말. [stopKey]: 카드가 묻는 정차 이름의 고유 부분("옥산"). "옥산 가자"를 수락으로 듣는 데 쓴다.
     */
    private fun answer(t: String, question: Boolean, stopKey: String? = null): YesNo {
        // 묻는 말은 대답이 아니다: "그래, 얼마나 걸려?"에 경유지를 넣지 않고 "다음 휴게소가 어디야?"에 카드를 닫지 않는다.
        // STT가 물음표를 주면 "네?"(못 들었다)도 수락으로 듣지 않는다
        if (question || HOW_LONG.containsMatchIn(t) || ASKS.containsMatchIn(t)) return YesNo.UNKNOWN
        // 거절이 수락 낱말을 품고 있어서 거절을 먼저 본다 ("안 들러도 돼", "그냥 가자"). "다음 휴게소 가자"도 지금은 아니라는 뜻이다
        if (refused(t) || LATER.containsMatchIn(t)) return YesNo.NO
        if (YES_START.containsMatchIn(t) || YES_ANY.containsMatchIn(t) || GO_THERE_PLAIN.containsMatchIn(t) || goesThere(t, stopKey)) return YesNo.YES
        return YesNo.UNKNOWN
    }

    private fun asked(text: String?): Boolean = text?.trimEnd()?.let { it.endsWith('?') || it.endsWith('？') } == true

    /** 거절하는 말이 있는지. '안'으로 시작하는 거절 낱말은 "미안 가자"의 안이 아닐 때만 */
    private fun refused(t: String): Boolean {
        if (t == "no" || t == "노" || NO_MORE.containsMatchIn(t)) return true
        return NO.any { word ->
            var i = t.indexOf(word)
            while (i >= 0 && word[0] == '안' && endsAnotherWord(t, i)) i = t.indexOf(word, i + 1)
            i >= 0
        }
    }

    /**
     * "가자", "가줘"가 거기로 가자는 뜻인지. 앞말이 대답·정차·'들렀다'일 때만 그렇다("응 가자", "휴게소 가자", "쉬었다 가자").
     * 그 밖의 "가자"는 계속 가자는 말일 수 있고("조용히 가자", "바로 가자") "아이가 자"일 수도 있어서 수락으로 보지 않는다.
     */
    private fun goesThere(t: String, stopKey: String?): Boolean {
        // "가자 가자"는 "고고"처럼 계속 가자는 말일 수도 있다 (발화 묶음 2026-11-13)
        if (REPEATED_GO.matches(t)) return false
        return GO_VERB.findAll(t).any { m ->
            val before = t.substring(0, m.range.first)
            // "옥산으로 가자": 정차 이름 뒤의 조사를 떼고 본다
            val place = PLACE_PARTICLE.replace(before, "")
            before.isEmpty() || GO_THERE_AFTER.containsMatchIn(before) || (stopKey != null && stopKey.length >= 2 && place.endsWith(stopKey))
        }
    }

    // ------------------------------------------------------------ 요청

    private enum class Kind { FEEDING, PET, URGENT, RESTROOM, SLEEPY, FUEL, MEAL, REST, HOW_LONG }

    private class Request(
        val kind: Kind,
        val intent: VoiceIntent,
        /** "휴게소 가자"처럼 장소 낱말로 알아들은 휴식 요청. 카드 대답에서는 카드의 정차를 되받은 말이다 */
        val byPlaceWord: Boolean = false,
        /** 졸림·피로에서 나온 요청이고 운전자가 자신을 가리키지 않았다. 누가 잔다는 말과 함께면 그 사람 이야기다 */
        val aboutTiredness: Boolean = false,
    )

    /** 분명히 말한 요청. 필요가 여럿이면 급한 순서(영아·반려견 → 급해 → 화장실 → 졸림 → 주유 → 식사 → 휴식), 필요가 없고 묻기만 했으면 되묻기 */
    private fun request(t: String, occupants: List<Occupant>): Request? {
        val who = who(t, occupants)
        val asks = HOW_LONG.containsMatchIn(t)
        // 되묻는 구절을 뺀 나머지에서 필요를 찾는다: "얼마나 더 가야 돼"의 '가야'는 필요가 아니다
        val s = if (asks) HOW_LONG.replace(t, "?") else t

        // 영아: 수유·기저귀. 아기가 배고프면 식당이 아니라 수유실이다
        val babyTalk = who.type == OccupantType.INFANT && (who.occupant == null || who.occupant.type == OccupantType.INFANT)
        // "기저귀 가방"은 물건 이름이다 (발화 묶음 2026-11-13: "기저귀 가방은 트렁크에 넣었어"를 수유 요청으로 읽었다)
        if (said(s.replace("기저귀가방", "가방"), FEEDING, topic = true) || (babyTalk && hungry(s))) {
            val baby = who.occupant?.takeIf { it.type == OccupantType.INFANT || it.type == OccupantType.CHILD }
                ?: occupants.firstOrNull { it.type == OccupantType.INFANT }
            return Request(Kind.FEEDING, VoiceIntent.OccupantNeed(baby?.id, Purpose.FEEDING))
        }

        // 반려견: 사람 화장실을 찾는 급해 모드가 아니다
        val urgent = urgent(s)
        if (OccupantWords.has(t, OccupantWords.DOG) && (urgent || said(s, PET_RELIEF, topic = true))) {
            return Request(Kind.PET, VoiceIntent.OccupantNeed(occupants.firstOrNull { it.type == OccupantType.DOG }?.id, Purpose.PET))
        }
        if (urgent) return Request(Kind.URGENT, VoiceIntent.UrgentRestroom)

        // 화장실: "마려워"처럼 그 자체가 필요인 말이거나, 화장실을 말하고 가겠다는 말이 있을 때
        val toilet = said(s, TOILET, topic = true) || PEE.containsMatchIn(s)
        if (said(s, HOLD) || SEEK_TOILET.containsMatchIn(s) || (toilet && said(s, GO))) {
            return Request(Kind.RESTROOM, VoiceIntent.OccupantNeed(who.occupant?.id, Purpose.RESTROOM))
        }

        // 졸림·피로: 다른 탑승자 이야기면 운전자의 졸림이 아니다. 피곤한 탑승자는 쉬고 싶은 것이고, 졸린 탑승자는 자면 된다
        val tired = said(s, TIRED)
        if (tired || said(s.replace("졸음쉼터", "쉼터"), DROWSY)) {
            val self = SELF.any { it in t }
            // "나도 졸려"라고 자신을 가리켰으면 누가 잔다는 말과 함께여도 운전자의 졸림이다
            if (who.type == null || self) return Request(Kind.SLEEPY, VoiceIntent.Sleepy, aboutTiredness = !self)
            if (tired) return Request(Kind.REST, VoiceIntent.WantRest, aboutTiredness = true)
        }

        // 주유·충전 (휴대폰 충전이 아니다)
        val fuelPlace = said(s, FUEL_PLACE, topic = true)
        val fuelTalk = fuelPlace || FUEL_WORD.any { it in s }
        if (fuelTalk && PHONE.none { it in s } && ((fuelPlace && said(s, GO_PLACE)) || said(s, FUEL_NEED) || FUEL_LOW.containsMatchIn(t))) {
            return Request(Kind.FUEL, VoiceIntent.OccupantNeed(null, Purpose.FUEL))
        }

        if (hungry(s)) return Request(Kind.MEAL, VoiceIntent.Hungry)

        // 휴식: 쉬자는 말, 또는 휴게소·쉼터에 가자는 말
        if (said(s, REST) || said(s, REST_NOUN, topic = true)) return Request(Kind.REST, VoiceIntent.WantRest)
        if (said(s, REST_PLACE, topic = true) && said(s, GO_PLACE) && !refused(t)) {
            return Request(Kind.REST, VoiceIntent.WantRest, byPlaceWord = true)
        }
        // "주유소 말고 휴게소 들르자": '말고'는 앞말을 물리고, 뒤에 진짜 요청이 온다 (발화 묶음 2026-11-13)
        val afterBut = s.substringAfterLast("말고", "")
        if (afterBut.isNotEmpty() && said(afterBut, REST_PLACE, topic = true) && said(afterBut, GO_PLACE) && !refused(afterBut)) {
            return Request(Kind.REST, VoiceIntent.WantRest, byPlaceWord = true)
        }

        // 묻기만 했다. 연료량은 코치가 모른다: 화장실 여유를 답하면 틀린 답이다
        if (asks) return if (fuelTalk) null else Request(Kind.HOW_LONG, VoiceIntent.HowLong)
        return null
    }

    /** 낱말만 말한 것("화장실", "휴게소", "주유소"). 버튼을 누르고 한 말일 때만 요청으로 본다 (카드 대답에서는 되물음이다) */
    private fun hint(t: String, occupants: List<Occupant>): VoiceIntent? = when {
        bare(t, TOILET) -> VoiceIntent.OccupantNeed(who(t, occupants).occupant?.id, Purpose.RESTROOM)
        bare(t, REST_PLACE) -> VoiceIntent.WantRest
        bare(t, FUEL_PLACE) -> VoiceIntent.OccupantNeed(null, Purpose.FUEL)
        else -> null
    }

    private fun bare(t: String, words: List<String>): Boolean {
        val word = words.firstOrNull { affirmedAt(t, it, topic = true) >= 0 } ?: return false
        return FILLER.replace(PERSON.replace(t.replace(word, ""), ""), "").isEmpty()
    }

    private fun hungry(t: String): Boolean = said(t, HUNGRY) || said(t, EAT, topic = true) || MISSED_MEAL.containsMatchIn(t)

    /** 화장실이 급하다는 말인지. "급하게 가지 마", "시간이 급해"는 서두른다는 말이다 */
    private fun urgent(t: String): Boolean = URGENT.findAll(t).any { m ->
        val hurry = m.value.startsWith("급") && HURRY_BEFORE.any { t.substring(0, m.range.first).endsWith(it) }
        !hurry && !denied(t, m.range.first, m.range.last + 1)
    }

    /** "다음 휴게소에서 쉬자", "나중에 화장실 갈래": 지금 할 일이 아니다. 남은 시간을 묻는 것은 지금 답할 수 있다 */
    private fun putOff(t: String, request: Request): Boolean = request.kind != Kind.HOW_LONG && LATER.containsMatchIn(t)

    /** 말에 카드의 정차 이름이 나왔는지 ("옥산휴게소 말고 다른 데서 쉬자") */
    private fun names(t: String, cardStopName: String?): Boolean {
        val key = cardStopName?.let(StopNames::key) ?: return false
        return key.length >= 2 && key in t
    }

    // ------------------------------------------------------------ 부정

    /**
     * [words] 가운데 부정하지 않고 말한 것이 있는지.
     * [topic]: 낱말이 일·장소의 이름(화장실, 산책, 식사, 휴게소)이다. 이름은 뒤에 오는 말로도 부정된다: "화장실은 괜찮아", "휴게소 말고",
     * "산책 안 해도 돼", 그리고 이미 끝난 일("밥 먹었어", "산책했어"). 상태를 말하는 낱말(급해, 졸려, 배고파)에는 쓰지 않는다:
     * "급해, 안 가면 안 돼"는 급하다는 말이고 "깜빡 졸았어"는 지금 위험하다는 말이다.
     */
    private fun said(t: String, words: List<String>, topic: Boolean = false): Boolean = words.any { affirmedAt(t, it, topic) >= 0 }

    /** [word]가 부정되지 않고 나온 자리. 없으면 -1 */
    private fun affirmedAt(t: String, word: String, topic: Boolean = false): Int {
        var i = t.indexOf(word)
        while (i >= 0) {
            if (!denied(t, i, i + word.length, topic)) return i
            i = t.indexOf(word, i + 1)
        }
        return -1
    }

    /** [from, to)의 낱말을 부정했는지: 앞의 '안'("안 급해"), 뒤의 '-지 않아'·'~건 아니야', [topic]이면 [TOPIC_OFF]도 본다 */
    private fun denied(t: String, from: Int, to: Int, topic: Boolean = false): Boolean {
        if (from > 0 && t[from - 1] == '안' && !endsAnotherWord(t, from - 1)) return true
        val after = t.substring(to, minOf(t.length, to + LOOK_AHEAD))
        return NEGATED.containsMatchIn(after) || (topic && TOPIC_OFF.containsMatchIn(after))
    }

    /** t[i]의 '안'이 "미안"처럼 다른 낱말의 끝인지. "미안 급해"는 급하다는 말이다 */
    private fun endsAnotherWord(t: String, i: Int): Boolean = ENDS_IN_AN.any { i + 1 >= it.length && t.startsWith(it, i + 1 - it.length) }

    // ------------------------------------------------------------ 누구

    /** [type]: 말에 나온 탑승자 유형 (아무도 가리키지 않았으면 null). [occupant]: 그 말이 가리키는 설정된 탑승자 */
    private class Who(val type: OccupantType?, val occupant: Occupant?)

    private fun who(t: String, occupants: List<Occupant>): Who {
        // 첫 번째 어른은 운전자다 (Settings 기본값, OccupantPhrase)
        val others = if (occupants.firstOrNull()?.type == OccupantType.ADULT) occupants.drop(1) else occupants
        // 화면 이름을 그대로 말했다 ("둘째", "할머니")
        others.mapNotNull { o -> flat(o.label)?.takeIf { it.length >= 2 }?.let { o to it } }
            .sortedByDescending { it.second.length }
            .firstOrNull { OccupantWords.indexOf(t, it.second) >= 0 }
            ?.let { return Who(it.first.type, it.first) }
        val children = others.filter { it.type == OccupantType.CHILD }
        val infant = others.firstOrNull { it.type == OccupantType.INFANT }
        for ((words, pick) in BIRTH_ORDER) {
            if (words.any { it in t }) return Who(OccupantType.CHILD, pick(children) ?: infant)
        }
        val type = OccupantWords.typeIn(t) ?: return Who(null, null)
        val occupant = when (type) {
            // 아기를 아이로, 아이를 아기로 부르는 일이 많아서 서로 대신한다
            OccupantType.INFANT -> infant ?: children.firstOrNull()
            OccupantType.CHILD -> children.firstOrNull() ?: infant
            else -> others.firstOrNull { it.type == type }
        }
        return Who(type, occupant)
    }

    // ------------------------------------------------------------ 잠

    private fun sleepStatement(t: String, occupants: List<Occupant>): Spoken.Asleep? {
        val asleep = when {
            STILL_ASLEEP.containsMatchIn(t) -> true
            AWAKE.any { it in t } || NOT_SLEEPING.containsMatchIn(t) -> false
            ASLEEP.any { it in t } || SOMEONE_SLEEPS.containsMatchIn(t) || JUST_SLEEPS.matches(t) -> true
            else -> return null
        }
        val who = who(t, occupants)
        val children = occupants.filter { it.type == OccupantType.CHILD || it.type == OccupantType.INFANT }
        return when {
            who.occupant != null && SEVERAL.none { it in t } -> Spoken.Asleep(who.occupant.id, asleep)
            // 설정에 없는 어른·반려견 이야기를 아이에게 붙이지 않는다
            who.occupant == null && who.type != null && who.type != OccupantType.CHILD && who.type != OccupantType.INFANT -> null
            // 아무도 가리키지 않았거나 여럿을 말했다: 아이가 한 명이면 그 아이, 아니면 코어가 고른다
            else -> Spoken.Asleep(children.singleOrNull()?.id, asleep)
        }
    }

    private fun flat(text: String?): String? = text?.lowercase()?.replace(NOISE, "")?.takeIf { it.isNotEmpty() }

    private companion object {
        val NOISE = Regex("[\\s\\p{Punct}…·“”‘’]")
        /** 낱말 뒤 이만큼 안에서 부정·완료를 찾는다 */
        const val LOOK_AHEAD = 12

        // ---- 예·아니요
        val NO = listOf(
            "아니", "아뇨", "아냐", "아닙", "괜찮아", "괜찮습니다", "괜찮거든", "괜찮으니", "괜찮을",
            "괜찮다니까", "괜찮다고", "괜찮다는", "괜찮대", "괜찮은것같", "괜찮은거같", "귀찮",
            "됐어", "됐습니다", "됐고", "됐거든", "싫", "스킵", "넘어가", "통과", "취소", "그만", "별로", "굳이", "생각없", "필요없",
            "말고", "말자", "지마", "지말", "그냥가", "계속가", "쭉가", "더가", "집에가", "가던길", "갈길", "지나쳐", "지나치",
            "안들러", "안들르", "안들를", "안가", "안갈", "안간", "안해", "안할", "안쉬", "안쉴", "안돼", "안먹", "안좋",
            "안급", "급하지않", "급하진않", "안마려", "안졸", "안피곤", "안배고", "참을수있", "참을게", "참을만", "참을래", "버틸", "노노",
            "싶지않", "싶진않",
        )
        /**
         * 거절 가운데 앞뒤를 봐야 하는 말: "참자"(급하다는 "못 참아"가 아닐 때), 말끝의 "괜찮은데"("난 괜찮은데 애가 가고 싶대"는
         * 거절이 아니다), "패스"("하이패스"가 아닐 때), "지나가자"("지나가는 길에 들르자"가 아닐 때)
         */
        val NO_MORE = Regex("(?<!못)참(자|아봐|으라|아야|아$)|괜찮은데(요)?$|(?<!하이)패스|지나가(?!는길|다가|면서|는김)")
        /** 지금은 아니라는 말. "뒤에 다 자"(뒷자리)의 '뒤에'는 아니다 */
        val LATER = Regex(
            "다음|나중|이따|있다가|좀있다|조금있다|(분|시간|조금|좀|잠시|금)(뒤|후)에|도착해서|도착하고|도착하면|더가서|더가다|만더|깨면|일어나면" +
                "|(빠지|풀리|뚫리)(고나서|고서|면|고)",
        )
        /** 묻는 말 (남은 시간·거리는 [HOW_LONG]). "어디든 들르자"는 묻는 말이 아니다 */
        val ASKS = Regex("어디(야|지|에|있|인|쯤|냐|예요|에요)|뭐(야|지|냐|예요|에요)|이름이|왜|(야|어야)(하나|되나|할까|될까)$|(하나|되나|려나|을까|를까)$")
        /** 말머리의 대답. 같은 글자로 시작하는 다른 말은 뺀다: 응가·응급, 네비·네 시, 예정, 그래도, 좋아하는, 콜라 */
        val YES_START = Regex(
            "^(어|아|음|오)?(응(?!가|급|원)|웅|엉|네(?!비|가|시|번|명|개|살)|넵|넹|예(?!정|상|전|를|약|쁘)|옙|그래(?!도|서|야|프)|그럼요?$|그러자|그러죠|그럽시다" +
                "|좋아(?!하|해)|좋지(?!않|는않|도않|못)|좋죠|좋습니다|좋네|좋은데|오케이|오키|오케|ok|okay|yes|예스|ㅇㅇ|ㅇㅋ|콜(?!라|택)|당연|물론|알았어|알겠)|^어+$",
        )
        /** 어디에 있어도 수락인 말. 뜻이 갈리는 "가자"는 [goesThere]가 앞말을 보고 정한다 */
        val YES_ANY = Regex(
            "들르자|들러|들렀다가|들를게|들를래|들을게|들을께|들릅시다|그러자|그렇게해|그렇게하자|그게좋겠|그게낫겠|(그러|들르|가|쉬)지뭐" +
                "|갔다가.{0,4}(출발|가자|가)" +
                "|(들르면|들르는게|들렀으면|쉬면|쉬는게|쉬었으면)좋(겠|을)" +
                "|좋아(?!하|해)|좋습니다|좋지(?!않|는않|도않|못)|좋죠|좋네|좋은데|좋은생각|부탁|넣어(?!야|도)|추가해|안내해|쉬자|쉬어가자|쉬었다가|세워줘|들어가자",
        )
        val GO_VERB = Regex("가자|가줘|가주|갑시다|가보자|가요$")
        /** 이 말 바로 뒤의 "가자"는 거기로 가자는 말이다: 대답, 정차·거기, 들렀다·쉬었다 */
        val GO_THERE_AFTER = Regex(
            "(응|어|엉|웅|네|예|그래|그럼|좋아|오케이|그러자|휴게소|쉼터|화장실|주유소|충전소|거기|그곳|저기|여기|그리|글로|이리|그쪽|저쪽)(에|로|으로|에서)?(한번|같이|빨리|얼른)?$" +
                "|(들렀다|쉬었다|쉬고|들러서|들르고|보고|먹고|넣고|하고|다녀|갔다|보내고)$",
        )
        /** "거기로 가": 말끝의 '가'는 거기·정차 낱말 바로 뒤일 때만 거기로 가자는 말이다 (발화 묶음 2026-11-13) */
        val GO_THERE_PLAIN = Regex("(거기|그곳|그리|그쪽|글로|휴게소|쉼터|화장실)(에|로|으로)?가$")
        /** "가자 가자", "가자가자가자" */
        val REPEATED_GO = Regex("^(가자){2,}$")
        /** 정차 이름 뒤에 붙는 조사 ("옥산으로 가자") */
        val PLACE_PARTICLE = Regex("(으로|로|에|까지)$")

        // ---- 부정
        /** '안'으로 끝나는 낱말. 그 뒤에 온 말은 부정이 아니다 */
        val ENDS_IN_AN = listOf("미안", "천안", "불안", "편안", "동안")
        /** 낱말 바로 뒤의 부정 (모든 낱말) */
        val NEGATED = Regex(
            "^(하|이)?(지|진|지는|지도)(않|못|말|마)" +                                         // 급하지 않아, 가고 싶지 않아, 들르지 말자
                "|^.{0,5}?(아니야|아닌데|아니고|아니라|아니에요|아닙니다|아니거든|아니지만)",     // 급한 건 아니야
        )
        /** 일·장소 이름 바로 뒤에서 그 일을 하지 않겠다거나 이미 했다는 말 */
        val TOPIC_OFF = Regex(
            "^(은|는|이|가|을|를|도)?(말고|괜찮|됐어|됐고|필요없)" +                             // 화장실은 괜찮아, 휴게소 말고
                // 화장실 안 가도 돼, 산책 안 해도 돼, 점심 안 먹어도 돼. "점심 안 먹었어"는 배고프다는 말이라 넣지 않는다
                "|^(은|는|이|가|을|를|도|에)?안(가|갈|들|해|할|쉬|갈아|넣|시켜|먹어도|먹여도|먹일|먹을|먹어$|먹는다|먹고싶)" +
                "|^(은|는|이|가|을|를|도|에)?(아까|방금|이미|벌써)?" +
                "(갔다왔|다녀왔|갔어|갔었|갔는데|했어|했는데|했다|했습니다|시켰|갈았|갈아줬|갈아놨|먹였|먹었|넣었|끝났|들렀어|들렀는데)" + // 화장실 갔다 왔어, 산책했어
                "|^(었|였)(어|다|는데|지|고|습니다|네|거든|잖아|잖)",                            // 밥 먹'었어', 먹'었잖아'
        )

        // ---- 되묻기
        val HOW_LONG = Regex(
            "(얼마나|얼마|몇분|몇시간|몇킬로|몇키로|몇km)(쯤|나|정도|이나)?(더|나더)?(가야|가면|걸|남|있어야|돼|되|가)" +
                "|몇(킬로|키로|km|분|시간)(미터)?(야|이야|쯤|정도|인데|이나|지)|몇시(에|쯤)?(도착|와|가)|언제(쯤|나와|도착|까지|가|쉬|들|야|와)" +
                "|남았(어|지|나|니|죠|냐|습니까|는지|을까)|남은(시간|거리)|멀었|아직멀|다와가|(?<!갔)(?<!거의)다왔(어|나|니)|어디쯤",
        )

        // ---- 화장실
        /**
         * 낱말 자체가 화장실이 급하다는 말. '급하게', '급한 일'은 서두른다는 말이고 '비쌀 것 같아'는 값 이야기다.
         * 말끝의 '화장실(이) 급'은 STT가 끝 글자를 자른 "화장실 급해"다 (2026-11-13 에뮬레이터 STT가 "화장실 급"을 돌려줬다)
         */
        val URGENT = Regex(
            "급해|급하(?!게)|급한(?!일|약속|전화|용무|게)|급함|급합|급똥|못참|참을수없|참기힘|(?<!비)쌀(것|거)같|지릴|지리겠|화장실빨리|빨리화장실" +
                "|(화장실|소변|볼일|대변|오줌)(이|가)?급$|(방광|오줌|소변)(이|가)?터질(것|거)같",
        )
        /** 이 말 뒤의 '급해'는 화장실이 아니다. "볼일이 급해"는 화장실이라 '일이'는 넣지 않는다 */
        val HURRY_BEFORE = listOf("시간이", "시간", "마음이", "마음", "갈길이")
        /** '화장시'는 STT가 '화장실'을 잘못 들은 것이다 (발화 묶음 2026-11-13) */
        val TOILET = listOf("화장실", "화장시", "소변", "오줌", "응가", "똥", "대변", "볼일", "쉬야")
        /** 소변을 뜻하는 '쉬'. 쉬다(쉬자, 쉬고 싶어)와 다음 글자로 가른다 */
        val PEE = Regex("쉬(?=하|할|한|해|마려|마렵|싸|좀하|좀싸)")
        /** 화장실 낱말 없이도 화장실인 말 */
        val HOLD = listOf("마려", "마렵")
        /** 낱말만으로 화장실을 찾는 말: "근처 화장실", "미안한데 화장실 좀" */
        val SEEK_TOILET = Regex("(근처|가까운|주변)(에)?(있는)?화장실|(화장실|소변|볼일)좀$")
        /** 화장실 낱말과 함께면 가겠다는 말 */
        val GO = listOf(
            "가고싶", "가고파", "가야", "갈래", "가자", "가줘", "가주", "가도돼", "가도될", "간대", "간다는", "갈게",
            "들르", "들러", "들를", "들을래", "들을게", "들렀다", "다녀오", "다녀올", "갔다오", "갔다올", "찾아", "찾네", "찾는다", "찾는데", "찾더",
            "어디", "세워", "보내",
            "하고싶", "한대", "할래", "해야", "하자", "보고싶", "봐야", "보고올", "보고와", "보러", "싸야", "쌀래", "누고싶", "눠야",
        )

        // ---- 영아·반려견
        /** '젖었어'(옷이 젖다)와 겹치지 않게 '젖'만으로는 받지 않는다 */
        val FEEDING = listOf("기저귀", "수유", "분유", "이유식", "맘마", "젖병", "젖먹", "젖줘", "젖물", "모유")
        val PET_RELIEF = listOf("쉬", "산책", "화장실", "배변", "볼일", "오줌", "똥", "응가", "마려", "마렵", "낑낑")

        // ---- 졸림·휴식
        val DROWSY = listOf(
            "졸려", "졸리", "졸린", "졸음", "졸았", "졸뻔", "졸고", "졸다가", "꾸벅", "잠와", "잠이와", "잠온", "잠이온",
            "잠들것같", "잠들거같", "잠들겠", "잠들뻔", "눈이감", "눈감", "감겨", "감긴다", "눈꺼풀", "하품", "자고싶", "잠깨야", "잠좀깨", "잠을깨",
        )
        val TIRED = listOf("피곤", "지치", "지쳤")
        /** 운전자 자신을 가리키는 말. "아이도 자고 나도 졸려"는 운전자의 졸림이다 */
        val SELF = listOf("나도", "나는", "내가", "저도", "저는", "제가")
        val REST = listOf(
            "쉬고싶", "쉬자", "쉬어가", "쉬었다가", "쉬었다갈", "쉴래", "쉬어야", "좀쉬자", "좀쉬고", "좀쉬어", "좀쉬었으면", "좀쉴",
            "쉬고가", "쉬다가", "한숨자", "눈좀붙", "눈붙이", "자고가",
            // 쉬자고 하지는 않았지만 차에서 내리고 싶다는 말
            "스트레칭", "바람쐬", "바람좀쐬", "허리아파", "허리가아파", "다리저려", "다리가저려", "엉덩이아파", "뻐근", "커피한잔", "커피마시고싶", "커피사",
            "다리좀펴", "다리펴", "기지개",
        )
        /** 이름으로 말한 휴식 ("휴식이 필요해". "휴식 필요 없어"는 아니다) */
        val REST_NOUN = listOf("휴식")
        /** '휴개소'는 STT가 '휴게소'를 잘못 들은 것이다 */
        val REST_PLACE = listOf("휴게소", "휴개소", "쉼터")
        /** 장소 낱말과 함께면 거기 가자는 말 */
        val GO_PLACE = listOf("가자", "가줘", "가주", "가고싶", "가야", "갈래", "들르", "들러", "들를", "들렀다", "들어가", "세워", "찾아", "어디")

        // ---- 식사·주유
        val HUNGRY = listOf("배고파", "배고프", "배고픈", "배가고", "출출", "허기", "땡기", "땡긴", "땡겨")
        /** 끼니를 거른 말: "아침도 못 먹고 나왔어", "저녁 안 먹었잖아". "점심 안 먹어도 돼"는 아니다 */
        val MISSED_MEAL = Regex("(아침|점심|저녁|밥)(도|을|은|는)?(못|안)먹(고|었|은|어서)")
        /** 먹자는 말. 이미 먹었으면("밥 먹었어") 요청이 아니다 */
        val EAT = listOf("밥먹", "밥좀먹", "밥도먹", "밥이나먹", "밥을먹", "식사", "점심", "저녁먹", "아침먹", "간식", "뭐좀먹", "뭐먹", "먹고가", "먹고싶")
        val FUEL_PLACE = listOf("주유소", "충전소")
        val FUEL_WORD = listOf("기름", "주유", "충전", "연료", "배터리", "전기")
        /** "기름 얼마 안 남았어": 남은 양을 묻는 말이 아니라 넣어야 한다는 말 */
        val FUEL_LOW = Regex("(기름|주유|충전|연료|배터리|전기)(이|가|도|은|는)?(얼마|거의|별로)?(안남|없)")
        val FUEL_NEED = listOf(
            "넣어야", "넣자", "넣고", "넣을", "넣어줘", "넣으러", "없어", "없다", "없네", "없는데", "떨어", "부족", "바닥",
            "해야", "하자", "하고가", "하러", "채워", "간당", "경고등",
        )
        val PHONE = listOf("핸드폰", "휴대폰", "폰", "전화기", "노트북", "보조배터리")
        /** 휴게소에서만 풀리는 요청 (졸음쉼터·공중화장실에는 식당·수유실·주유소가 없다) */
        val NEEDS_A_REST_AREA = setOf(Kind.MEAL, Kind.FEEDING, Kind.FUEL)

        // ---- 누구
        val BIRTH_ORDER: List<Pair<List<String>, (List<Occupant>) -> Occupant?>> = listOf(
            listOf("첫째", "큰애", "큰아이", "큰딸", "큰아들") to { c -> c.firstOrNull() },
            listOf("둘째") to { c -> c.getOrNull(1) ?: c.lastOrNull() },
            listOf("셋째") to { c -> c.getOrNull(2) ?: c.lastOrNull() },
            listOf("막내", "작은애", "작은아이", "작은딸", "작은아들") to { c ->
                if (c.isNotEmpty() && c.all { it.ageYears != null }) c.minByOrNull { it.ageYears!! } else c.lastOrNull()
            },
        )
        private val PEOPLE: String = with(OccupantWords) { INFANT + CHILD + ELDER + DOG + ADULT + PREGNANT + "애" }
            .sortedByDescending { it.length }.joinToString("|")
        val PERSON = Regex(PEOPLE)
        /** 낱말만 말했는지 볼 때 지우는 군말 */
        val FILLER = Regex("졸음|다음|가까운|근처|여기|지금|빨리|잠깐|좀|이요|요|은|는|이|가|을|를|에|로|도|나|저")

        // ---- 잠
        /** "아직 안 깼어", "아직 자": 여전히 잔다 */
        val STILL_ASLEEP = Regex("안깼|안일어났|(아직|아직도|계속)(자$|자요|자네|잔다|자고있)")
        val AWAKE = listOf("깼어", "깼다", "깼네", "깼는데", "깼나", "깼습니다", "깨어났", "깨났", "일어났", "일어나셨", "깨셨", "눈떴", "안잠들", "잠을안자", "잠안자")
        val NOT_SLEEPING = Regex("(?<![미천불편동])안(자$|자요|자네|잔다|자고(?!싶)|자는)")
        /** 누가 자고 있다는 말. 운전자 자신의 말("잠들 것 같아", "자고 싶어", "자고 가자")은 들어 있지 않다 */
        val ASLEEP = listOf(
            "잠들었", "잠이들었", "잠들어버렸", "잠든것같", "잠든거같", "잠든듯", "자고있", "자는중", "자는거같", "자는것같", "자나봐", "자나보",
            "곯아떨어", "꿈나라", "주무시", "주무셔", "주무세", "재웠",
        )
        /** "아이가 자", "애기 자요", "첫째는 벌써 잔다", "아이가 자서". "아이 자리", "아이가 자고 싶대"는 아니다 */
        val SOMEONE_SLEEPS = Regex(
            "($PEOPLE)(들이|들은|들도|들|이|가|는|도|은)?(지금|방금|막|이제|벌써|또|다|아직)?" +
                "(자$|자요|자네|잔다|잔대|자더라|자서|자니까|자길래|자느라|자는(?!게|편)|자고(?!싶))",
        )
        /** 주어 없이 "자요", "잔다", "다 자", "뒤에 다 자" (말 전체가 이 꼴일 때만) */
        val JUST_SLEEPS = Regex(
            "(뒤|뒷자리|뒷좌석|옆)?(에|에서)?(지금|방금|막|이제|벌써|또|다|다들|둘다|모두)?(자요|자네|자네요|잔다|잔대)" +
                "|(뒤|뒷자리|뒷좌석|옆)?(에|에서)?(다|다들|둘다|모두)자",
        )
        val SEVERAL = listOf("애들", "다들", "둘다", "모두", "전부", "다자", "다잠")
    }
}
