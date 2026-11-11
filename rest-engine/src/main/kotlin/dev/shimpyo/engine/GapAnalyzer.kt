package dev.shimpyo.engine

/**
 * 마지막 기회 찾기. SPEC 4.3, docs/research/ai-science.md 8-2.
 *
 * 화장실 기회 o_1..o_n을 경로 거리순으로 놓고 정체를 반영한 도착 시간 a_i를 구한다. 판단 마감 D는
 * '누군가 필요해질 확률이 0.3에 닿을 때까지 남은 시간'이다 (RestroomNeedModel.groupDeadlineMin).
 * 세 가지 규칙으로 '여기를 놓치면 곤란한 곳'을 찾는다.
 * - 게이지(GAUGE): D 안에 닿는 마지막 기회 i*에서 다음 기회(없으면 목적지)가 D를 넘으면 i*.
 *   곧 "다음 기회까지 버티지 못할 확률 ≥ 0.3이고 여기까지는 버틸 수 있는" 곳. 단 그 간격이 15분 이상일 때만
 *   (휴게시설 간격 기준 최대 25km ≈ 고속 15분. 더 촘촘하면 공백이 아니다).
 * - 정체(CONGESTION): 정체 통과 시간 ≥ 30분(민감 탑승자가 있으면 20분)이고 정체 안에 기회가 없으면 정체 직전 마지막 기회.
 *   단, 정체 뒤 첫 기회가 D 안이면(그때까지 필요해질 확률 < 0.3) 묻지 않는다 (방금 다녀온 사람에게 매번 묻지 않도록).
 * - 커버리지(PREFERENCE): 과민성 방광(20분)·장(15분) 탑승자는 절박감을 시간으로 예측할 수 없으므로,
 *   기회 사이 간격이 그보다 긴 첫 구간의 앞 기회에서 묻는다 (AI §3-A, §9-1).
 * 여러 규칙이 걸리면 가장 가까운 기회(지금 정해야 하는 것)를 고르고, 같은 곳이면 정체 > 게이지 > 선호 순으로 이유를 붙인다.
 * 질문은 그 기회까지 [CoachConfig.askWindowNearM]~[CoachConfig.askWindowFarM]일 때만 한다 (PromptPolicy).
 */
internal class GapAnalyzer(private val config: CoachConfig) {

    enum class Trigger { CONGESTION, GAUGE, PREFERENCE }

    class LastChance(
        val opp: Opportunity,
        val index: Int,
        val trigger: Trigger,
        /** 이 기회 다음 기회(또는 목적지·정체 끝)까지 걸리는 시간 (초) */
        val gapS: Double,
        /** 정체 규칙일 때 정체 통과 시간 (초) */
        val congestionS: Double,
    )

    fun analyze(
        opps: List<Opportunity>,
        route: RouteContext,
        deadlineMin: Double?,
        sensitive: Boolean,
        preferredMaxGapMin: Int?,
    ): LastChance? {
        if (opps.isEmpty() || deadlineMin == null) return null
        val deadlineS = deadlineMin * 60.0
        var best: LastChance? = null
        fun consider(i: Int, trigger: Trigger, gapS: Double, congestionS: Double) {
            val b = best
            if (b == null || i < b.index || (i == b.index && trigger.ordinal < b.trigger.ordinal)) {
                best = LastChance(opps[i], i, trigger, gapS, congestionS)
            }
        }

        // 정체
        val thresholdS = (if (sensitive) config.congestionAlertSensitiveMin else config.congestionAlertMin) * 60.0
        for (j in 0 until route.jamN) {
            val s = route.jamStart[j]
            val e = route.jamEnd[j]
            val dur = route.jamDur[j]
            if (dur < thresholdS) continue
            if (opps.any { it.distanceM in s..e }) continue
            val i = opps.indexOfLast { it.distanceM < s }
            if (i < 0) continue
            val afterS = nextAfter(opps, e, route)
            if (afterS < deadlineS) continue // 정체 뒤 첫 기회까지 필요해질 확률이 0.3 미만
            consider(i, Trigger.CONGESTION, afterS - opps[i].arriveS, dur)
        }

        // 게이지 (판단 마감)
        val iStar = opps.indexOfLast { it.arriveS <= deadlineS }
        if (iStar >= 0) {
            val nextS = nextArrival(opps, iStar, route)
            val gapS = nextS - opps[iStar].arriveS
            if (!nextS.isNaN() && nextS > deadlineS && gapS >= config.lastChanceMinGapMin * 60.0) {
                consider(iStar, Trigger.GAUGE, gapS, 0.0)
            }
        }

        // 커버리지 (과민성 방광·장)
        if (preferredMaxGapMin != null) {
            val lim = preferredMaxGapMin * 60.0
            for (i in opps.indices) {
                val nextS = nextArrival(opps, i, route)
                if (!nextS.isNaN() && nextS - opps[i].arriveS > lim) {
                    consider(i, Trigger.PREFERENCE, nextS - opps[i].arriveS, 0.0)
                    break
                }
            }
        }
        return best
    }

    fun inAskWindow(lc: LastChance): Boolean =
        lc.opp.distanceM >= config.askWindowNearM && lc.opp.distanceM <= config.askWindowFarM

    /** i 다음 기회의 도착 시간. 다음 기회가 없으면 목적지 도착 시간(모르면 NaN) */
    private fun nextArrival(opps: List<Opportunity>, i: Int, route: RouteContext): Double {
        if (i + 1 < opps.size) return opps[i + 1].arriveS
        val d = route.destinationS
        return if (!d.isNaN() && d > opps[i].arriveS) d else Double.NaN
    }

    /** 정체 끝(endM) 뒤 첫 기회의 도착 시간. 없으면 목적지, 그것도 없으면 정체를 빠져나오는 시간 */
    private fun nextAfter(opps: List<Opportunity>, endM: Double, route: RouteContext): Double {
        for (o in opps) if (o.distanceM > endM) return o.arriveS
        if (!route.destM.isNaN() && route.destM > endM && !route.destS.isNaN()) return route.destS
        return route.arrivalAtDistance(endM)
    }
}
