package dev.shimpyo.app.ui

import androidx.compose.ui.graphics.vector.ImageVector
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.theme.DriveLimits
import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CoachTexts
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 화면 문구와 숫자 형식. 시간·거리 표기는 음성과 같도록 엔진의 [CoachTexts]를 쓴다.
 *
 * 엔진의 이유(Reason.text)는 음성·상세용 문장이라 길다. 주행 화면의 칩과 게이지 한 줄은
 * 한글 12자 안팎이어야 해서(DriveLimits.MAX_CHIP_CHARS) Reason.code로 짧은 문구를 만든다.
 * 질환 이름은 다른 탑승자도 보는 주행 화면에 드러내지 않는다("민감 탑승자").
 */
object Labels {
    private val texts = CoachTexts()

    /** 기본 설정값. 탑승자 유형별 기준 간격과 게이지 경계에 쓴다 */
    val config = CoachConfig()

    // ------------------------------------------------------------ 숫자

    fun duration(min: Int): String = texts.duration(min)

    fun distance(m: Double): String = texts.distance(m)

    /** 초 → "3분", "1시간 5분" (1분 미만은 1분) */
    fun minutesOf(seconds: Double): String = duration(max(1, (seconds / 60.0).roundToInt()))

    /** 108 → "1:48" */
    fun hhmm(min: Int): String = "%d:%02d".format(min / 60, min % 60)

    fun clock(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /** 목적격 조사: "안성휴게소를", "옥산휴게소(부산방향)을"이 아니라 괄호 앞 글자로 고른다 */
    fun withObject(word: String): String {
        val last = word.substringBefore('(').trim().lastOrNull() ?: return word
        val batchim = when (last) {
            in '가'..'힣' -> (last.code - 0xAC00) % 28 != 0
            in '0'..'9' -> last in "013678"
            else -> false
        }
        return word + if (batchim) "을" else "를"
    }

    // ------------------------------------------------------------ 게이지

    /** 게이지 숫자와 단위. 120분 이상이면 "2시간+" (screens.md 1.4) */
    data class Readout(val value: String, val unit: String)

    fun readout(minutes: Int?): Readout = when {
        minutes == null -> Readout("--", "분")
        minutes >= 120 -> Readout("2", "시간+")
        else -> Readout(minutes.toString(), "분")
    }

    fun levelWord(level: Level): String = when (level) {
        Level.OK -> "여유"
        Level.WATCH -> "확인"
        Level.SOON -> "곧"
        Level.NOW -> "지금"
    }

    /**
     * 그 탑승자의 화장실 기준 간격(분): 개인 간격(Occupant.intervalOverrideMin)이 있으면 그것, 없으면 유형별 사전값
     * (엔진 CoachConfig, SPEC 4.1). 게이지 막대의 분모와 탑승자 화면의 "N분마다"가 같은 값을 쓴다
     */
    fun baselineMin(o: Occupant?): Int = o?.intervalOverrideMin?.takeIf { it > 0.0 }?.roundToInt() ?: when (o?.type) {
        null, OccupantType.ADULT -> config.priorAdultMin
        OccupantType.ELDERLY -> config.priorElderlyMin
        OccupantType.OAB -> config.priorOabMin
        OccupantType.IBS -> config.priorIbsMin
        OccupantType.PREGNANT -> config.priorPregnantMin
        OccupantType.CHILD -> {
            val age = o.ageYears
            when {
                age == null || age <= 6 -> config.priorChildYoungMin
                age <= 12 -> config.priorChildOlderMin
                else -> config.priorAdultMin
            }
        }
        OccupantType.INFANT -> config.priorInfantMin
        OccupantType.DOG -> config.priorDogMin
    }.roundToInt()

    // ------------------------------------------------------------ 탑승자

    fun typeName(type: OccupantType): String = when (type) {
        OccupantType.ADULT -> "어른"
        OccupantType.CHILD -> "아이"
        OccupantType.INFANT -> "영아"
        OccupantType.PREGNANT -> "임신부"
        OccupantType.ELDERLY -> "어르신"
        OccupantType.OAB -> "과민성 방광"
        OccupantType.IBS -> "과민성 장"
        OccupantType.DOG -> "반려견"
    }

    fun typeIcon(type: OccupantType): ImageVector = when (type) {
        OccupantType.ADULT, OccupantType.OAB, OccupantType.IBS -> ShimpyoIcons.Person
        OccupantType.CHILD -> ShimpyoIcons.Child
        OccupantType.INFANT -> ShimpyoIcons.Infant
        OccupantType.PREGNANT -> ShimpyoIcons.Pregnant
        OccupantType.ELDERLY -> ShimpyoIcons.Elderly
        OccupantType.DOG -> ShimpyoIcons.Pets
    }

    /** 처리에 동의가 필요한 건강 관련 유형 (개인정보 보호법 제23조 민감정보) */
    val OccupantType.isHealth: Boolean
        get() = this == OccupantType.PREGNANT || this == OccupantType.OAB || this == OccupantType.IBS

    /** 공유 화면에서 질환 이름을 숨기는 유형 */
    private val OccupantType.isSensitive: Boolean
        get() = this == OccupantType.OAB || this == OccupantType.IBS

    /** 탑승자 화면의 이름 */
    fun occupantName(o: Occupant): String = o.label.ifBlank {
        if (o.type == OccupantType.CHILD && o.ageYears != null) "아이 ${o.ageYears}세" else typeName(o.type)
    }

    /** 주행 화면처럼 다른 사람도 보는 곳의 이름. 질환 이름 대신 "민감 탑승자" */
    fun publicName(o: Occupant): String = if (o.type.isSensitive) "민감 탑승자" else occupantName(o)

    /** "아이 동승" 같은 칩. 운전자 혼자면 null */
    fun companionChip(o: Occupant?): String? = when (o?.type) {
        null, OccupantType.ADULT -> null
        OccupantType.OAB, OccupantType.IBS -> "민감 탑승자"
        OccupantType.CHILD -> "아이 동승"
        OccupantType.INFANT -> "영아 동승"
        OccupantType.PREGNANT -> "임신부 동승"
        OccupantType.ELDERLY -> "어르신 동승"
        OccupantType.DOG -> "반려견 동승"
    }

    // ------------------------------------------------------------ 정차 후보

    fun kindName(kind: StopKind): String = when (kind) {
        StopKind.HIGHWAY_REST_AREA -> "휴게소"
        StopKind.DROWSY_SHELTER -> "졸음쉼터"
        StopKind.PUBLIC_RESTROOM -> "공중화장실"
        StopKind.GAS_STATION -> "주유소"
        StopKind.PUBLIC_PARKING -> "공영주차장"
        StopKind.OTHER -> "기타"
    }

    fun kindIcon(kind: StopKind): ImageVector = when (kind) {
        StopKind.HIGHWAY_REST_AREA -> ShimpyoIcons.Restaurant
        StopKind.DROWSY_SHELTER -> ShimpyoIcons.Rest
        StopKind.GAS_STATION -> ShimpyoIcons.GasStation
        StopKind.PUBLIC_PARKING -> ShimpyoIcons.Parking
        StopKind.PUBLIC_RESTROOM, StopKind.OTHER -> ShimpyoIcons.Wc
    }

    fun purposeName(p: Purpose): String = when (p) {
        Purpose.RESTROOM -> "화장실"
        Purpose.REST -> "휴식"
        Purpose.MEAL -> "식사"
        Purpose.FUEL -> "주유"
        Purpose.FEEDING -> "수유"
        Purpose.PET -> "반려견"
    }

    fun purposeIcon(p: Purpose): ImageVector = when (p) {
        Purpose.RESTROOM -> ShimpyoIcons.Wc
        Purpose.REST -> ShimpyoIcons.Rest
        Purpose.MEAL -> ShimpyoIcons.Restaurant
        Purpose.FUEL -> ShimpyoIcons.GasStation
        Purpose.FEEDING -> ShimpyoIcons.Breastfeeding
        Purpose.PET -> ShimpyoIcons.Pets
    }

    /** 표시 순서 */
    fun sortedPurposes(purposes: Set<Purpose>): List<Purpose> = Purpose.entries.filter { it in purposes }

    /** 차에서 그곳까지 거리. 경로 거리를 모르면 직선거리 × 도로계수에 "약"을 붙인다 */
    fun stopDistance(stop: StopCandidate): String? =
        stop.routeDistanceM?.let { distance(it) }
            ?: stop.straightDistanceM?.let { "약 " + distance(it * config.roadFactor) }

    /** 진행 방향 표시: "경로 위", "진행 방향", "+4분 우회", "유턴 필요". 모르면 null */
    fun stopPosition(stop: StopCandidate): String? = when {
        stop.directionOk == false -> "유턴 필요"
        stop.detourS >= 60.0 -> "+${(stop.detourS / 60.0).roundToInt()}분 우회"
        stop.directionOk == true ->
            if (stop.kind == StopKind.HIGHWAY_REST_AREA || stop.kind == StopKind.DROWSY_SHELTER) "경로 위" else "진행 방향"
        else -> null
    }

    /** "4.2km · 3분 · 경로 위" */
    fun stopMeta(stop: StopCandidate): String =
        listOfNotNull(stopDistance(stop), stop.etaS?.let { minutesOf(it) }, stopPosition(stop)).joinToString(" · ")

    // ------------------------------------------------------------ 이유 → 짧은 문구

    /**
     * 칩·게이지 한 줄에 쓰는 짧은 문구. [who]는 화장실 게이지를 정한 탑승자의 공개 이름이다.
     * 결과는 늘 [DriveLimits.MAX_CHIP_CHARS]자 이하다(넘치면 짧은 대체 문구, 그래도 넘치면 null).
     * 모르는 코드는 null이다: 원문을 그대로 내보내면 새 코드의 문장이 질환 이름을 흘릴 수 있다(UI 감사 #17).
     * 엔진이 내는 코드는 모두 여기나 [NOT_CHIP_CODES]에 있어야 한다(ReasonCoverageTest가 엔진 소스로 지킨다).
     */
    fun shortReason(r: Reason, who: String? = null): String? = when (r.code) {
        "RESTROOM_ELAPSED" -> fitChip("${who ?: "탑승자"} 기준", "탑승자 기준")
        "RESTROOM_VOICED" -> fitChip("${who ?: "탑승자"} 요청", "탑승자 요청")
        "RESTROOM_PERSONAL" -> firstNumber(r.text, "번")?.let { "기록 ${it}번 반영" } ?: "내 기록 반영"
        "RESTROOM_NONE" -> "탑승자 설정 필요"
        // 정차 간 상한(카시트·걷기). 원문에 탑승자 이름(질환 이름일 수 있다)이 있으므로 시간만 쓴다
        "RESTROOM_CAP" -> durationIn(r.text)?.let { d ->
            if (r.text.contains("카시트")) chipWithDuration("카시트 ", d, " 상한") else chipWithDuration("", d, "마다 걷기")
        } ?: "정차 간격 상한"
        // 맥락 요인 (엔진 RestroomNeedModel): 식사 뒤에는 조금 일찍, 밤에 잠든 아이는 여유를 더 둔다
        "RESTROOM_AFTER_MEAL" -> "식사 뒤"
        "RESTROOM_ASLEEP" -> who?.let { fitChip("$it 자는 중", "자는 중") } ?: "자는 중"
        "FATIGUE_VOICED" -> "졸리다고 하심"
        "FATIGUE_STRONG" -> "커피·낮잠 권장"
        "FATIGUE_DRIVING" -> durationIn(r.text)?.let { "연속 ${if (it >= 60) hhmm(it) else "${it}분"}" } ?: "연속 운전"
        "FATIGUE_NIGHT" -> "새벽 2~6시"
        "FATIGUE_AFTERNOON" -> "오후 2~4시"
        "FATIGUE_BODY_CLOCK" -> "졸음 오는 시간대"
        "FATIGUE_AWAKE_LONG" -> firstNumber(r.text, "시간")?.let { fitChip("깬 지 ${it}시간+", "오래 깨어 있음") } ?: "오래 깨어 있음"
        "FATIGUE_SHORT_SLEEP" -> "수면 부족"
        "FATIGUE_MONOTONY" -> "단조로운 주행"
        "FATIGUE_STYLE", "STYLE_STEERING_REVERSAL", "STYLE_STEERING_MICRO", "STYLE_SPEED", "STYLE_BRAKE" -> "운전 패턴 변화"
        "STYLE_BASELINE" -> "운전 패턴 익히는 중"
        "FATIGUE_RECIRC", "CABIN_RECIRC" -> "환기 권장"
        "CABIN_COOL_DOWN" -> "온도 낮추기 권장"
        "NEXT_ACCEPTED" -> "들르기로 함"
        "NEXT_NEAREST_RESTROOM" -> "가장 빨리 가는 곳"
        // 말로 한 요청("화장실 가고 싶대", 수유·반려견)에 답한 곳. 질문 카드에서는 맥락 줄이 이 말을 한다
        "NEXT_REQUESTED_RESTROOM" -> "요청하신 화장실"
        "NEXT_REQUESTED_CARE" -> "요청하신 곳"
        // 맥락 줄이 이미 "정체 전 마지막 화장실"·"화장실 공백 전 마지막 기회"라서 칩은 길이만 보인다
        "LAST_CHANCE_CONGESTION" -> durationIn(r.text)?.let { chipWithDuration("정체 ", it, "") } ?: "정체 전 마지막"
        "LAST_CHANCE_GAP" -> durationIn(r.text)?.let { chipWithDuration("공백 ", it, "") } ?: "공백 전 마지막"
        "LAST_CHANCE_PREFERENCE" -> "기준보다 긴 공백"
        // 게이지(가장 급한 한 사람)는 넉넉한데 여럿이 타서 앞당겨 묻는 경우: 왜 묻는지 보인다 (엔진 GroupNeedTest, 감사 O6)
        "LAST_CHANCE_GROUP" -> firstNumber(r.text, "명")?.let { "${it}명이 타서" } ?: "여럿이 타서"
        "NEXT_REST" -> "휴식 권유"
        "NEXT_MEAL" -> "식사 가능"
        "URGENT_OPEN_24H" -> "24시간"
        "URGENT_OPEN" -> "영업 중"
        "URGENT_OPEN_UNKNOWN" -> "영업 여부 모름"
        "URGENT_DIRECTION" -> "진행 방향"
        "URGENT_UTURN" -> "유턴 필요"
        "URGENT_SAFETY" -> when {
            r.text.contains("비상벨") && r.text.contains("CCTV") -> "비상벨·CCTV"
            r.text.contains("비상벨") -> "비상벨"
            else -> "CCTV"
        }
        "URGENT_DIAPER" -> "교환대"
        else -> null
    }?.takeIf { it.length <= DriveLimits.MAX_CHIP_CHARS }

    /** 칩으로 쓰지 않는 엔진 코드 (계획 메모는 정차 화면에 문장으로, 급해 소요 시간은 결과 카드의 구성 줄로 보인다) */
    val NOT_CHIP_CODES: Set<String> = setOf(
        "PLAN_NO_CANDIDATES", "PLAN_NOW", "PLAN_GAP", "PLAN_FUEL_SHORT", "PLAN_NONE_NEEDED", "PLAN_MEAL", "PLAN_FUEL",
        "PLAN_SLEEPY_TIME", "PLAN_NO_DESTINATION", "PLAN_PARTIAL", "URGENT_TIME",
    )

    /** [text]가 칩 길이를 넘으면 [fallback] */
    private fun fitChip(text: String, fallback: String): String = if (text.length <= DriveLimits.MAX_CHIP_CHARS) text else fallback

    /** "정체 1시간 30분"처럼 우리말 시간이 들어가면 그대로, 넘치면 "정체 1:30"처럼 줄인다 */
    private fun chipWithDuration(prefix: String, min: Int, suffix: String): String {
        val full = "$prefix${duration(min)}$suffix"
        return if (full.length <= DriveLimits.MAX_CHIP_CHARS || min < 60) full else "$prefix${hhmm(min)}$suffix"
    }

    /** "1시간 48분째" → 108, "40분 정체" → 40. 없으면 null */
    internal fun durationIn(text: String): Int? {
        val m = DURATION.find(text) ?: return null
        val h = m.groupValues[1].toIntOrNull()
        val min = m.groupValues[2].toIntOrNull() ?: m.groupValues[3].toIntOrNull()
        return when {
            h != null -> h * 60 + (min ?: 0)
            min != null -> min
            else -> null
        }
    }

    private fun firstNumber(text: String, suffix: String): Int? =
        numberBefore.getOrPut(suffix) { Regex("(\\d+)\\s*$suffix") }.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** 접미어별 정규식 (부를 때마다 컴파일하지 않게) */
    private val numberBefore = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private val DURATION = Regex("(\\d+)시간(?:\\s*(\\d+)분)?|(\\d+)분")
}
