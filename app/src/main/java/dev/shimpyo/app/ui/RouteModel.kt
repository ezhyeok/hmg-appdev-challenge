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

/** 화장실 공백: 차와 첫 기회 사이, 또는 두 화장실 기회 사이가 [GAP_ALERT_MIN]분 이상인 구간 */
data class RestroomGap(val startM: Double, val endM: Double, val minutes: Int, val congested: Boolean)

/** 공백으로 표시하는 최소 시간 (정체 알림 기준 30분과 같게 둔다) */
const val GAP_ALERT_MIN = 30

/** 목적지와 같은 곳(목적지가 휴게소)으로 볼 거리 여유. 내비의 남은 거리와 TBT 누적 거리가 조금 다르다 */
private const val DESTINATION_SLACK_M = 500.0

/** 경로 평균 속도 (m/s). 남은 거리·시간으로 구하고, 모르면 시속 80km */
fun routeSpeedMps(state: CoachUiState): Double {
    val d = state.remainDistanceM
    val t = state.remainTimeS
    return if (d != null && t != null && t > 60.0 && d > 0.0) (d / t).coerceIn(5.0, 33.0) else 22.0
}

/**
 * 길안내 중 목적지보다 먼 곳인지. TBT가 비어 반경 후보로 대신하면 목적지 뒤 휴게소가 섞일 수 있다
 * (UI 감사: 계획 타임라인에 목적지 뒤 정차가 나왔다)
 */
fun beyondDestination(state: CoachUiState, distanceM: Double): Boolean {
    val remain = state.remainDistanceM ?: return false
    return state.guiding && distanceM > remain + DESTINATION_SLACK_M
}

/** 경로 앞 화장실 기회: 경로 거리를 아는 것만, 목적지까지, 가까운 순 */
fun opportunities(state: CoachUiState): List<StopCandidate> =
    state.ahead.filter { s ->
        val d = s.routeDistanceM
        d != null && s.facilities.restroom && !beyondDestination(state, d)
    }.sortedBy { it.routeDistanceM }

/** 정체 구간 중 아직 남은 부분. 차가 정체 안에 있으면(시작 거리 < 0) 지금 위치부터 끝까지, 시간은 길이 비율로 줄인다 */
fun remainingCongestion(c: CongestionSegment): CongestionSegment {
    if (c.startDistanceM >= 0.0) return c
    val end = c.startDistanceM + c.lengthM
    if (end <= 0.0 || c.lengthM <= 0.0) return c.copy(startDistanceM = 0.0, lengthM = 0.0, durationS = 0.0)
    return c.copy(startDistanceM = 0.0, lengthM = end, durationS = c.durationS * end / c.lengthM)
}

/** 정체 이름표: "정체 20분" (남은 부분 기준) */
fun congestionLabel(c: CongestionSegment): String = "정체 ${Labels.duration((remainingCongestion(c).durationS / 60.0).roundToInt())}"

/** 정체 상세: "청주IC–남이JC · 3km 앞부터 18km", 정체 안이면 "지금부터 12km" */
fun congestionDetail(c: CongestionSegment): String {
    val r = remainingCongestion(c)
    val where = if (c.startDistanceM <= 0.0) "지금부터 ${Labels.distance(r.lengthM)}" else "${Labels.distance(r.startDistanceM)} 앞부터 ${Labels.distance(r.lengthM)}"
    return listOfNotNull(congestionPlace(c), where).joinToString(" · ")
}

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
 * 화장실 공백 목록. 차 → 첫 기회, 기회 → 기회 사이만 본다: 후보는 앞쪽 일부(반경 60km, TBT 10곳)만 오므로
 * 마지막 기회 뒤를 공백이라고 하면 틀린 말이 된다. 차 → 첫 기회는 정체 속에서 가장 중요한 공백이다(UI 감사 #20).
 * 경로 거리를 모르는 화장실 후보가 하나라도 있으면 순서를 알 수 없어(그 사이에 있을 수 있다) 공백을 말하지 않는다.
 */
fun restroomGaps(state: CoachUiState): List<RestroomGap> {
    val unknownOrder = state.ahead.any { it.facilities.restroom && it.routeDistanceM == null }
    if (unknownOrder) return emptyList()
    val speed = routeSpeedMps(state)
    val opps = opportunities(state).map { it.routeDistanceM!! }
    if (opps.isEmpty()) return emptyList()
    val points = listOf(0.0) + opps
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

/**
 * 계획 정차의 지금 모습.
 * @property number 남은 계획 안에서의 순서 (1부터, 지나친 정차는 빼고 센다)
 * @property stop 경로 앞 후보(ahead)에 같은 곳이 있으면 그 후보(거리·시간이 매초 줄어든다), 없으면 계획 때 값
 * @property live [stop]이 지금 후보 목록의 값인지
 * @property distanceM 차에서 경로 거리. 모르면 어림값([estimated])
 * @property etaS 지금 기준 도착까지 (초)
 */
data class LivePlannedStop(
    val planned: PlannedStop,
    val number: Int,
    val stop: StopCandidate,
    val live: Boolean,
    val distanceM: Double,
    val estimated: Boolean,
    val etaS: Double,
)

/**
 * 계획을 지금 위치에 맞춘다 (UI 감사 #4: 계획 때 거리·도착 시간이 그대로 보였고, 지나친 정차가 첫 번째로 남았다).
 * - 지나침: 후보 목록에 없는데 뒤의 계획 정차는 있으면 지나친 것이다.
 * - 목적지 뒤(길안내 중 남은 거리보다 먼 곳)는 뺀다. 지금 후보에 있는 정차만 견준다(계획 때 거리는 낡았다).
 * - 거리를 전혀 모르면(경로 거리·직선거리 없음) 도착 시간 × 평균 속도로 어림하고, 도착 0초(PLAN_NOW)는 지금 위치다.
 *   예전에는 이런 정차가 계획 화면에서 조용히 빠졌다.
 * 코치가 계획을 다시 세우거나 가지치기하면 그 값이 먼저다 (이 함수는 받은 계획을 지금 후보에 맞춰 보일 뿐이다).
 */
fun livePlan(state: CoachUiState): List<LivePlannedStop> {
    val stops = state.plan?.stops.orEmpty()
    if (stops.isEmpty()) return emptyList()
    val byId = state.ahead.associateBy { it.id }
    val liveIdx = stops.indices.filter { byId.containsKey(stops[it].stop.id) }
    val lastLive = liveIdx.maxOrNull() ?: -1
    val speed = routeSpeedMps(state)
    val out = ArrayList<LivePlannedStop>(stops.size)
    stops.forEachIndexed { i, p ->
        val now = byId[p.stop.id]
        if (now == null && i < lastLive) return@forEachIndexed
        val (d, est) = plannedPosition(p, now, speed)
        // 목적지와 비교는 지금 거리로만 한다: 계획 때 거리(52km)는 그 뒤 달린 만큼 낡아 남은 거리(30km)와 견줄 수 없다
        if (now != null && beyondDestination(state, d)) return@forEachIndexed
        val eta = now?.etaS ?: if (now != null || !est) travelSeconds(0.0, d, state.congestion, speed) else p.arriveInS
        out += LivePlannedStop(p, out.size + 1, now ?: p.stop, now != null, d, est, eta)
    }
    return out
}

/** 계획 정차의 경로 거리: 지금 후보 → 계획 때 경로 거리 → 직선거리 × 도로계수(어림) → 도착 시간 × 평균 속도(어림) */
fun plannedDistance(p: PlannedStop, state: CoachUiState): Double =
    plannedPosition(p, state.ahead.firstOrNull { it.id == p.stop.id }, routeSpeedMps(state)).first

private fun plannedPosition(p: PlannedStop, now: StopCandidate?, speed: Double): Pair<Double, Boolean> {
    now?.routeDistanceM?.let { return it to false }
    now?.straightDistanceM?.let { return it * Labels.config.roadFactor to true }
    p.stop.routeDistanceM?.let { return it to (now == null) }
    p.stop.straightDistanceM?.let { return it * Labels.config.roadFactor to true }
    return (p.arriveInS.coerceAtLeast(0.0) * speed) to true
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
    val plan = livePlan(state)
    val planNumber = plan.associate { it.planned.stop.id to it.number }
    val recommendedId = (state.prompt?.stop ?: state.next?.stop)?.id
    val opps = opportunities(state)
    val stops = ArrayList<StripStop>()
    opps.forEach { s ->
        val n = planNumber[s.id]
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
    plan.forEach { p ->
        if (opps.none { it.id == p.planned.stop.id }) stops += StripStop(p.distanceM, StripMark.PLANNED, shortStopName(p.stop.name), p.number)
    }
    val spans = ArrayList<StripSpan>()
    restroomGaps(state).forEach { g ->
        if (!g.congested) spans += StripSpan(g.startM, g.endM, StripSpanKind.GAP, "공백 ${Labels.duration(g.minutes)}")
    }
    state.congestion.forEach { c ->
        val r = remainingCongestion(c)
        if (r.lengthM > 0.0) spans += StripSpan(r.startDistanceM, r.startDistanceM + r.lengthM, StripSpanKind.CONGESTION, congestionLabel(c))
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
            spans.filter { it.kind == StripSpanKind.CONGESTION }.maxOfOrNull { it.endM },
            opps.getOrNull(2)?.routeDistanceM,
        ).maxOrNull()
        if (key != null) axis = min(axis, (key * 1.15).coerceAtLeast(NEAR_AXIS_MIN_M))
    }
    if (maxAxisM != null) axis = min(axis, maxAxisM)
    // 도착 직전(남은 거리 0)에도 띠가 점 하나로 줄지 않게
    return StripData(axis.coerceAtLeast(MIN_AXIS_M), stops, spans, dest)
}

private const val NEAR_AXIS_MIN_M = 30_000.0
private const val MIN_AXIS_M = 1_000.0

/** "안성휴게소(부산방향)" → "안성휴게소" (띠 이름은 짧게) */
fun shortStopName(name: String): String = name.substringBefore('(').trim().ifEmpty { name }
