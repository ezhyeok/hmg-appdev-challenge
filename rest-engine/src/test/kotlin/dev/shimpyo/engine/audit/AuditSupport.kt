package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.Opportunity
import dev.shimpyo.engine.RouteContext
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.StopCandidate
import kotlin.math.abs
import kotlin.test.assertTrue

// 감사(audit) 테스트 공용 도구. 감사(2026-11-11)에서 찾은 결함마다 실패하던 테스트를 고친 뒤 회귀 방지로 남긴다 (주석의 '감사 R1' 등은
// 시나리오 표 번호). 지금 맞는 경계를 고정한 테스트도 함께 둔다.

internal const val KM = 1_000.0

/** 100km/h (m/s) */
internal const val V100 = 100 / 3.6

internal fun jam(startKm: Double, lengthKm: Double, minutes: Double) =
    CongestionSegment(startDistanceM = startKm * KM, lengthM = lengthKm * KM, durationS = minutes * 60.0)

/**
 * 자유 속도 100km/h 경로. 내비의 남은 시간은 각 정체를 한 번씩, 목적지 안쪽 몫만 센다 (실제 내비와 같은 방식).
 */
internal fun routeCtx(
    vararg jams: CongestionSegment,
    destM: Double = 300 * KM,
    horizonM: Double? = null,
    cfg: CoachConfig = CoachConfig(),
): RouteContext {
    var jamLen = 0.0
    var jamTime = 0.0
    for (j in jams) {
        if (j.lengthM <= 0.0 || j.durationS <= 0.0) continue // 내비가 정체로 세지 않을 값
        val s = maxOf(0.0, j.startDistanceM)
        val e = minOf(destM, j.startDistanceM + j.lengthM)
        if (e <= s) continue
        jamLen += e - s
        jamTime += j.durationS * (e - s) / j.lengthM
    }
    val rt = (destM - jamLen) / V100 + jamTime
    return RouteContext(cfg).apply {
        prepare(
            RouteState(guiding = true, remainDistanceM = destM, remainTimeS = rt, congestion = jams.toList(), candidatesUntilM = horizonM),
            100f,
        )
    }
}

internal fun opps(ctx: RouteContext, vararg c: StopCandidate): List<Opportunity> =
    ArrayList<Opportunity>().also { ctx.opportunities(c.toList(), it, RouteContext::isRestroomOpportunity) }

internal fun approx(expected: Double, actual: Double, tol: Double, msg: String = "") =
    assertTrue(abs(expected - actual) <= tol, "$msg expected $expected±$tol but was $actual")
