package dev.shimpyo.app.ui

import androidx.compose.runtime.Immutable
import dev.shimpyo.app.ui.components.StripMark
import dev.shimpyo.app.ui.components.StripSpan
import dev.shimpyo.app.ui.components.StripSpanKind
import dev.shimpyo.app.ui.components.StripStop
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.PlannedStop
import dev.shimpyo.engine.StopCandidate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * 경로 앞 정보를 하나의 거리 축으로 정리한다. 계획 화면의 타임라인·경로 띠와 가로 주행 화면의 띠가 같이 쓴다.
 * 거리는 모두 '차에서 경로를 따라 잰 거리(m)'다 (StopCandidate.routeDistanceM, CongestionSegment.startDistanceM).
 */

/** 화장실 공백: 두 화장실 기회 사이(또는 마지막 기회와 목적지 사이)가 [GAP_ALERT_MIN]분 이상인 구간 */
data class RestroomGap(val startM: Double, val endM: Double, val minutes: Int, val congested: Boolean)

/** 공백으로 표시하는 최소 시간 (정체 알림 기준 30분과 같게 둔다) */
const val GAP_ALERT_MIN = 30

/** 경로 평균 속도 (m/s). 남은 거리·시간으로 구하고, 모르면 시속 80km */
fun routeSpeedMps(state: CoachUiState): Double {
    val d = state.remainDistanceM
    val t = state.remainTimeS
    return if (d != null && t != null && t > 60.0 && d > 0.0) (d / t).coerceIn(5.0, 33.0) else 22.0
}

/** 경로 앞 화장실 기회: 경로 거리를 아는 것만, 가까운 순 */
fun opportunities(state: CoachUiState): List<StopCandidate> =
    state.ahead.filter { it.routeDistanceM != null && it.facilities.restroom }.sortedBy { it.routeDistanceM }

/** a에서 b까지 걸리는 시간(초): 평균 속도 + 겹치는 정체의 지연 */
fun travelSeconds(aM: Double, bM: Double, congestion: List<CongestionSegment>, speedMps: Double): Double {
    var s = (bM - aM).coerceAtLeast(0.0) / speedMps
    for (c in congestion) {
        val overlap = min(bM, c.startDistanceM + c.lengthM) - max(aM, c.startDistanceM)
        if (overlap <= 0.0 || c.lengthM <= 0.0) continue
        val frac = overlap / c.lengthM
        s += (c.durationS * frac - overlap / speedMps).coerceAtLeast(0.0)
    }
    return s
}

/**
 * 화장실 공백 목록. 알고 있는 기회 사이만 본다: 후보는 앞쪽 일부(반경 60km, TBT 10곳)만 오므로
 * 마지막 기회 뒤를 공백이라고 하면 틀린 말이 된다.
 */
fun restroomGaps(state: CoachUiState): List<RestroomGap> {
    val speed = routeSpeedMps(state)
    val points = opportunities(state).map { it.routeDistanceM!! }
    return points.zipWithNext().mapNotNull { (a, b) ->
        val min = (travelSeconds(a, b, state.congestion, speed) / 60.0).roundToInt()
        val congested = state.congestion.any { it.startDistanceM < b && it.startDistanceM + it.lengthM > a }
        if (min >= GAP_ALERT_MIN) RestroomGap(a, b, min, congested) else null
    }
}

/** 정체 구간 이름: "안성–천안", 없으면 도로 이름 */
fun congestionPlace(c: CongestionSegment): String? {
    val a = c.startName.takeIf { it.isNotBlank() && !it.startsWith("시연") }
    val b = c.endName.takeIf { it.isNotBlank() && !it.startsWith("시연") }
    return when {
        a != null && b != null -> "$a–$b"
        c.roadName.isNotBlank() -> c.roadName
        else -> null
    }
}

/** 경로 띠 데이터 */
@Immutable
data class StripData(val axisM: Double, val stops: List<StripStop>, val spans: List<StripSpan>, val destinationM: Double?) {
    val isEmpty: Boolean get() = stops.isEmpty() && spans.isEmpty() && destinationM == null
}

/**
 * @param maxAxisM 띠가 보여 주는 최대 거리 (가로 주행 화면은 120km)
 * @param nearFocus 주행 화면용: 추천 정차, 정체 끝, 세 번째 기회까지만 보이도록 축을 줄인다(30km 이상).
 *   가까운 판단 지점이 띠 왼쪽 끝에 몰리지 않게 하려는 것이고, 축은 그대로 거리 비례다.
 */
fun stripData(state: CoachUiState, maxAxisM: Double? = null, nearFocus: Boolean = false): StripData {
    val plan = state.plan?.stops.orEmpty()
    val planIndex = plan.withIndex().associate { (i, p) -> p.stop.id to i + 1 }
    val recommendedId = (state.prompt?.stop ?: state.next?.stop)?.id
    val opps = opportunities(state)
    val stops = ArrayList<StripStop>()
    opps.forEach { s ->
        val n = planIndex[s.id]
        stops += StripStop(
            distanceM = s.routeDistanceM!!,
            mark = when {
                n != null -> StripMark.PLANNED
                s.id == recommendedId -> StripMark.RECOMMENDED
                else -> StripMark.OPPORTUNITY
            },
            label = shortStopName(s.name),
            number = n,
        )
    }
    plan.forEachIndexed { i, p ->
        if (opps.none { it.id == p.stop.id }) {
            plannedDistance(p, state)?.let { d -> stops += StripStop(d, StripMark.PLANNED, shortStopName(p.stop.name), i + 1) }
        }
    }
    val spans = ArrayList<StripSpan>()
    restroomGaps(state).forEach { g ->
        if (!g.congested) spans += StripSpan(g.startM, g.endM, StripSpanKind.GAP, "공백 ${Labels.duration(g.minutes)}")
    }
    state.congestion.forEach { c ->
        val start = c.startDistanceM.coerceAtLeast(0.0)
        spans += StripSpan(start, c.startDistanceM + c.lengthM, StripSpanKind.CONGESTION, "정체 ${Labels.duration((c.durationS / 60.0).roundToInt())}")
    }
    val farthest = max(
        stops.maxOfOrNull { it.distanceM } ?: 0.0,
        spans.maxOfOrNull { it.endM } ?: 0.0,
    ).coerceAtLeast(10_000.0)
    val dest = state.remainDistanceM
    var axis = if (dest != null && dest <= farthest * 1.3) dest * 1.02 else farthest * 1.1
    if (nearFocus) {
        val recommended = stops.firstOrNull { it.mark != StripMark.OPPORTUNITY }?.distanceM
        val key = listOfNotNull(
            recommended,
            state.congestion.maxOfOrNull { it.startDistanceM + it.lengthM },
            opps.getOrNull(2)?.routeDistanceM,
        ).maxOrNull()
        if (key != null) axis = min(axis, (key * 1.15).coerceAtLeast(NEAR_AXIS_MIN_M))
    }
    if (maxAxisM != null) axis = min(axis, maxAxisM)
    return StripData(axis, stops, spans, dest)
}

/** 계획 정차의 경로 거리. 후보에 경로 거리가 없으면 도착 시간 × 평균 속도로 어림한다 */
fun plannedDistance(p: PlannedStop, state: CoachUiState): Double? =
    p.stop.routeDistanceM ?: (p.arriveInS * routeSpeedMps(state)).takeIf { p.arriveInS > 0 }

private const val NEAR_AXIS_MIN_M = 30_000.0

/** "안성휴게소(부산방향)" → "안성휴게소" (띠 이름은 짧게) */
fun shortStopName(name: String): String = name.substringBefore('(').trim().ifEmpty { name }
