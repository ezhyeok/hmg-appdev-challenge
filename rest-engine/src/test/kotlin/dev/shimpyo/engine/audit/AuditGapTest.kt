package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.GapAnalyzer
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.MIN
import dev.shimpyo.engine.RouteContext
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.TripProfile
import dev.shimpyo.engine.adult
import dev.shimpyo.engine.child
import dev.shimpyo.engine.kst
import dev.shimpyo.engine.stop
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GapAnalyzer: 마지막 기회의 세 규칙 × 목적지·후보 범위·정체 모양 */
class AuditGapTest {
    private val cfg = CoachConfig()
    private val gap = GapAnalyzer(cfg)

    // ---------------- 정체가 여러 항목으로 쪼개져 올 때 ----------------

    @Test
    fun splitJamItemsCountAsOneCongestion() {
        // 회귀(감사 R1): 내비는 한 정체를 여러 항목으로 쪼개 보낸다(판교→부산 26항목). 예전에는 항목마다 따로 20/30분 기준에 대어
        // 이어진 15분 × 3(45분, 안에 화장실 없음)을 정체로 보지 못하고 '공백 57분'이라고만 했다
        val ctx = routeCtx(jam(10.0, 5.0, 15.0), jam(15.0, 5.0, 15.0), jam(20.0, 5.0, 15.0))
        val list = opps(ctx, stop("a", 5 * KM), stop("b", 40 * KM))
        val lc = assertNotNull(gap.analyze(list, ctx, deadlineMin = 30.0, sensitive = true, preferredMaxGapMin = null))
        assertEquals("a", lc.opp.stop.id)
        assertEquals(GapAnalyzer.Trigger.CONGESTION, lc.trigger)
        approx(45 * 60.0, lc.congestionS, 1.0, "이어진 정체 45분")
    }

    @Test
    fun splitJamItemsStillWarnWhenTheDeadlineIsAlreadyShort() {
        // 회귀(감사 R2): 같은 원인. 마감(2분)이 a(3분)보다 일러 게이지 규칙도 말이 없어 아무것도 묻지 않았다
        val ctx = routeCtx(jam(10.0, 5.0, 15.0), jam(15.0, 5.0, 15.0), jam(20.0, 5.0, 15.0))
        val list = opps(ctx, stop("a", 5 * KM), stop("b", 40 * KM))
        val lc = gap.analyze(list, ctx, deadlineMin = 2.0, sensitive = true, preferredMaxGapMin = null)
        assertEquals("a", lc?.opp?.stop?.id, "정체 전 마지막 기회")
    }

    @Test
    fun aSingleLongJamItemFiresTheCongestionRule() {
        // 같은 정체를 한 항목으로 받으면 맞게 동작한다 (위 두 테스트와 대조)
        val ctx = routeCtx(jam(10.0, 15.0, 45.0))
        val list = opps(ctx, stop("a", 5 * KM), stop("b", 40 * KM))
        val lc = assertNotNull(gap.analyze(list, ctx, deadlineMin = 2.0, sensitive = true, preferredMaxGapMin = null))
        assertEquals(GapAnalyzer.Trigger.CONGESTION, lc.trigger)
        assertEquals("a", lc.opp.stop.id)
    }

    // ---------------- 목적지가 후보보다 앞일 때 ----------------

    @Test
    fun destinationBeforeTheNextCandidateEndsTheGap() {
        // 회귀(감사 R4): 목적지(12분) 너머 후보(30분)를 다음 기회로 봐서, 9분 뒤 도착인데 'a 뒤 27분 공백'이라고 했다
        val ctx = routeCtx(destM = 20 * KM)
        val list = opps(ctx, stop("a", 5 * KM), stop("pastDestination", 50 * KM))
        assertNull(gap.analyze(list, ctx, deadlineMin = 8.0, sensitive = false, preferredMaxGapMin = null))
    }

    @Test
    fun destinationRightAfterTheJamSuppressesTheCongestionRule() {
        // 회귀(감사 R5): 정체 뒤 목적지(49분)가 마감(52분) 안인데 목적지 너머 첫 후보(64분)를 써서 정체·게이지 규칙이 모두 걸렸다
        val ctx = routeCtx(jam(10.0, 20.0, 40.0), destM = 35 * KM)
        val list = opps(ctx, stop("a", 5 * KM), stop("pastDestination", 60 * KM))
        assertNull(gap.analyze(list, ctx, deadlineMin = 52.0, sensitive = false, preferredMaxGapMin = null))
    }

    // ---------------- 후보 범위(horizon)를 넘는 것들 ----------------

    @Test
    fun aCandidateBeyondTheHorizonDoesNotProveAGap() {
        // 회귀(감사 R6): 목록은 20km까지만 빠짐없다. 그 너머 80km 후보를 다음 기회로 봐서 '45분 공백'이라고 했다 (플래너는 이미 뺐다)
        val ctx = routeCtx(horizonM = 20 * KM)
        val list = opps(ctx, stop("a", 5 * KM), stop("farOutside", 80 * KM))
        assertNull(gap.analyze(list, ctx, deadlineMin = 10.0, sensitive = false, preferredMaxGapMin = null))
    }

    @Test
    fun aJamStartingBeyondTheHorizonIsNotProvenToHaveNoStopBeforeIt() {
        // 회귀(감사 R7): 목록 범위(30km) 밖 60km에서 시작하는 정체 앞에도 기회가 있을 수 있다. 예전에는 20km 후보를 '정체 전 마지막'이라고 했다
        val ctx = routeCtx(jam(60.0, 40.0, 40.0), horizonM = 30 * KM)
        val list = opps(ctx, stop("a", 5 * KM), stop("b", 20 * KM))
        assertNull(gap.analyze(list, ctx, deadlineMin = 40.0, sensitive = false, preferredMaxGapMin = null))
    }

    @Test
    fun theHorizonLowerBoundStillFlagsARealGap() {
        // 범위 안에 a 하나뿐이고 범위가 60km면 a 뒤 공백은 적어도 33분이다 → 마감 10분이면 a가 마지막 기회 (지금 맞다)
        val ctx = routeCtx(horizonM = 60 * KM)
        val lc = assertNotNull(gap.analyze(opps(ctx, stop("a", 5 * KM)), ctx, deadlineMin = 10.0, sensitive = false, preferredMaxGapMin = null))
        assertEquals(GapAnalyzer.Trigger.GAUGE, lc.trigger)
    }

    // ---------------- 내비 ETA가 일부만 있을 때 ----------------

    @Test
    fun aNavEtaOnAnEarlierStopBoundsTheLaterStops() {
        // 회귀(감사 R14): 도착 시간이 거리순이 아니어서 b(25km)가 15분에 닿는다고 봤다(a 20km는 내비 40분). 아무것도 마감 30분 안에 닿지 않는다
        val ctx = routeCtx()
        val list = opps(ctx, stop("a", 20 * KM, etaS = 40 * 60.0), stop("b", 25 * KM))
        assertNull(gap.analyze(list, ctx, deadlineMin = 30.0, sensitive = false, preferredMaxGapMin = null))
    }

    // ---------------- 마감이 이미 지났지만 게이지는 NOW가 아닐 때 (엔진 수준) ----------------

    @Test
    fun aPassedDecisionDeadlineStillWarnsBeforeALongGap() {
        // 회귀(감사 O1): 판단 마감이 이미 0인데(아이 필요 확률 ≥ 0.3) 게이지 규칙은 '마감 안' 기회를 못 찾고, NOW 경로는 게이지 10분 미만이 필요해
        // 아이 기준 약 16분 동안 28분 공백 앞에서 말이 없었다. PLAN_NOW 수정의 GapAnalyzer 쌍둥이
        val start = kst(2026, 11, 11, 10, 0)
        val e = CoachEngine(
            profile = TripProfile(
                listOf(adult("driver"), child("kid", 7)), tripStart = start - 90 * MIN,
                lastRestroomAt = mapOf("driver" to start - 82 * MIN, "kid" to start - 82 * MIN),
            ),
            random = Random(7),
        )
        val out = e.update(
            CoachInput(
                now = start, gear = Gear.DRIVE, speedKph = 100f,
                route = RouteState(guiding = true, remainDistanceM = 200 * KM, remainTimeS = 200 * KM / V100, nextManeuverDistanceM = 50 * KM),
                candidates = listOf(stop("a", 3.5 * KM, name = "가휴게소"), stop("b", 50 * KM, name = "나휴게소")),
            ),
        )
        assertEquals(Level.WATCH, out.restroom.level, "아이 게이지 23분")
        assertEquals("a", out.next?.stop?.id, "다음 기회(28분 뒤)가 게이지보다 머니 a를 권해야 한다: next=${out.next}")
    }

    // ---------------- 지금 맞는 경계 ----------------

    @Test
    fun noDestinationAndNoHorizonClaimsNothingAfterTheLastStop() {
        val ctx = RouteContext(cfg).apply { prepare(null, 100f) }
        assertNull(gap.analyze(opps(ctx, stop("a", 5 * KM)), ctx, deadlineMin = 10.0, sensitive = false, preferredMaxGapMin = null))
    }

    @Test
    fun jamStartingAtTheCarHasNoStopBeforeIt() {
        // 정체 규칙은 걸리지 않는다(정체 앞에 기회가 없다). 마감(10분)이 a(66분)보다 이르고 a 뒤가 목적지까지 2시간 36분이라
        // 게이지 규칙이 a를 '아직 들를 수 있는 마지막 기회'로 고른다 (마감이 지난 경우, aPassedDecisionDeadlineStillWarnsBeforeALongGap)
        val ctx = routeCtx(jam(0.0, 30.0, 60.0))
        val lc = gap.analyze(opps(ctx, stop("a", 40 * KM)), ctx, deadlineMin = 10.0, sensitive = false, preferredMaxGapMin = null)
        assertTrue(lc == null || lc.trigger != GapAnalyzer.Trigger.CONGESTION, lc?.trigger.toString())
        assertEquals(GapAnalyzer.Trigger.GAUGE, lc?.trigger)
    }

    @Test
    fun voicedNeedWithZeroDeadlineStillPointsToTheStopBeforeTheJam() {
        val ctx = routeCtx(jam(10.0, 20.0, 40.0))
        val lc = assertNotNull(gap.analyze(opps(ctx, stop("a", 5 * KM), stop("b", 40 * KM)), ctx, deadlineMin = 0.0, sensitive = false, preferredMaxGapMin = null))
        assertEquals("a", lc.opp.stop.id)
        assertEquals(GapAnalyzer.Trigger.CONGESTION, lc.trigger)
    }

    @Test
    fun stopInsideAnyPartOfTheJamSuppressesTheCongestionRule() {
        val ctx = routeCtx(jam(10.0, 20.0, 40.0))
        val lc = gap.analyze(opps(ctx, stop("a", 5 * KM), stop("inside", 20 * KM), stop("b", 40 * KM)), ctx, deadlineMin = 100.0, sensitive = false, preferredMaxGapMin = null)
        assertTrue(lc == null || lc.trigger != GapAnalyzer.Trigger.CONGESTION, lc?.trigger.toString())
    }
}
