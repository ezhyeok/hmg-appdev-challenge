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
 * - 구간 상한: 첫 구간은 지금 상태의 판단 마감(누군가 필요해질 확률 0.3)과 피로 게이지 중 짧은 쪽,
 *   정차 뒤에는 min(모두 방금 다녀온 상태의 판단 마감, 그 시각에서 시작하는 2시간 휴식 리듬). 걷기·카시트 2시간 상한 포함.
 *   넘친 시간은 '위반 초'로 센다 (불가능해도 가장 덜 어기는 계획을 낸다).
 * - 비용(사전식): 위반 초 → 정차 수 → 선호 감점 → 정차·우회·진출입 시간 (진출입 손실 5분이 정차마다 붙어 묶음 정차가 유리하다).
 *   선호 감점: 식사 시간대(11:30~13:30, 17:30~19:30)를 지나는데 음식 있는 곳에서 식사하지 않음 60,
 *   영아 수유·기저귀 목적인데 교환대·수유실 없음 15, 반려견인데 반려동물 공간 없음 5.
 * - 각 정차에 목적을 묶는다: 화장실(항상), 휴식(90분 이상 운전 뒤), 식사(시간대 + 음식), 주유(필요 + 주유 시설),
 *   수유(영아 간격의 절반이 지났을 때), 반려동물. 정차 시간 = 목적별 시간의 최댓값.
 * - 같은 비용이면 먼저 찾은 해(앞쪽 후보)를 유지하므로 결정적이다.
 * 정차 시각의 식사 시간대 판정은 그 상태까지의 최선 경로 누적 정차 시간을 쓴다 (근사).
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
        val horizonS = if (hasDest) route.destS else opps[n - 1].arriveS
        val horizonM = if (hasDest && !route.destM.isNaN()) route.destM else opps[n - 1].distanceM
        val fuelLimitM = needs.rangeM?.let { it - config.fuelReserveKm * 1000.0 }
        val fuelNeeded = fuelLimitM != null && horizonM > fuelLimitM
        val mealPossible = opps.any { it.stop.facilities.food && inMeal(local, wallNow + (it.arriveS * 1000).toLong()) }

        // labels[node][f * 2 + a]
        val labels = Array(n + 1) { arrayOfNulls<Label>(4) }
        labels[0][(if (fuelNeeded) 0 else 1) * 2] = Label(0.0, 0, 0.0, 0.0, -1, -1, 0.0, emptySet(), 0.0)
        var terminal: Label? = null

        for (i in 0..n) {
            for (st in 0 until 4) {
                val from = labels[i][st] ?: continue
                val f = st / 2
                val a = st % 2
                val arrI = if (i == 0) 0.0 else opps[i - 1].arriveS
                val departWall = wallNow + ((arrI + from.accDwellS) * 1000).toLong()
                val legMax = if (i == 0) needs.firstLegMaxS else minOf(needs.restroomFullS, needs.fatigueFullS(departWall))

                for (j in i + 1..n) {
                    val o = opps[j - 1]
                    if (hasDest && o.arriveS >= route.destS) continue
                    val legS = maxOf(0.0, o.arriveS - arrI) + o.stop.detourS
                    var viol = maxOf(0.0, legS - legMax)
                    if (f == 0 && fuelLimitM != null && o.distanceM > fuelLimitM) {
                        viol += (o.distanceM - fuelLimitM) / route.vFreeMps
                    }
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
                        from.violS + viol, from.stops + 1, from.penalty + penalty, from.dwellS + extra,
                        i, st, from.accDwellS + extra, purposes, dwellS,
                    )
                    val idx = nf * 2 + na
                    if (better(cand, labels[j][idx])) labels[j][idx] = cand
                }

                // 끝점
                var viol = maxOf(0.0, horizonS - arrI - legMax)
                if (f == 0 && fuelLimitM != null && horizonM > fuelLimitM) viol += (horizonM - fuelLimitM) / route.vFreeMps
                var penalty = 0.0
                if (a == 0 && mealPossible &&
                    mealCrossed(local, wallNow, wallNow + ((horizonS + from.accDwellS) * 1000).toLong())
                ) penalty += MEAL_MISS_PENALTY
                val term = Label(from.violS + viol, from.stops, from.penalty + penalty, from.dwellS, i, st, from.accDwellS, emptySet(), 0.0)
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

        if (end.violS > 0.5) notes += Reason("PLAN_GAP", texts.planGapTooLong())
        if (stops.isEmpty() && end.violS <= 0.5) notes += Reason("PLAN_NONE_NEEDED", texts.planNoneNeeded(hasDest))
        stops.firstOrNull { Purpose.MEAL in it.purposes }?.let { notes += Reason("PLAN_MEAL", texts.planMeal(it.stop.name)) }
        stops.firstOrNull { Purpose.FUEL in it.purposes }?.let { notes += Reason("PLAN_FUEL", texts.planFuel(it.stop.name)) }
        if (needs.sleepyCurveS < horizonS + end.accDwellS) notes += Reason("PLAN_SLEEPY_TIME", texts.planSleepyTime())
        if (!hasDest) notes += Reason("PLAN_NO_DESTINATION", texts.planNoDestination())
        return PitStopPlan(stops, totalMin, notes)
    }

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
