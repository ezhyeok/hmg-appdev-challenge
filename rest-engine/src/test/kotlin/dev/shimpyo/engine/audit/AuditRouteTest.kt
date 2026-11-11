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
                RouteState(guiding = true, remainDistanceM = 50 * KM, remainTimeS = 40 * KM / V100 + 1_200.0, congestion = listOf(jam(40.0, 20.0, 40.0))),
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
                RouteState(guiding = true, remainDistanceM = 300 * KM, remainTimeS = 290 * KM / V100 + 1_200.0, congestion = listOf(dup, dup)),
                100f,
            )
        }
        approx(20 * KM / V100 + 1_200.0, ctx.arrivalAtDistance(30 * KM), 30.0, "정체 한 번(20분) + 20km 자유")
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
