package dev.shimpyo.engine

import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 피트스톱 플래너. SPEC 4.5, docs/research/ai-science.md 8-4 (경로가 고정된 운전자 일정 문제와 같은 계열).
 *
 * 경로 앞 화장실 기회 o_1..o_n(거리순)과 출발점 0, 끝점 T(목적지, 없으면 마지막 후보)를 두고 동적 계획법을 푼다.
 *
 * - 상태: (마지막으로 선 곳 i, 주유했는지 f, 식사했는지 a). 상태 수 4(n + 1), 전이 O(n) → 전체 O(n²).
 * - 구간 상한: 첫 구간은 지금 상태의 판단 마감(가장 급한 사람의 필요 확률 0.3)과 피로 게이지 중 짧은 쪽,
 *   정차 뒤에는 min(모두 방금 다녀온 상태의 판단 마감, 그 시각에서 시작하는 2시간 휴식 리듬). 걷기·카시트 2시간 상한 포함.
 *   넘친 시간은 '위반 초'로 센다 (불가능해도 가장 덜 어기는 계획을 낸다).
 *   식사 뒤 구간의 배수(RestroomNeedModel, 0.8)는 차가 지금 달리는 첫 구간의 마감에만 들어 있다. 계획한 식사 정차 뒤 구간은
 *   배수 1.0이다: 여기서는 정차 뒤 시계를 출발 때 0으로 보는데 엔진은 정차 3분에 돌리므로(35분 식사면 출발 때 이미 32분),
 *   그 차이가 배수(20%)보다 크다. 배수만 얹으면 계획이 실제보다 정밀해 보일 뿐이라 둘을 함께 맞출 때까지 두지 않는다.
 * - 비용(사전식): 위반 초 → 정차 수 → 선호 감점 → 정차·우회·진출입 시간 (진출입 손실 5분이 정차마다 붙어 묶음 정차가 유리하다).
 *   선호 감점: 식사 시간대(11:30~13:30, 17:30~19:30)를 지나는데 음식 있는 곳에서 식사하지 않음 60,
 *   영아 수유·기저귀 목적인데 교환대·수유실 없음 15, 반려견인데 반려동물 공간 없음 5.
 * - 각 정차에 목적을 묶는다: 화장실(항상), 휴식(90분 이상 운전 뒤), 식사(시간대 + 음식), 주유(필요 + 주유 시설),
 *   수유(영아 간격의 절반이 지났을 때), 반려동물. 정차 시간 = 목적별 시간의 최댓값.
 * - 같은 비용이면 먼저 찾은 해(앞쪽 후보)를 유지하므로 결정적이다.
 * 정차 시각의 식사 시간대 판정은 그 상태까지의 최선 경로 누적 정차 시간을 쓴다 (근사).
 * 주유 위반: 주유하지 않은 채 '주행가능거리 − 여유'를 넘어 달린 거리를 구간마다 한 번씩만 센다 (그 구간에서 새로 넘은 거리).
 * 예전에는 넘은 뒤의 정차마다 출발점부터 넘은 거리 전체를 다시 더해, 주유할 곳이 없으면 한계 전에만 서려다 화장실 계획이 틀어졌다.
 * 메모: 위반 초 중 주유 몫과 화장실·휴식 몫을 따로 쌓아, 주유할 곳이 없을 때 '화장실 사이가 먼 구간'이라고 하지 않는다
 * (PLAN_FUEL_SHORT. 예전에는 모든 위반이 PLAN_GAP이었다, 감사 L1).
 */
internal class PitStopPlanner(private val config: CoachConfig, private val texts: CoachTexts) {

    /** 정차에 얹히는 돌봄 필요 (영아 수유·기저귀, 반려견 산책) */
    class Care(
        val purpose: Purpose,
        val elapsedNowS: Double,
        val fullS: Double,
        val facilityOk: (StopCandidate) -> Boolean,
        val mismatchPenalty: Double,
    )

    class Needs(
        /** 첫 구간 상한 (초): 지금 화장실·피로 게이지 중 짧은 쪽 */
        val firstLegMaxS: Double,
        /** 정차 뒤 화장실 상한 (초). 탑승자가 없으면 무한대 */
        val restroomFullS: Double,
        /** 휴식 직후 벽시계 시각 → 피로 NOW 문턱까지 (초) */
        val fatigueFullS: (Long) -> Double,
        /** 지금까지 쉬지 않고 운전한 시간 (초) */
        val drivenNowS: Double,
        /** 이만큼 운전한 뒤의 정차에는 휴식 목적을 붙인다 (초) */
        val restLabelS: Double,
        val care: List<Care>,
        /** 남은 주행가능거리 (m). null이면 주유를 고려하지 않는다 */
        val rangeM: Double?,
        /** 졸림 사전 곡선이 NOW에 닿을 때까지 (초). 계획 안에 들어오면 메모를 단다 */
        val sleepyCurveS: Double = Double.POSITIVE_INFINITY,
    )

    private class Label(
        val violS: Double,
        /** violS 중 주유 몫 */
        val fuelViolS: Double,
        val stops: Int,
        val penalty: Double,
        val dwellS: Double,
        val prevNode: Int,
        val prevState: Int,
        /** 이 노드를 떠날 때까지 쌓인 정차·우회 시간 (초) */
        val accDwellS: Double,
        val purposes: Set<Purpose>,
        val nodeDwellS: Double,
    )

    fun plan(opps: List<Opportunity>, route: RouteContext, needs: Needs, wallNow: Long, local: LocalClock): PitStopPlan {
        val notes = ArrayList<Reason>(3)
        val hasDest = !route.destS.isNaN()
        if (opps.isEmpty()) {
            notes += Reason("PLAN_NO_CANDIDATES", texts.planNoCandidates())
            return PitStopPlan(emptyList(), 0, notes)
        }
        val n = opps.size
        // 후보 목록을 잘랐으면(RouteState.candidatesUntilM) 아는 범위까지만 계획한다. 예전에는 그 뒤를 화장실 없는 구간으로 봐서
        // '화장실 사이가 먼 구간이 있어요'라고 틀리게 안내했다(2026-11-11 에뮬레이터, CandidateHorizonTest가 지킨다)
        val cut = !route.horizonM.isNaN()
        val horizonS = when {
            cut -> route.arrivalAtDistance(route.horizonM)
            hasDest -> route.destS
            else -> opps[n - 1].arriveS
        }
        val horizonM = when {
            cut -> route.horizonM
            hasDest && !route.destM.isNaN() -> route.destM
            else -> opps[n - 1].distanceM
        }
        val fuelLimitM = needs.rangeM?.let { it - config.fuelReserveKm * 1000.0 }
        val fuelNeeded = fuelLimitM != null && horizonM > fuelLimitM
        val mealPossible = opps.any { it.stop.facilities.food && inMeal(local, wallNow + (it.arriveS * 1000).toLong()) }

        // labels[node][f * 2 + a]
        val labels = Array(n + 1) { arrayOfNulls<Label>(4) }
        labels[0][(if (fuelNeeded) 0 else 1) * 2] = Label(0.0, 0.0, 0, 0.0, 0.0, -1, -1, 0.0, emptySet(), 0.0)
        var terminal: Label? = null

        for (i in 0..n) {
            for (st in 0 until 4) {
                val from = labels[i][st] ?: continue
                val f = st / 2
                val a = st % 2
                val arrI = if (i == 0) 0.0 else opps[i - 1].arriveS
                val distI = if (i == 0) 0.0 else opps[i - 1].distanceM
                val departWall = wallNow + ((arrI + from.accDwellS) * 1000).toLong()
                val legMax = if (i == 0) needs.firstLegMaxS else minOf(needs.restroomFullS, needs.fatigueFullS(departWall))

                for (j in i + 1..n) {
                    val o = opps[j - 1]
                    if (hasDest && o.arriveS >= route.destS) continue
                    if (cut && o.distanceM > route.horizonM) continue
                    val legS = maxOf(0.0, o.arriveS - arrI) + o.stop.detourS
                    val fuelViol = if (f == 0 && fuelLimitM != null) beyond(distI, o.distanceM, fuelLimitM) / route.vFreeMps else 0.0
                    val viol = maxOf(0.0, legS - legMax) + fuelViol
                    val arrivalWall = wallNow + ((o.arriveS + from.accDwellS) * 1000).toLong()
                    val purposes = EnumSet.of(Purpose.RESTROOM)
                    if (legS + (if (i == 0) needs.drivenNowS else 0.0) >= needs.restLabelS) purposes += Purpose.REST
                    var na = a
                    if (a == 0 && o.stop.facilities.food && inMeal(local, arrivalWall)) {
                        purposes += Purpose.MEAL
                        na = 1
                    }
                    var nf = f
                    if (f == 0 && (o.stop.facilities.fuel || o.stop.facilities.evCharger)) {
                        purposes += Purpose.FUEL
                        nf = 1
                    }
                    var penalty = 0.0
                    for (c in needs.care) {
                        val since = legS + (if (i == 0) c.elapsedNowS else 0.0)
                        if (since >= c.fullS * CARE_LABEL_FRACTION) {
                            purposes += c.purpose
                            if (!c.facilityOk(o.stop)) penalty += c.mismatchPenalty
                        }
                    }
                    val dwellS = dwellMin(purposes) * 60.0
                    val extra = dwellS + o.stop.detourS + config.stopOverheadMin * 60.0
                    val cand = Label(
                        from.violS + viol, from.fuelViolS + fuelViol, from.stops + 1, from.penalty + penalty, from.dwellS + extra,
                        i, st, from.accDwellS + extra, purposes, dwellS,
                    )
                    val idx = nf * 2 + na
                    if (better(cand, labels[j][idx])) labels[j][idx] = cand
                }

                // 끝점
                val fuelViol = if (f == 0 && fuelLimitM != null) beyond(distI, horizonM, fuelLimitM) / route.vFreeMps else 0.0
                val viol = maxOf(0.0, horizonS - arrI - legMax) + fuelViol
                var penalty = 0.0
                if (a == 0 && mealPossible &&
                    mealCrossed(local, wallNow, wallNow + ((horizonS + from.accDwellS) * 1000).toLong())
                ) penalty += MEAL_MISS_PENALTY
                val term = Label(from.violS + viol, from.fuelViolS + fuelViol, from.stops, from.penalty + penalty, from.dwellS, i, st, from.accDwellS, emptySet(), 0.0)
                if (better(term, terminal)) terminal = term
            }
        }

        val end = terminal!!
        val chain = ArrayList<Pair<Int, Label>>()
        var node = end.prevNode
        var state = end.prevState
        while (node > 0) {
            val l = labels[node][state]!!
            chain += node to l
            node = l.prevNode
            state = l.prevState
        }
        chain.reverse()
        val stops = ArrayList<PlannedStop>(chain.size)
        var accBefore = 0.0
        for ((nd, l) in chain) {
            val o = opps[nd - 1]
            stops += PlannedStop(o.stop, l.purposes, (l.nodeDwellS / 60.0).roundToInt(), o.arriveS + accBefore)
            accBefore = l.accDwellS
        }
        val totalMin = stops.sumOf { it.dwellMin }

        // 지금 이미 쉬어야 할 때가 지났으면(게이지 0) 가장 가까운 곳에 들러도 기준을 넘는다. 그것뿐이면 '먼 구간'이 아니라
        // '바로 들르기'로 안내한다 (2026-11-11 에뮬레이터: 휴게소에 서 있는데 '화장실 사이가 먼 구간이 있어요'라고 했다)
        val unavoidableS = maxOf(0.0, opps[0].arriveS + opps[0].stop.detourS - needs.firstLegMaxS)
        val first = stops.firstOrNull()
        val restViolS = end.violS - end.fuelViolS
        when {
            restViolS <= 0.5 -> Unit
            unavoidableS > 0.5 && restViolS <= unavoidableS + 0.5 && first != null && first.stop.id == opps[0].stop.id ->
                notes += Reason("PLAN_NOW", texts.planNow(first.stop.name))
            else -> notes += Reason("PLAN_GAP", texts.planGapTooLong())
        }
        if (end.fuelViolS > 0.5) notes += Reason("PLAN_FUEL_SHORT", texts.planFuelShort())
        if (stops.isEmpty() && end.violS <= 0.5) notes += Reason("PLAN_NONE_NEEDED", texts.planNoneNeeded(hasDest && !cut))
        stops.firstOrNull { Purpose.MEAL in it.purposes }?.let { notes += Reason("PLAN_MEAL", texts.planMeal(it.stop.name)) }
        stops.firstOrNull { Purpose.FUEL in it.purposes }?.let { notes += Reason("PLAN_FUEL", texts.planFuel(it.stop.name)) }
        if (needs.sleepyCurveS < horizonS + end.accDwellS) notes += Reason("PLAN_SLEEPY_TIME", texts.planSleepyTime())
        if (!hasDest) notes += Reason("PLAN_NO_DESTINATION", texts.planNoDestination())
        else if (cut) notes += Reason("PLAN_PARTIAL", texts.planPartial((route.horizonM / 1000.0).roundToInt()))
        return PitStopPlan(stops, totalMin, notes)
    }

    /** [fromM, toM] 구간 중 limitM 너머의 길이 */
    private fun beyond(fromM: Double, toM: Double, limitM: Double): Double = maxOf(0.0, toM - maxOf(fromM, limitM))

    fun dwellMin(purposes: Set<Purpose>): Int {
        var m = 0
        for (p in purposes) {
            val d = when (p) {
                Purpose.RESTROOM -> config.restroomDwellMin
                Purpose.REST -> config.restDwellMin
                Purpose.MEAL -> config.mealDwellMin
                Purpose.FUEL -> config.fuelDwellMin
                Purpose.FEEDING -> config.feedingDwellMin
                Purpose.PET -> config.petDwellMin
            }
            if (d > m) m = d
        }
        return m
    }

    private fun better(a: Label, b: Label?): Boolean {
        if (b == null) return true
        if (abs(a.violS - b.violS) > 0.5) return a.violS < b.violS
        if (a.stops != b.stops) return a.stops < b.stops
        if (abs(a.penalty - b.penalty) > 1e-6) return a.penalty < b.penalty
        if (abs(a.dwellS - b.dwellS) > 0.5) return a.dwellS < b.dwellS
        return false
    }

    private fun inMeal(local: LocalClock, wall: Long): Boolean {
        val m = local.minuteOfDay(wall)
        return config.mealWindows.any { m in it }
    }

    /** [startWall, endWall]가 식사 시간대와 30분 이상 겹치는지 (여러 날에 걸쳐도) */
    private fun mealCrossed(local: LocalClock, startWall: Long, endWall: Long): Boolean {
        val durMin = (endWall - startWall) / 60_000.0
        if (durMin <= 0.0) return false
        val s = local.minuteOfDay(startWall).toDouble()
        val e = s + durMin
        val days = (e / 1440.0).toInt()
        for (day in 0..days) for (w in config.mealWindows) {
            val ws = day * 1440.0 + w.first
            val we = day * 1440.0 + w.last
            if (minOf(we, e) - maxOf(ws, s) >= MEAL_OVERLAP_MIN) return true
        }
        return false
    }

    companion object {
        const val MEAL_MISS_PENALTY = 60.0
        const val FEEDING_MISMATCH_PENALTY = 15.0
        const val PET_MISMATCH_PENALTY = 5.0
        const val CARE_LABEL_FRACTION = 0.5
        const val MEAL_OVERLAP_MIN = 30.0
    }
}
