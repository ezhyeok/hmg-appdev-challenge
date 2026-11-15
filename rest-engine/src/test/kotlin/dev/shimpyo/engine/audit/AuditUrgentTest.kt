package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CoachOutput
import dev.shimpyo.engine.CoachTexts
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.LocalClock
import dev.shimpyo.engine.MIN
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.SEC
import dev.shimpyo.engine.SEOUL
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.TripProfile
import dev.shimpyo.engine.UrgentRanker
import dev.shimpyo.engine.VoiceIntent
import dev.shimpyo.engine.adult
import dev.shimpyo.engine.child
import dev.shimpyo.engine.kst
import dev.shimpyo.engine.stop
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UrgentRanker: 닫힘·영업 모름, 야간, 영아, 유턴, 지나친 곳 (지금 맞게 동작하는 경계).
 * 그리고 급해 모드의 시간 모델 하나: 순위, 목록의 총 소요, 음성의 '약 N분', 카드의 도착 시간이 같은 주행 시간에서 나온다 (닫힘 검증 E1)
 */
class AuditUrgentTest {
    private val cfg = CoachConfig()
    private val ranker = UrgentRanker(cfg, CoachTexts())
    private val local = LocalClock(SEOUL)
    private val night = kst(2026, 11, 11, 23, 0)
    private val noon = kst(2026, 11, 11, 12, 0)

    @Test
    fun closedIsExcludedEvenIfFlaggedOpen24h() {
        val r = ranker.rank(listOf(stop("closed24", 500.0, kind = StopKind.PUBLIC_RESTROOM, openNow = false, facilities = Facilities(open24h = true))), night, local, false, 30f, 5)
        assertTrue(r.isEmpty())
    }

    @Test
    fun passedOrDistanceLessCandidatesAreExcluded() {
        val r = ranker.rank(listOf(stop("behind", -300.0), stop("noDistance", null, etaS = 60.0)), noon, local, false, 30f, 5)
        assertTrue(r.isEmpty(), r.map { it.stop.id }.toString())
    }

    @Test
    fun atNightAShelterBeatsAnUnknownHoursRestroomAtTheSameDistance() {
        val r = ranker.rank(
            listOf(
                stop("wc", 2_000.0, kind = StopKind.PUBLIC_RESTROOM, openNow = null),
                stop("shelter", 2_000.0, kind = StopKind.DROWSY_SHELTER, openNow = null),
            ),
            night, local, false, 30f, 5,
        )
        assertEquals("shelter", r.first().stop.id)
    }

    @Test
    fun infantAtNightStacksDiaperAndSafetyBonuses() {
        val r = ranker.rank(
            listOf(
                stop("plain", 1_000.0, kind = StopKind.PUBLIC_RESTROOM, facilities = Facilities(open24h = true)),
                stop("care", 2_500.0, kind = StopKind.PUBLIC_RESTROOM, facilities = Facilities(open24h = true, diaperTable = true, emergencyBell = true)),
            ),
            night, local, true, 30f, 5,
        )
        // 1.5km 더 멀어도(도시 25km/h로 약 3.6분) 교환대 3 + 야간 안전 2 = 5분 가점이 이긴다
        assertEquals("care", r.first().stop.id)
        assertTrue(r.first().why.any { it.code == "URGENT_DIAPER" } && r.first().why.any { it.code == "URGENT_SAFETY" })
    }

    // ---------------- 급해 모드의 시간은 하나다 (닫힘 검증 E1, 감사 U6) ----------------

    private val t0 = kst(2026, 11, 13, 8, 0)

    private fun engine(vararg occ: Occupant, sinceMin: Int = 60) = CoachEngine(
        profile = TripProfile(occ.toList(), tripStart = t0 - sinceMin * MIN, lastRestroomAt = occ.associate { it.id to t0 - sinceMin * MIN }),
        random = Random(7),
    )

    /** 시속 100km로 1초마다 update. 두 번째 update에 "화장실 급해" */
    private fun CoachEngine.sayUrgent(route: RouteState?, cands: List<StopCandidate>): CoachOutput {
        update(CoachInput(now = t0, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands))
        return update(CoachInput(now = t0 + SEC, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands, intent = VoiceIntent.UrgentRestroom))
    }

    /** 1km 앞부터 18km·60분 정체. 목적지 200km */
    private val jammed = RouteState(
        guiding = true, congestionBasisKnown = true, remainDistanceM = 200 * KM, remainTimeS = 182 * KM / V100 + 3_600.0,
        congestion = listOf(CongestionSegment(startDistanceM = 1 * KM, lengthM = 18 * KM, durationS = 3_600.0)), nextManeuverDistanceM = 100 * KM,
    )
    private val inTheJam = listOf(stop("shelter", 6 * KM, kind = StopKind.DROWSY_SHELTER, name = "가졸음쉼터"), stop("rest", 10 * KM, name = "나휴게소"))

    @Test
    fun theSpokenTimeTheListAndTheCardComeFromOneDrivingTime() {
        // 회귀: 급해 순위는 etaS가 없으면 거리 ÷ 80km/h만 쓰고 엔진이 아는 정체를 보지 않았다. 길안내 중 후보는 설계상 etaS가 없다.
        // 그래서 명절 정체 앞에서 "화장실 급해"라고 하면 음성은 "가졸음쉼터까지 약 6분"인데 같은 질문 카드의 도착 시간은 17분이었다
        val out = engine(adult("me"), child("kid", 7)).sayUrgent(jammed, inTheJam)
        val top = assertNotNull(out.urgent?.firstOrNull())
        val prompt = assertNotNull(out.prompt)
        assertEquals(PromptKind.URGENT_RESULT, prompt.kind)
        assertEquals("shelter", prompt.stop?.id)
        // 정체 밖 1km(36초) + 정체 속 5km(60분 × 5/18): 주행 약 17.3분, 졸음쉼터 주차·도보 1분
        val driveS = 1 * KM / V100 + 3_600.0 * 5 / 18
        approx(driveS, assertNotNull(prompt.stop?.etaS, "질문 카드의 도착 시간"), 1.0)
        approx(driveS + 60.0, top.totalTimeS, 1.0, "총 소요 = 카드의 주행 시간 + 주차·도보")
        assertEquals("가졸음쉼터까지 약 18분이에요. 바로 안내할까요?", prompt.speech)
        assertTrue(top.why.first { it.code == "URGENT_TIME" }.text.startsWith("약 18분 (주행 17분, 주차·도보 1분"), top.why.toString())
        // 추천 카드도 같은 곳, 같은 시간, 순위의 이유
        assertEquals("shelter", out.next?.stop?.id)
        assertEquals(prompt.stop?.etaS, out.next?.stop?.etaS)
        assertEquals(top.why, out.next?.reasons)
        // 2순위도 정체 속이다: 36초 + 60분 × 9/18 + 휴게소 2분
        approx(1 * KM / V100 + 1_800.0 + 120.0, out.urgent!![1].totalTimeS, 1.0)
    }

    @Test
    fun theUrgentListFromTheScreenPathSeesTheJamToo() {
        // 회귀: 앱의 급해 화면은 urgent()만 부른다. 경로(정체)를 넘겨도 쓰지 않아 5.5분·9.5분이 나왔다 (정체로는 18분·33분)
        val e = engine(adult("me"), child("kid", 7))
        val input = CoachInput(now = t0, gear = Gear.DRIVE, speedKph = 100f, route = jammed, candidates = inTheJam)
        val list = e.urgent(input)
        assertEquals(listOf("shelter", "rest"), list.map { it.stop.id })
        approx(1 * KM / V100 + 1_000.0 + 60.0, list[0].totalTimeS, 1.0)
        // 화면 목록(urgent())과 음성 질문(update)이 같은 입력에 같은 시간을 낸다
        val spoken = engine(adult("me"), child("kid", 7)).sayUrgent(jammed, inTheJam).urgent!!
        approx(list[0].totalTimeS, spoken[0].totalTimeS, 1.5, "1초 차이")
        // 목록의 후보는 받은 그대로다: 엔진이 계산한 시간을 etaS에 채워 돌려주지 않는다 (앱이 다시 후보로 넣으면 내비 값으로 믿게 된다)
        assertTrue(list.all { it.stop.etaS == null })
        assertEquals(list, e.urgent(input), "상태를 바꾸지 않는다")
    }

    @Test
    fun aRouteStopInAJamNoLongerBeatsAStopTheNaviMeasured() {
        // 경로 위 3km 앞 휴게소는 정체 속이라 9분(36초 + 60분 × 2/18 + 2분), 내비가 5분이라고 잰 4km 앞 화장실은 7분.
        // 예전에는 휴게소를 3km ÷ 80km/h = 2.3분으로 봐서 1순위였다
        val cands = listOf(stop("rest", 3 * KM, name = "가휴게소"), stop("wc", 4 * KM, kind = StopKind.PUBLIC_RESTROOM, name = "나화장실", etaS = 300.0))
        val out = engine(adult("me")).sayUrgent(jammed, cands)
        assertEquals(listOf("wc", "rest"), out.urgent?.map { it.stop.id })
        assertEquals("나화장실까지 약 7분이에요. 바로 안내할까요?", out.prompt?.speech)
        assertEquals(300.0, out.prompt?.stop?.etaS, "내비 ETA는 그대로")
    }

    @Test
    fun anUnmeasuredFarStopIsNotAssumedFasterThanTheArrivalModelSays() {
        // 회귀(감사 U6): 길안내가 없을 때 앞 몇 곳만 내비 ETA가 있고 나머지는 직선거리뿐이다. 8km 앞 휴게소는 내비 25분(정체),
        // 20km 앞 졸음쉼터는 80km/h로 21.5분 → 더 먼 곳이 1순위가 됐다. 엔진의 도착 시간으로는(앞 후보의 지연은 뒤에도 있다) 36분이다
        val cands = listOf(
            stop("rest8", 8 * KM, name = "가휴게소", etaS = 25 * 60.0),
            stop("shelter20", null, kind = StopKind.DROWSY_SHELTER, name = "나졸음쉼터", straightM = 20 * KM, directionOk = null),
        )
        val out = engine(adult("me")).sayUrgent(RouteState(guiding = false), cands)
        assertEquals(listOf("rest8", "shelter20"), out.urgent?.map { it.stop.id })
        assertEquals("가휴게소까지 약 27분이에요. 바로 안내할까요?", out.prompt?.speech)
        val far = out.urgent!![1]
        assertTrue(far.totalTimeS > 35 * 60.0, "재지 못한 먼 곳: ${far.totalTimeS / 60}분")
        val card = assertNotNull(out.next?.stop)
        assertEquals("rest8", card.id)
        assertEquals(25 * 60.0, card.etaS)
    }

    @Test
    fun withoutARouteOrANaviEtaTheFlatEstimateStays() {
        // 도심(길안내 없음, 시속 40km): 직선거리 × 1.3 ÷ 25km/h. 엔진의 도착 시간(지금 속도로)보다 느린 쪽을 쓴다
        val e = engine(adult("me"))
        val cands = listOf(stop("wc", null, kind = StopKind.PUBLIC_RESTROOM, name = "가화장실", straightM = 1_000.0, directionOk = null))
        e.update(CoachInput(now = t0, gear = Gear.DRIVE, speedKph = 40f, candidates = cands))
        val out = e.update(CoachInput(now = t0 + SEC, gear = Gear.DRIVE, speedKph = 40f, candidates = cands, intent = VoiceIntent.UrgentRestroom))
        val top = assertNotNull(out.urgent?.single())
        val driveS = 1_300.0 / (25 / 3.6)
        approx(driveS + 120.0 + 60.0, top.totalTimeS, 1e-6, "주행 3.1분 + 주차·도보 2분 + 방향 모름 1분")
        // 음성의 총 소요와 카드의 도착 시간이 같은 주행 시간이다 (예전에는 카드가 지금 속도 40km/h로 계산한 117초였다)
        approx(driveS, assertNotNull(out.prompt?.stop?.etaS), 1e-6)
        assertEquals(out.prompt?.stop?.etaS, out.next?.stop?.etaS)
        // 질문이 떠 있는 동안 추천은 같은 곳·같은 시간·순위의 이유를 유지한다
        val later = e.update(CoachInput(now = t0 + 2 * SEC, gear = Gear.DRIVE, speedKph = 40f, candidates = cands))
        assertEquals(out.prompt?.id, later.prompt?.id)
        assertEquals("wc", later.next?.stop?.id)
        approx(driveS, assertNotNull(later.next?.stop?.etaS), 1e-6)
        assertEquals(top.why.map { it.code }, later.next?.reasons?.map { it.code })
    }

    @Test
    fun theRankerUsesTheRouteArrivalOnlyForStopsOnTheGuidedRoute() {
        // 단위 수준: 길안내 경로 위 후보(경로 거리) → 엔진 도착 시간. 경로 없이 부르면 예전 그대로 거리 ÷ 평균 속도
        val ctx = routeCtx(jam(1.0, 18.0, 60.0), destM = 200 * KM)
        val c = stop("shelter", 6 * KM, kind = StopKind.DROWSY_SHELTER)
        approx(1 * KM / V100 + 1_000.0 + 60.0, ranker.rank(listOf(c), noon, local, false, 100f, 5, ctx).single().totalTimeS, 1.0)
        approx((6.0 / 80 * 60 + 1.0) * 60, ranker.rank(listOf(c), noon, local, false, 100f, 5).single().totalTimeS, 1e-6)
        // 유턴이 필요한 경로 후보도 같은 도착 시간 + 유턴 5분, 지나친 곳은 빠진다
        val back = stop("back", 6 * KM, kind = StopKind.DROWSY_SHELTER, directionOk = false)
        approx(1 * KM / V100 + 1_000.0 + 60.0 + 300.0, ranker.rank(listOf(back), noon, local, false, 100f, 5, ctx).single().totalTimeS, 1.0)
        assertTrue(ranker.rank(listOf(stop("behind", -300.0)), noon, local, false, 100f, 5, ctx).isEmpty())
    }

    @Test
    fun uTurnCostsFiveMinutesAndUnknownDirectionOne() {
        val r = ranker.rank(
            listOf(stop("ahead", 1_000.0, directionOk = true), stop("unknown", 1_000.0, directionOk = null), stop("uturn", 1_000.0, directionOk = false)),
            noon, local, false, 30f, 5,
        )
        assertEquals(listOf("ahead", "unknown", "uturn"), r.map { it.stop.id })
        val t = r.associate { it.stop.id to it.totalTimeS / 60.0 }
        approx(t.getValue("ahead") + 1.0, t.getValue("unknown"), 1e-6)
        approx(t.getValue("ahead") + 5.0, t.getValue("uturn"), 1e-6)
    }
}
