package dev.shimpyo.app.ui.preview

import dev.shimpyo.app.coach.CoachUiState
import dev.shimpyo.app.coach.StopLog
import dev.shimpyo.app.coach.VoiceState
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gauge
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PitStopPlan
import dev.shimpyo.engine.PlannedStop
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.RankedStop
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.Recommendation
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 미리보기·스크린샷용 화면 상태. 시연 시나리오(SPEC 7장: 귀성길 정체, 오후 휴식 권유, 도심 급해, 휴식 기록)와 같은 장면이다.
 * 장소 이름과 거리는 경부고속도로 남행을 단순화한 예시값이다.
 */
object Samples {
    private val now = System.currentTimeMillis()

    val driver = Occupant("driver", OccupantType.ADULT, label = "운전자")
    val kid = Occupant("kid", OccupantType.CHILD, ageYears = 7, label = "7살 아이")
    val baby = Occupant("baby", OccupantType.INFANT, label = "아기")
    val dog = Occupant("dog", OccupantType.DOG)

    private val restArea = Facilities(food = true, fuel = true, diaperTable = true, nursingRoom = true, accessible = true, open24h = true)

    private fun ra(id: String, name: String, km: Double, kind: StopKind = StopKind.HIGHWAY_REST_AREA, f: Facilities = restArea) = StopCandidate(
        id = id, name = name, kind = kind, lat = 37.0, lon = 127.1, facilities = f, parkable = true, openNow = true,
        routeDistanceM = km * 1000, etaS = km * 1000 / 25.0, directionOk = true,
    )

    val anseong = ra("ra-anseong", "안성휴게소(부산방향)", 4.2)
    val cheonan = ra("ra-cheonan", "천안삼거리휴게소(부산방향)", 33.0)
    val manghyang = ra("ra-manghyang", "망향휴게소(부산방향)", 45.5)
    val shelter = ra("ds-cheongju", "청주 졸음쉼터", 57.0, StopKind.DROWSY_SHELTER, Facilities(restroom = true))
    val oksan = ra("ra-oksan", "옥산휴게소(부산방향)", 68.0)
    val jukam = ra("ra-jukam", "죽암휴게소(부산방향)", 96.0)

    private val ahead = listOf(anseong, cheonan, manghyang, shelter, oksan, jukam)

    /** 차가 앞으로 간 만큼 경로 거리를 줄인다 */
    private fun StopCandidate.shift(km: Double): StopCandidate {
        val d = routeDistanceM!! + km * 1000
        return copy(routeDistanceM = d, etaS = d / 25.0)
    }

    private val congestion = listOf(
        CongestionSegment(startDistanceM = 7_200.0, lengthM = 18_000.0, durationS = 40 * 60.0, type = "HEAVY", roadName = "경부고속도로", startName = "안성IC", endName = "북천안IC"),
    )

    /** 귀성길 정체: 아이 동승, 안성휴게소가 정체 전 마지막 화장실이라 묻는 중 (주행 중) */
    val holidayPrompt = CoachUiState(
        serviceRunning = true,
        restroom = Gauge(18, Level.SOON, listOf(Reason("RESTROOM_ELAPSED", "7살 아이 기준, 마지막 화장실 후 1시간 42분 지났어요")), drivenBy = "kid"),
        fatigue = Gauge(72, Level.OK, listOf(Reason("FATIGUE_DRIVING", "48분째 쉬지 않고 운전 중이에요"))),
        next = Recommendation(anseong, setOf(Purpose.RESTROOM), listOf(Reason("LAST_CHANCE_CONGESTION", "이곳을 지나면 40분 정체 구간에 화장실이 없어요"))),
        prompt = Prompt("lc-anseong", PromptKind.LAST_CHANCE, "앞에 40분 정체가 있어요. 다음 안성휴게소가 정체 전 마지막 화장실이에요. 들를까요?", anseong, now + 45_000),
        parked = false,
        drivingRestricted = true,
        speedKph = 92f,
        guiding = true,
        destinationName = "부산역",
        remainDistanceM = 312_000.0,
        remainTimeS = 4 * 3600.0 + 10 * 60,
        congestion = congestion,
        ahead = ahead,
        occupants = listOf(driver, kid),
        voice = VoiceState.LISTENING,
        demo = "holiday",
        status = "정차 후보 데이터 18,412곳",
    )

    /** 평소 주행: 질문 없음, 다음 화장실만 보여 준다 */
    val cruising = holidayPrompt.copy(
        restroom = Gauge(52, Level.OK, listOf(Reason("RESTROOM_ELAPSED", "운전자 기준, 마지막 화장실 후 2시간 8분 지났어요")), drivenBy = "driver"),
        fatigue = Gauge(31, Level.WATCH, listOf(Reason("FATIGUE_DRIVING", "1시간 29분째 쉬지 않고 운전 중이에요"))),
        next = Recommendation(manghyang.shift(-33.0), setOf(Purpose.RESTROOM), listOf(Reason("NEXT_NEAREST_RESTROOM", "가장 빨리 갈 수 있는 화장실이에요"))),
        prompt = null,
        congestion = emptyList(),
        ahead = listOf(manghyang, shelter, oksan, jukam).map { it.shift(-33.0) },
        occupants = listOf(driver),
        voice = VoiceState.IDLE,
        demo = null,
    )

    /** 오후 휴식 권유: 연속 1시간 46분, 오후 2~4시, 운전 패턴 변화로 휴식을 앞당김 */
    val fatiguePrompt = cruising.copy(
        restroom = Gauge(96, Level.OK, listOf(Reason("RESTROOM_ELAPSED", "운전자 기준, 마지막 화장실 후 1시간 24분 지났어요")), drivenBy = "driver"),
        fatigue = Gauge(
            14, Level.SOON,
            listOf(
                Reason("FATIGUE_DRIVING", "1시간 46분째 쉬지 않고 운전 중이에요"),
                Reason("FATIGUE_AFTERNOON", "오후 2~4시는 졸음이 오기 쉬운 시간이에요"),
                Reason("FATIGUE_STYLE", "운전 패턴이 이번 주행 초반과 달라 휴식을 앞당겼어요"),
            ),
        ),
        next = Recommendation(manghyang.copy(routeDistanceM = 8_100.0, etaS = 330.0), setOf(Purpose.REST, Purpose.RESTROOM), listOf(Reason("NEXT_REST", "쉬어 가기 좋은 가장 가까운 곳이에요"))),
        prompt = Prompt("rest-1", PromptKind.REST_SUGGESTION, "1시간 46분째 쉬지 않으셨어요. 8.1km 앞 망향휴게소에서 쉬어 갈까요?", manghyang.copy(routeDistanceM = 8_100.0, etaS = 330.0), now + 45_000),
        voice = VoiceState.SPEAKING,
        demo = "fatigue",
    )

    private fun place(id: String, name: String, kind: StopKind, m: Double, f: Facilities, open: Boolean? = true, dir: Boolean? = true, parkable: Boolean = true) =
        StopCandidate(id, name, kind, 37.5, 127.03, f, parkable, open, routeDistanceM = m, directionOk = dir)

    val urgentResults = listOf(
        RankedStop(
            place("u1", "역삼 공영주차장 화장실", StopKind.PUBLIC_PARKING, 1_100.0, Facilities(diaperTable = true, emergencyBell = true, cctv = true, open24h = true)),
            190.0,
            listOf(Reason("URGENT_TIME", "약 3분 (주행 2분, 주차·도보 1분)"), Reason("URGENT_OPEN_24H", "24시간 열려 있어요"), Reason("URGENT_DIRECTION", "유턴 없이 진행 방향에 있어요"), Reason("URGENT_SAFETY", "비상벨과 CCTV가 있어요"), Reason("URGENT_DIAPER", "기저귀 교환대가 있어요")),
        ),
        RankedStop(
            place("u2", "선릉 셀프주유소", StopKind.GAS_STATION, 1_900.0, Facilities(fuel = true, open24h = true, cctv = true)),
            310.0,
            listOf(Reason("URGENT_TIME", "약 5분 (주행 4분, 주차·도보 1분)"), Reason("URGENT_OPEN_24H", "24시간 열려 있어요"), Reason("URGENT_DIRECTION", "유턴 없이 진행 방향에 있어요"), Reason("URGENT_SAFETY", "CCTV가 있어요")),
        ),
        RankedStop(
            place("u3", "역삼역 공중화장실", StopKind.PUBLIC_RESTROOM, 2_600.0, Facilities(diaperTable = true, emergencyBell = true), dir = false, parkable = true),
            420.0,
            listOf(Reason("URGENT_TIME", "약 7분 (주행 4분, 주차·도보 2분, 유턴 5분)"), Reason("URGENT_OPEN", "지금 영업 중이에요"), Reason("URGENT_UTURN", "유턴이 필요해요")),
        ),
        RankedStop(
            place("u4", "강남 문화센터 화장실", StopKind.OTHER, 3_400.0, Facilities(accessible = true), open = null),
            560.0,
            listOf(Reason("URGENT_TIME", "약 9분"), Reason("URGENT_OPEN_UNKNOWN", "영업 여부를 확인하지 못했어요"), Reason("URGENT_DIRECTION", "유턴 없이 진행 방향에 있어요")),
        ),
    )

    /** 도심 급해: 밤 11시, 영아 동승. 급해 결과 (주행 중) */
    val urgentDriving = CoachUiState(
        serviceRunning = true,
        restroom = Gauge(8, Level.NOW, listOf(Reason("RESTROOM_VOICED", "아기가 화장실이 필요하다고 했어요")), drivenBy = "baby"),
        fatigue = Gauge(88, Level.OK, listOf(Reason("FATIGUE_DRIVING", "32분째 쉬지 않고 운전 중이에요"))),
        urgent = urgentResults,
        prompt = Prompt("urgent-1", PromptKind.URGENT_RESULT, "가장 빠른 곳은 역삼 공영주차장 화장실, 약 3분이에요. 안내할까요?", urgentResults[0].stop, now + 90_000),
        parked = false,
        drivingRestricted = true,
        speedKph = 34f,
        occupants = listOf(driver, baby),
        voice = VoiceState.LISTENING,
        demo = "urgent",
    )

    /** 정차 중 + 계획 (안성휴게소에서 쉬는 중) */
    val parkedPlan = holidayPrompt.copy(
        restroom = Gauge(95, Level.OK, listOf(Reason("RESTROOM_ELAPSED", "7살 아이 기준, 마지막 화장실 후 25분 지났어요")), drivenBy = "kid"),
        fatigue = Gauge(120, Level.OK, emptyList()),
        prompt = null,
        next = Recommendation(cheonan, setOf(Purpose.RESTROOM), listOf(Reason("NEXT_NEAREST_RESTROOM", "가장 빨리 갈 수 있는 화장실이에요"))),
        plan = PitStopPlan(
            stops = listOf(
                PlannedStop(anseong, setOf(Purpose.RESTROOM, Purpose.MEAL, Purpose.FUEL), 20, 180.0),
                PlannedStop(oksan, setOf(Purpose.RESTROOM, Purpose.REST), 10, 68_000 / 25.0 + 1_200 + 1_400),
            ),
            totalStopMin = 30,
            notes = listOf(Reason("PLAN_MEAL", "식사 시간에 맞춰 안성휴게소에서 식사해요")),
        ),
        parked = true,
        drivingRestricted = false,
        speedKph = 0f,
        voice = VoiceState.IDLE,
        demo = null,
        occupants = listOf(driver, kid, dog),
        history = history(),
        lastStop = history().lastOrNull(),
        llm = "규칙 기반 (LLM 응답 없음)",
    )

    /** 처음 실행: 서비스가 아직 계산 전 */
    val starting = CoachUiState(serviceRunning = false, occupants = listOf(driver))

    /** 탑승자를 처음 고르는 화면 (운전자만) */
    val onlyDriver = parkedPlan.copy(occupants = listOf(driver), restroom = Gauge(140, Level.OK, emptyList(), drivenBy = "driver"))

    /** 오늘·어제 정차 기록 (오래된 것부터) */
    fun history(): List<StopLog> {
        val zone = ZoneId.systemDefault()
        fun at(daysAgo: Long, h: Int, m: Int) = LocalDate.now(zone).minusDays(daysAgo).atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
        fun log(d: Long, h: Int, m: Int, min: Int, restroom: Boolean, name: String?) = StopLog(at(d, h, m), at(d, h, m) + min * 60_000L, 36.9, 127.1, restroom, name)
        return listOf(
            log(1, 9, 40, 12, true, "죽전휴게소"),
            log(1, 11, 35, 25, true, "옥천휴게소"),
            log(1, 13, 55, 15, true, "추풍령휴게소"),
            log(1, 16, 5, 10, false, "칠곡휴게소"),
            log(1, 18, 20, 4, false, null),
            log(0, 8, 30, 6, true, "서울만남의광장"),
            log(0, 10, 58, 9, true, "기흥휴게소"),
            log(0, 12, 45, 22, true, "안성휴게소"),
        )
    }
}
