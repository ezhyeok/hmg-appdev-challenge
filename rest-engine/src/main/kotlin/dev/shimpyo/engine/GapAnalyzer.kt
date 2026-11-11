package dev.shimpyo.engine

/**
 * 마지막 기회 찾기. SPEC 4.3, docs/research/ai-science.md 8-2.
 *
 * 화장실 기회 o_1..o_n을 경로 거리순으로 놓고 정체를 반영한 도착 시간 a_i를 구한다(거리순으로 단조, RouteContext).
 * 판단 마감 D는 '탑승자 중 누군가 필요해질 확률이 0.3에 닿을 때까지 남은 시간'이다 (RestroomNeedModel.deadlineMin).
 * 여럿이 타면 가장 급한 한 사람의 마감보다 이르다. 그 차이 때문에 앞당겨진 마지막 기회는 [LastChance.groupDriven]으로 표시해
 * 화면이 이유를 말하게 한다 (CoachEngine이 한 사람 기준 마감으로 한 번 더 판정해 가린다).
 *
 * 판단 범위: 목록을 자른 곳(RouteState.candidatesUntilM) 너머나 목적지 너머 후보는 공백의 증거로 쓰지 않는다.
 * 그 앞 마지막 기회 뒤의 공백은 자른 곳까지(아래 한계) 또는 목적지까지다. 예전에는 목적지 너머 후보까지의 시간을
 * 공백으로 세거나(도착 9분 뒤인데 '27분 공백'), 범위 밖 후보를 다음 기회로 봤다(감사 R4~R6).
 *
 * 세 가지 규칙으로 '여기를 놓치면 곤란한 곳'을 찾는다.
 * - 게이지(GAUGE): D 안에 닿는 마지막 기회 i*에서 다음 기회(없으면 목적지)가 D를 넘으면 i*.
 *   곧 "다음 기회까지 버티지 못할 확률 ≥ 0.3이고 여기까지는 버틸 수 있는" 곳. 단 그 간격이 15분 이상일 때만
 *   (휴게시설 간격 기준 최대 25km ≈ 고속 15분. 더 촘촘하면 공백이 아니다).
 *   D 안에 닿는 기회가 없으면(마감이 이미 지났다) 아직 안전하게 들를 수 있는 가장 가까운 기회(진입로 2km 밖)가 i*다.
 *   예전에는 이때 아무것도 고르지 않아, 게이지가 NOW(10분 미만)가 될 때까지 긴 공백 앞에서도 말이 없었다(감사 O1, PLAN_NOW와 같은 이유).
 *   단 이때는 공백의 끝이 실제로 아는 다음 기회나 목적지일 때만 묻는다. 마감이 지나면 마감이 아무것도 걸러 주지 않아 공백 크기만으로
 *   묻게 되는데, 자른 목록의 '여기까지는 없다'(아래 한계)는 목록이 빠짐없다는 앱의 약속에만 기댄다. 그 약속이 틀리면(후보를 덜 넣음)
 *   지나는 휴게소마다 울린다 (시연 후보 3곳에 범위 55km를 붙인 DemoScenarioTest에서 천안호두 뒤 '26분 공백'이 나왔다. 실제로는 15km 뒤 옥산휴게소).
 * - 정체(CONGESTION): 정체 통과 시간 ≥ 30분(민감 탑승자가 있으면 20분)이고 정체 안에 기회가 없으면 정체 직전 마지막 기회.
 *   내비가 한 정체를 여러 항목으로 쪼개 보내므로, 사이에 기회가 없고 1km 안으로 이어진 항목은 한 정체로 본다
 *   (예전에는 15분짜리 세 항목을 따로 재서 45분 정체를 놓쳤다, 감사 R1). 통과 시간은 그 묶음의 처음부터 끝까지 도착 시간 차이.
 *   정체 뒤 첫 기회(또는 목적지)가 D 안이면 묻지 않는다 (방금 다녀온 사람에게 매번 묻지 않도록).
 *   목록을 자른 곳보다 뒤에서 시작하는 정체는 그 앞에 기회가 없다고 말할 수 없어 보지 않고, 자른 곳이 정체 안이면 거기까지만 센다.
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
        /** 가장 급한 한 사람만 봤다면 여기가 아니었다: 여럿이 타서 앞당겨진 마지막 기회 */
        val groupDriven: Boolean = false,
    ) {
        fun groupDriven(): LastChance = if (groupDriven) this else LastChance(opp, index, trigger, gapS, congestionS, groupDriven = true)
    }

    fun analyze(
        opps: List<Opportunity>,
        route: RouteContext,
        deadlineMin: Double?,
        sensitive: Boolean,
        preferredMaxGapMin: Int?,
    ): LastChance? {
        if (opps.isEmpty() || deadlineMin == null) return null
        // 판단 범위 안의 기회만 (opps는 거리순이라 앞부분)
        val limitM = route.knownEndM
        var n = 0
        while (n < opps.size && opps[n].distanceM <= limitM) n++
        if (n == 0) return null
        val deadlineS = deadlineMin * 60.0
        var best: LastChance? = null
        fun consider(i: Int, trigger: Trigger, gapS: Double, congestionS: Double) {
            val b = best
            if (b == null || i < b.index || (i == b.index && trigger.ordinal < b.trigger.ordinal)) {
                best = LastChance(opps[i], i, trigger, gapS, congestionS)
            }
        }

        // 정체 (이어진 항목은 한 묶음)
        val thresholdS = (if (sensitive) config.congestionAlertSensitiveMin else config.congestionAlertMin) * 60.0
        val h = route.horizonM
        var j = 0
        while (j < route.jamN) {
            val s = route.jamStart[j]
            var e = route.jamEnd[j]
            var k = j + 1
            while (k < route.jamN && route.jamStart[k] - e <= JAM_JOIN_M && !anyIn(opps, n, e, route.jamStart[k])) {
                e = maxOf(e, route.jamEnd[k])
                k++
            }
            j = k
            if (!h.isNaN() && s > h) continue // 목록 범위 밖에서 시작: 그 앞에 기회가 없다고 말할 수 없다
            val end = if (!h.isNaN() && h < e) h else e // 범위 안쪽만 '기회 없음'을 안다
            val durS = route.arrivalAtDistance(end) - route.arrivalAtDistance(s)
            if (durS < thresholdS) continue
            if (anyIn(opps, n, s, end)) continue
            var i = -1
            for (x in 0 until n) if (opps[x].distanceM < s) i = x else break
            if (i < 0) continue
            val afterS = nextAfter(opps, n, end, route)
            if (afterS < deadlineS) continue // 정체 뒤 첫 기회까지 필요해질 확률이 0.3 미만
            consider(i, Trigger.CONGESTION, afterS - opps[i].arriveS, durS)
        }

        // 게이지 (판단 마감)
        var iStar = -1
        for (i in 0 until n) if (opps[i].arriveS <= deadlineS) iStar = i
        val passed = iStar < 0
        if (passed) {
            // 마감이 이미 지났다: 아직 안전하게 들를 수 있는 가장 가까운 기회
            for (i in 0 until n) if (opps[i].distanceM >= config.askWindowNearM) { iStar = i; break }
        }
        if (iStar >= 0) {
            val nextS = nextArrival(opps, n, iStar, route, lowerBound = !passed)
            val gapS = nextS - opps[iStar].arriveS
            if (!nextS.isNaN() && nextS > deadlineS && gapS >= config.lastChanceMinGapMin * 60.0) {
                consider(iStar, Trigger.GAUGE, gapS, 0.0)
            }
        }

        // 커버리지 (과민성 방광·장)
        if (preferredMaxGapMin != null) {
            val lim = preferredMaxGapMin * 60.0
            for (i in 0 until n) {
                val nextS = nextArrival(opps, n, i, route)
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

    /** [from, to] 안에 기회가 있는지 (판단 범위 안 n개 중) */
    private fun anyIn(opps: List<Opportunity>, n: Int, from: Double, to: Double): Boolean {
        for (x in 0 until n) {
            val d = opps[x].distanceM
            if (d > to) break
            if (d >= from) return true
        }
        return false
    }

    /**
     * i 다음 기회의 도착 시간. 다음 기회가 없으면 목적지 도착 시간(모르면 NaN).
     * 후보 목록을 잘랐으면(horizonM) 그 한계까지는 기회가 없다는 것만 안다: 한계 도착 시간(공백의 아래 한계)을 쓴다.
     */
    private fun nextArrival(opps: List<Opportunity>, n: Int, i: Int, route: RouteContext, lowerBound: Boolean = true): Double {
        if (i + 1 < n) return opps[i + 1].arriveS
        if (!route.horizonM.isNaN()) {
            if (!lowerBound) return Double.NaN
            val h = route.arrivalAfter(opps[i], maxOf(route.horizonM, opps[i].distanceM))
            return if (h > opps[i].arriveS) h else Double.NaN
        }
        val d = route.destinationS
        return if (!d.isNaN() && d > opps[i].arriveS) d else Double.NaN
    }

    /** 정체 끝(endM) 뒤 첫 기회의 도착 시간. 없으면 목적지, 그것도 없으면 정체를 빠져나오는 시간 */
    private fun nextAfter(opps: List<Opportunity>, n: Int, endM: Double, route: RouteContext): Double {
        for (x in 0 until n) if (opps[x].distanceM > endM) return opps[x].arriveS
        // 후보 목록을 잘랐으면 한계 너머는 모른다: 정체 끝과 한계 중 먼 쪽 도착 시간이 아래 한계다
        if (!route.horizonM.isNaN()) return route.arrivalAtDistance(maxOf(endM, route.horizonM))
        if (!route.destM.isNaN() && route.destM >= endM && !route.destS.isNaN()) return route.destS
        return route.arrivalAtDistance(endM)
    }

    companion object {
        /**
         * 정체 항목 사이 빈 구간이 이보다 짧고 그 사이에 기회가 없으면 한 정체로 본다 (m).
         * 내비는 한 정체를 도로 구간마다 쪼개 보내고(판교→부산 26항목, SPEC 8장) 항목은 붙어 있거나 짧게 떨어져 있다.
         * 1km 빈 구간은 고속 주행 30~40초라 그 사이에 쉬어 갈 수도, 정체를 벗어났다고 할 수도 없다
         */
        const val JAM_JOIN_M = 1_000.0
    }
}
