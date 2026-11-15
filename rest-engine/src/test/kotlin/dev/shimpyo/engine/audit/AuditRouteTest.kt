package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.RouteContext
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.stop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RouteContext: 정체 목록 → 도착 시간. 정체가 음수·겹침·인접·0에서 시작·경로보다 긺, 내비 ETA 섞임 */
class AuditRouteTest {
    private val cfg = CoachConfig()

    @Test
    fun jamRunningPastTheDestinationDoesNotSlowTheWholeRoute() {
        // 회귀(감사 R11): 목적지 너머까지 이어진 정체를 다 세서 정체 밖 속도가 450km/h → rd/rt(68km/h)로 떨어져 모든 도착이 약 47% 늦었다
        // 목적지 50km, 정체 40~60km(40분). 내비 남은 시간 = 40km 자유 + 정체 절반 20분 = 2,640초
        val ctx = RouteContext(cfg).apply {
            prepare(
                RouteState(guiding = true, congestionBasisKnown = true, remainDistanceM = 50 * KM, remainTimeS = 40 * KM / V100 + 1_200.0, congestion = listOf(jam(40.0, 20.0, 40.0))),
                100f,
            )
        }
        approx(V100, ctx.vFreeMps, 1.0, "정체 밖 자유 속도는 100km/h")
        approx(30 * KM / V100, ctx.arrivalAtDistance(30 * KM), 30.0, "정체 앞 30km 후보는 18분")
    }

    @Test
    fun duplicateJamItemIsCountedOnce() {
        // 회귀(감사 R12): 같은 항목이 두 번(또는 겹쳐) 오면 더해서 +20분, 정체 밖 길이도 줄었다
        val dup = CongestionSegment(startDistanceM = 10 * KM, lengthM = 10 * KM, durationS = 1_200.0)
        val ctx = RouteContext(cfg).apply {
            prepare(
                RouteState(guiding = true, congestionBasisKnown = true, remainDistanceM = 300 * KM, remainTimeS = 290 * KM / V100 + 1_200.0, congestion = listOf(dup, dup)),
                100f,
            )
        }
        approx(20 * KM / V100 + 1_200.0, ctx.arrivalAtDistance(30 * KM), 30.0, "정체 한 번(20분) + 20km 자유")
    }

    // ---------------- 겹친 항목: 같은 항목만 한 번, 다른 항목은 이어 놓는다 (R12 수정의 남은 틈, 닫힘 검증 2026-11-12) ----------------

    /** 2026-11-12 에뮬레이터, 판교 출발 길안내의 정체 목록 중 101km 안 29항목 (offset m / 길이 m / 평균 속도 km/h). coach CongestionArrivalTest와 같은 기록 */
    private val measured = "400/100/38.3 600/40/35.5 1600/60/13.7 1600/30/61.3 2000/40/51.8 2000/100/54.1 2400/200/35.8 2600/70/10.1 " +
        "47000/100/9.0 47000/100/9.1 97000/40/81.9 98000/100/12.2 98000/100/51.9 98000/40/25.5 98000/80/52.0 98000/100/27.1 " +
        "98000/70/25.9 98000/100/15.8 98000/100/39.0 99000/100/26.7 99000/10/70.9 99000/100/20.3 99000/100/27.5 99000/100/16.4 " +
        "99000/100/60.4 100000/50/30.9 100000/200/38.5 100000/80/19.4 100000/70/67.5"

    private fun measuredSegments() = measured.split(' ').map { it.split('/').map(String::toDouble) }
        .map { (offset, length, kph) -> CongestionSegment(startDistanceM = offset, lengthM = length, durationS = length / (kph / 3.6)) }

    private fun RouteContext.jamTotals(): Pair<Double, Double> {
        var len = 0.0
        var time = 0.0
        for (i in 0 until jamN) {
            assertTrue(i == 0 || jamStart[i] >= jamEnd[i - 1], "구간은 시작순이고 겹치지 않는다")
            len += jamEnd[i] - jamStart[i]
            time += jamDur[i]
        }
        return len to time
    }

    @Test
    fun distinctJamItemsWithRoundedOffsetsAreNotCollapsedIntoOne() {
        // 회귀: 겹친 항목을 합집합으로 한 번만 세는 것은 '같은 항목이 두 번 온' 경우에만 맞다. 실측 목록은 offset이 유효숫자 2~3자리로 온다
        // (가까우면 100m, 10km 밖은 1km 단위: 98000이 8개, 99000이 6개, 100000이 4개). 길이·평균 속도가 서로 다른 별개 구간이고
        // 내비 요약도 길이를 다 더한 값이다. 예전에는 같은 offset을 겹친 것으로 보고 가장 느린 것 하나만 남겼다:
        // 101km 안 29항목 2,480m·385초 → 1,110m·200초, 97~101km는 19항목 1,640m·220초 → 440m·79초.
        // 먼 정체일수록 줄어들어, 여러 항목으로 오는 긴 정체는 가까이 가기 전에는 20·30분 기준에 못 미칠 수 있었다
        val segs = measuredSegments()
        val ctx = RouteContext(cfg).apply { prepare(RouteState(guiding = true, congestionBasisKnown = true, remainDistanceM = 383 * KM, congestion = segs), 90f) }
        val (jamLen, jamTime) = ctx.jamTotals()
        approx(segs.sumOf { it.lengthM }, jamLen, 1e-6, "길이 2,480m를 그대로")
        approx(segs.sumOf { it.durationS }, jamTime, 1e-6, "통과 시간 385초를 그대로")
        approx(2_480.0, jamLen, 0.5)
        approx(385.0, jamTime, 1.0)
        // 98km에 온 8개(690m)는 98.00~98.69km에 이어 놓인다: 그 뒤 지점의 도착 시간에 여덟 항목이 모두 들어 있다
        val v = 90 / 3.6
        val upTo985 = segs.filter { it.startDistanceM <= 98_000.0 }
        approx((98_800.0 - upTo985.sumOf { it.lengthM }) / v + upTo985.sumOf { it.durationS }, ctx.arrivalAtDistance(98_800.0), 1e-6)
    }

    @Test
    fun overlappingDistinctItemsAreLaidEndToEnd() {
        // 10~20km 20분과 15~25km 30분: 겹친 5km를 한 번만 세지 않는다. 뒤 항목을 앞 항목 끝(20km)에 이어 10~30km, 50분
        val a = jam(10.0, 10.0, 20.0)
        val b = jam(15.0, 10.0, 30.0)
        for (items in listOf(listOf(a, b), listOf(b, a))) { // 받은 순서가 달라도 같다
            val ctx = RouteContext(cfg).apply {
                prepare(RouteState(guiding = true, congestionBasisKnown = true, remainDistanceM = 300 * KM, remainTimeS = 280 * KM / V100 + 3_000.0, congestion = items), 100f)
            }
            val (len, time) = ctx.jamTotals()
            approx(20 * KM, len, 1e-6)
            approx(3_000.0, time, 1e-6)
            approx(V100, ctx.vFreeMps, 1e-6, "정체 밖 자유 속도")
            approx(10 * KM / V100 + 1_200.0, ctx.arrivalAtDistance(20 * KM), 1e-6, "앞 항목 끝")
            approx(10 * KM / V100 + 1_200.0 + 900.0, ctx.arrivalAtDistance(25 * KM), 1e-6, "뒤 항목의 절반")
            approx(10 * KM / V100 + 3_000.0, ctx.arrivalAtDistance(30 * KM), 1e-6)
            approx(20 * KM / V100 + 3_000.0, ctx.arrivalAtDistance(40 * KM), 1e-6)
        }
    }

    @Test
    fun onlyAnIdenticalItemIsADuplicate() {
        // 같은 시작·길이여도 통과 시간이나 이름이 다르면 다른 구간이다 (실측: 98000/100이 다섯 개, 평균 속도만 다르다)
        val base = CongestionSegment(startDistanceM = 10 * KM, lengthM = 5 * KM, durationS = 600.0, roadName = "경부고속도로", startName = "가IC", endName = "나IC")
        fun totals(vararg items: CongestionSegment) =
            RouteContext(cfg).apply { prepare(RouteState(guiding = true, congestionBasisKnown = true, remainDistanceM = 300 * KM, congestion = items.toList()), 100f) }.jamTotals()
        assertEquals(5 * KM to 600.0, totals(base, base.copy()), "모든 값이 같으면 한 번")
        assertEquals(5 * KM to 600.0, totals(base, base.copy(type = "HEAVY")), "종류 표기만 다른 것도 같은 항목")
        assertEquals(10 * KM to 1_500.0, totals(base, base.copy(durationS = 900.0)), "통과 시간이 다르다")
        assertEquals(10 * KM to 1_200.0, totals(base, base.copy(endName = "다IC")), "끝 이름이 다르다")
        assertEquals(10 * KM to 1_200.0, totals(base, base.copy(roadName = "")), "도로명이 다르다")
        assertEquals(10 * KM to 1_200.0, totals(base, base.copy(startDistanceM = 10 * KM + 100)), "시작 거리가 다르다")
    }

    @Test
    fun itemsPushedPastTheDestinationOrAlreadyPassedAreClipped() {
        // 목적지 20km. 15~19km 8분, 같은 시작의 다른 항목 4km 8분은 19~23km로 밀려 목적지 안쪽 1km(2분)만 남는다
        val items = listOf(jam(15.0, 4.0, 8.0), CongestionSegment(startDistanceM = 15 * KM, lengthM = 4 * KM, durationS = 480.0, roadName = "다른 구간"))
        val ctx = RouteContext(cfg).apply { prepare(RouteState(guiding = true, congestionBasisKnown = true, remainDistanceM = 20 * KM, congestion = items), 100f) }
        val (len, time) = ctx.jamTotals()
        approx(5 * KM, len, 1e-6)
        approx(480.0 + 120.0, time, 1e-6)
        // 이미 지나온 항목은 뒤 항목을 밀지 않고, 들어와 있는 항목은 남은 부분만 센다
        val passed = listOf(
            CongestionSegment(startDistanceM = -3 * KM, lengthM = 2 * KM, durationS = 600.0),
            CongestionSegment(startDistanceM = -1 * KM, lengthM = 2 * KM, durationS = 600.0),
            CongestionSegment(startDistanceM = 0.5 * KM, lengthM = 1 * KM, durationS = 300.0, roadName = "다음 구간"),
        )
        val inside = RouteContext(cfg).apply { prepare(RouteState(guiding = true, congestionBasisKnown = true, remainDistanceM = 100 * KM, congestion = passed), 100f) }
        assertEquals(1, inside.jamN, "같은 속도로 이어진 구간은 하나로")
        approx(0.0, inside.jamStart[0], 1e-9)
        approx(2 * KM, inside.jamEnd[0], 1e-6, "들어와 있는 항목의 남은 1km + 그 끝에 이어 놓은 1km")
        approx(600.0, inside.jamDur[0], 1e-6)
    }

    @Test
    fun laterStopNeverArrivesBeforeAnEarlierStop() {
        // 회귀(감사 R13): 앱은 상위 몇 곳만 내비 ETA를 받는다. 내비 ETA가 있는 앞 후보보다 엔진 계산의 뒤 후보가 먼저 닿았다
        val ctx = routeCtx()
        val list = opps(ctx, stop("a", 20 * KM, etaS = 40 * 60.0), stop("b", 25 * KM))
        assertEquals(listOf("a", "b"), list.map { it.stop.id })
        assertTrue(list[1].arriveS >= list[0].arriveS, "b(25km)는 a(20km, 40분)보다 먼저 닿을 수 없다: ${list.map { it.arriveS }}")
    }

    // ---- 지금 동작이 맞는 경계 (고칠 때 깨지지 않게) ----

    @Test
    fun zeroOrNegativeLengthOrDurationJamsAreIgnored() {
        val ctx = routeCtx(
            CongestionSegment(startDistanceM = 5 * KM, lengthM = 0.0, durationS = 600.0),
            CongestionSegment(startDistanceM = 5 * KM, lengthM = 3 * KM, durationS = 0.0),
            CongestionSegment(startDistanceM = 5 * KM, lengthM = -3 * KM, durationS = 600.0),
            CongestionSegment(startDistanceM = 5 * KM, lengthM = 3 * KM, durationS = -600.0),
        )
        assertEquals(0, ctx.jamN)
        approx(20 * KM / V100, ctx.arrivalAtDistance(20 * KM), 1e-6)
    }

    @Test
    fun jamStartingAtTheCarAndJamAlreadyEnteredUseOnlyTheRemainingPart() {
        val atZero = routeCtx(jam(0.0, 10.0, 30.0))
        approx(1_800.0, atZero.arrivalAtDistance(10 * KM), 1e-6)
        val inside = routeCtx(CongestionSegment(startDistanceM = -5 * KM, lengthM = 10 * KM, durationS = 1_800.0))
        approx(900.0, inside.arrivalAtDistance(5 * KM), 1e-6, "남은 절반만")
    }

    @Test
    fun adjacentJamsGiveAContinuousArrivalCurve() {
        // 인접한 두 정체: 경계에서 도착 시간이 끊기지 않는다 (도착 시간 계산 자체는 맞다. 문제는 GapAnalyzer 쪽, AuditGapTest)
        val ctx = routeCtx(jam(10.0, 5.0, 15.0), jam(15.0, 5.0, 15.0))
        approx(10 * KM / V100 + 900.0, ctx.arrivalAtDistance(15 * KM), 1e-6)
        approx(10 * KM / V100 + 1_800.0, ctx.arrivalAtDistance(20 * KM), 1e-6)
    }

    @Test
    fun horizonAtOrPastTheDestinationMeansTheListIsComplete() {
        assertTrue(routeCtx(destM = 50 * KM, horizonM = 50 * KM).horizonM.isNaN())
        assertTrue(routeCtx(destM = 50 * KM, horizonM = 80 * KM).horizonM.isNaN())
        assertEquals(30 * KM, routeCtx(destM = 50 * KM, horizonM = 30 * KM).horizonM)
    }

    @Test
    fun stopsAtZeroBehindOrWithoutAnyDistanceAreDropped() {
        val ctx = routeCtx()
        val list = opps(ctx, stop("here", 0.0), stop("behind", -1 * KM), stop("none", null), stop("straight", null, straightM = 10 * KM))
        assertEquals(listOf("straight"), list.map { it.stop.id })
        approx(13 * KM, list[0].distanceM, 1e-6, "직선거리 × 1.3")
    }
}
