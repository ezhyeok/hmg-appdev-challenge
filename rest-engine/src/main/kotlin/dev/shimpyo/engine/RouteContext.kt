package dev.shimpyo.engine

/** 경로 앞의 정차 기회 하나. distanceM은 경로 거리(모르면 직선거리 × 도로계수), arriveS는 지금부터 도착까지 초 */
internal class Opportunity(val stop: StopCandidate, val distanceM: Double, val arriveS: Double)

/**
 * 경로 위 거리 → 도착 시간 계산. SPEC 4.3, 4.4.
 *
 * 정체 밖은 자유 속도 v로, 정체 구간 안은 '정체 통과 시간 × 지나간 비율'로 센다.
 *   도착(d) = (d − 앞쪽 정체 길이) / v + 앞쪽 정체 통과 시간
 * v는 내비의 남은 거리·시간에서 정체 몫을 뺀 값(정체 밖 평균 속도). 모르면 현재 속도(30km/h 이상일 때),
 * 그것도 없으면 고속도로 평균 속도.
 * 후보에 etaS가 있으면 내비가 교통을 반영해 계산한 값으로 보고 그대로 쓴다.
 */
internal class RouteContext(private val config: CoachConfig) {
    private var jamCap = 8
    var jamN = 0
        private set
    var jamStart = DoubleArray(jamCap)
        private set
    var jamEnd = DoubleArray(jamCap)
        private set
    var jamDur = DoubleArray(jamCap)
        private set

    /** 정체 밖 자유 속도 (m/s) */
    var vFreeMps = config.highwayAvgSpeedKph / 3.6
        private set

    /** 정차 후보를 넣은 경로 거리 한계 (RouteState.candidatesUntilM). 목적지까지 다 넣었거나 모르면 NaN */
    var horizonM = Double.NaN
        private set

    /** 목적지까지 남은 거리·시간. 모르면 NaN */
    var destM = Double.NaN
        private set
    var destS = Double.NaN
        private set

    fun prepare(route: RouteState?, speedKph: Float?) {
        jamN = 0
        var jamLen = 0.0
        var jamTime = 0.0
        if (route != null) {
            for (c in route.congestion) {
                if (c.lengthM <= 0.0 || c.durationS <= 0.0) continue
                var start = c.startDistanceM
                var len = c.lengthM
                var dur = c.durationS
                if (start + len <= 0.0) continue // 이미 지나온 정체
                if (start < 0.0) { // 정체 안에 있다: 남은 부분만
                    val frac = (start + len) / len
                    dur *= frac
                    len = start + len
                    start = 0.0
                }
                addJam(start, start + len, dur)
                jamLen += len
                jamTime += dur
            }
        }
        sortJams()

        var v = Double.NaN
        val rd = route?.remainDistanceM
        val rt = route?.remainTimeS
        if (rd != null && rt != null && rd > 0.0 && rt > 0.0) {
            val freeLen = rd - jamLen
            val freeTime = rt - jamTime
            if (freeLen > 0.0 && freeTime > 0.0) v = freeLen / freeTime
            if (v.isNaN() || v < MIN_V || v > MAX_V) v = rd / rt
        }
        if (v.isNaN() || v < MIN_V || v > MAX_V) {
            v = if (speedKph != null && speedKph >= 30f) speedKph / 3.6 else config.highwayAvgSpeedKph / 3.6
        }
        vFreeMps = v.coerceIn(MIN_V, MAX_V)
        destM = if (rd != null && rd > 0.0) rd else Double.NaN
        destS = if (rt != null && rt > 0.0) rt else Double.NaN
        val h = route?.candidatesUntilM
        horizonM = if (h != null && h >= 0.0 && (destM.isNaN() || h < destM)) h else Double.NaN
    }

    fun distanceOf(c: StopCandidate): Double =
        c.routeDistanceM ?: c.straightDistanceM?.let { it * config.roadFactor } ?: Double.NaN

    fun arrivalS(c: StopCandidate, distanceM: Double): Double = c.etaS ?: arrivalAtDistance(distanceM)

    fun arrivalAtDistance(d: Double): Double {
        var lenBefore = 0.0
        var timeBefore = 0.0
        for (i in 0 until jamN) {
            val s = jamStart[i]
            if (d <= s) break
            val e = jamEnd[i]
            val covered = minOf(d, e) - s
            lenBefore += covered
            timeBefore += jamDur[i] * covered / (e - s)
        }
        return (d - lenBefore) / vFreeMps + timeBefore
    }

    /** 목적지 도착 시간 (초). 내비 값이 없으면 NaN */
    val destinationS: Double get() = destS

    /**
     * 후보를 거리순으로 out에 채운다 (out은 재사용 리스트).
     * 거리를 모르거나 이미 지나친(0 이하) 후보, filter를 통과하지 못한 후보는 뺀다.
     */
    fun opportunities(candidates: List<StopCandidate>, out: ArrayList<Opportunity>, filter: (StopCandidate) -> Boolean) {
        out.clear()
        for (i in candidates.indices) {
            val c = candidates[i]
            if (!filter(c)) continue
            val d = distanceOf(c)
            if (d.isNaN() || d <= 0.0) continue
            out += Opportunity(c, d, arrivalS(c, d))
        }
        // 거리순 (같으면 id순) — 결정적
        out.sortWith(OPP_ORDER)
    }

    private fun addJam(s: Double, e: Double, dur: Double) {
        if (jamN == jamCap) {
            jamCap *= 2
            jamStart = jamStart.copyOf(jamCap)
            jamEnd = jamEnd.copyOf(jamCap)
            jamDur = jamDur.copyOf(jamCap)
        }
        jamStart[jamN] = s
        jamEnd[jamN] = e
        jamDur[jamN] = dur
        jamN++
    }

    private fun sortJams() {
        for (i in 1 until jamN) {
            val s = jamStart[i]
            val e = jamEnd[i]
            val d = jamDur[i]
            var j = i - 1
            while (j >= 0 && jamStart[j] > s) {
                jamStart[j + 1] = jamStart[j]
                jamEnd[j + 1] = jamEnd[j]
                jamDur[j + 1] = jamDur[j]
                j--
            }
            jamStart[j + 1] = s
            jamEnd[j + 1] = e
            jamDur[j + 1] = d
        }
    }

    companion object {
        /** 자유 속도 범위 (m/s): 10~150 km/h */
        const val MIN_V = 10.0 / 3.6
        const val MAX_V = 150.0 / 3.6

        val OPP_ORDER: Comparator<Opportunity> =
            compareBy<Opportunity> { it.distanceM }.thenBy { it.arriveS }.thenBy { it.stop.id }

        /** 화장실 기회: 화장실이 있고, 차를 세울 수 있고, 닫히지 않았고, 진행 반대 방향이 아닌 곳 */
        fun isRestroomOpportunity(c: StopCandidate): Boolean =
            c.facilities.restroom && c.parkable && c.openNow != false && c.directionOk != false

        /** 휴식할 수 있는 곳: 차를 세울 수 있고 닫히지 않았고 진행 방향 */
        fun isRestOpportunity(c: StopCandidate): Boolean =
            c.parkable && c.openNow != false && c.directionOk != false

        /** 휴식에 특히 맞는 곳 (휴게소, 졸음쉼터) */
        fun isPreferredRestKind(c: StopCandidate): Boolean =
            c.kind == StopKind.HIGHWAY_REST_AREA || c.kind == StopKind.DROWSY_SHELTER

        fun isMealOpportunity(c: StopCandidate): Boolean =
            c.facilities.food && c.parkable && c.openNow != false && c.directionOk != false
    }
}
