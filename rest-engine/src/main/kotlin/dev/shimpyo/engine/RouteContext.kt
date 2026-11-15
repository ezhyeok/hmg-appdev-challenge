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
 *
 * 정체 목록 정리 ([buildJams]): 내비는 한 정체를 여러 항목으로 쪼개고, 같은 항목을 두 번 보내거나 목적지 너머까지 보낼 수 있다.
 * - 같은 항목이 두 번 온 것(시작·길이·통과 시간·도로명·시작/끝 이름이 모두 같다)은 한 번만 센다. 예전에는 더해서 +20분이었다(감사 R12).
 * - 서로 다른 항목의 범위가 겹치면 뒤 항목을 앞 항목이 끝나는 곳에 이어 놓는다(길이와 통과 시간을 그대로 지킨다).
 *   내비는 시작 거리를 반올림해 보낸다(가까우면 100m, 10km 밖은 1km 단위): 2026-11-12 실측 목록은 98km에 8개, 99km에 6개 항목이
 *   같은 시작 거리로 왔고, 길이·평균 속도가 서로 다른 별개 구간이었다(내비 요약도 길이를 다 더한 값이다). 겹친 곳을 한 번만 세는
 *   합집합으로는 101km 안 29항목 2,480m·385초가 1,110m·200초로 줄었다: 여러 항목으로 오는 먼 정체일수록 작게 보였다.
 * - 그 뒤 [0, 목적지]로 자른다. 예전에는 목적지 너머 정체까지 세서 자유 속도가 틀어졌다(감사 R11).
 *
 * 후보 도착 시간: etaS(내비가 교통을 반영해 계산한 값)가 있으면 그것, 없으면 위 식. 그리고 경로 거리순으로
 * 엔진이 '뒤 후보에 먼저 닿는다'고 지어내지 않게 한다 (감사 R13: 20km 후보는 내비 40분인데 25km 후보가 엔진 계산 15분).
 * - 엔진 계산 후보: 더 가까운 후보들의 지연(도착 − 엔진 계산) 중 가장 큰 것만큼 늦춘다. 앞에서 내비만 아는 정체는 뒤에도 있다.
 * - 내비 ETA 후보: 더 가까운 내비 ETA보다 이르면 그만큼 올린다(내비 값끼리 어긋나면 한쪽이 늦게 온 값이다). 그 밖에는 그대로 믿는다.
 *   앞 후보의 엔진 계산이 뒤 내비 ETA보다 늦은 경우는 두는데, 내비가 더 빠르다는 뜻이라 엔진 쪽이 보수적일 뿐이다(닿지 못한다고 보는 쪽).
 * 같은 거리의 후보끼리는 서로 묶지 않는다.
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

    // 정체 목록을 정리할 때 쓰는 작업 배열 (재사용): 남긴 항목의 원래 위치
    private var rawIdx = IntArray(8)

    /** 정체 밖 자유 속도 (m/s) */
    var vFreeMps = config.highwayAvgSpeedKph / 3.6
        private set

    /** 길안내 중인지 (RouteState.guiding). 급해 순위가 '길안내 경로 위 후보'를 가리는 데 쓴다 */
    var guiding = false
        private set

    /** 정차 후보를 넣은 경로 거리 한계 (RouteState.candidatesUntilM). 목적지까지 다 넣었거나 모르면 NaN */
    var horizonM = Double.NaN
        private set

    /** 목적지까지 남은 거리·시간. 모르면 NaN */
    var destM = Double.NaN
        private set
    var destS = Double.NaN
        private set

    // 후보 도착 시간 색인 (prepare마다 비운다)
    private var idxCands: List<StopCandidate> = emptyList()
    private var idxArr = DoubleArray(16)
    private var idxDist = DoubleArray(16)
    private var order = IntArray(16)

    fun prepare(route: RouteState?, speedKph: Float?) {
        idxCands = emptyList()
        guiding = route?.guiding == true
        val rd = route?.remainDistanceM
        val rt = route?.remainTimeS
        destM = if (rd != null && rd.isFinite() && rd > 0.0) rd else Double.NaN
        destS = if (rt != null && rt.isFinite() && rt > 0.0) rt else Double.NaN
        buildJams(route?.congestion.orEmpty())
        var jamLen = 0.0
        var jamTime = 0.0
        for (i in 0 until jamN) {
            jamLen += jamEnd[i] - jamStart[i]
            jamTime += jamDur[i]
        }

        var v = Double.NaN
        if (!destM.isNaN() && !destS.isNaN()) {
            val freeLen = destM - jamLen
            val freeTime = destS - jamTime
            if (freeLen > 0.0 && freeTime > 0.0) v = freeLen / freeTime
            if (v.isNaN() || v < MIN_V || v > MAX_V) v = destM / destS
        }
        if (v.isNaN() || v < MIN_V || v > MAX_V) {
            v = if (speedKph != null && speedKph >= 30f) speedKph / 3.6 else config.highwayAvgSpeedKph / 3.6
        }
        vFreeMps = v.coerceIn(MIN_V, MAX_V)
        val h = route?.candidatesUntilM
        horizonM = if (h != null && h.isFinite() && h >= 0.0 && (destM.isNaN() || h < destM)) h else Double.NaN
    }

    /** 후보 목록이 빠짐없다고 볼 수 있는 경로 거리 끝: 자른 곳(horizonM), 아니면 목적지. 둘 다 모르면 무한대 */
    val knownEndM: Double
        get() = when {
            !horizonM.isNaN() -> horizonM
            !destM.isNaN() -> destM
            else -> Double.POSITIVE_INFINITY
        }

    fun distanceOf(c: StopCandidate): Double =
        c.routeDistanceM ?: c.straightDistanceM?.let { it * config.roadFactor } ?: Double.NaN

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

    /**
     * 기회 o 뒤 거리 d까지의 도착 시간 (아래 한계). o에 붙은 지연(내비 ETA)이 그 뒤에도 이어진다고 본다.
     * 후보 목록을 자른 곳(horizon) 같은 '기회는 아니지만 거기까지는 없다'는 지점에 쓴다.
     */
    fun arrivalAfter(o: Opportunity, d: Double): Double {
        val est = arrivalAtDistance(d)
        return maxOf(est, o.arriveS + (est - arrivalAtDistance(o.distanceM)))
    }

    /** 목적지 도착 시간 (초). 내비 값이 없으면 NaN */
    val destinationS: Double get() = destS

    /**
     * 이번 후보 목록의 도착 시간을 계산해 둔다 (경로 거리순으로 단조). [opportunities]·[arrivalOf]가 쓰고,
     * 처음 부를 때 저절로 계산된다. [prepare]하면 비운다.
     */
    fun index(candidates: List<StopCandidate>) {
        idxCands = candidates
        val n = candidates.size
        if (idxArr.size < n) {
            val cap = maxOf(n, idxArr.size * 2)
            idxArr = DoubleArray(cap)
            idxDist = DoubleArray(cap)
            order = IntArray(cap)
        }
        var m = 0
        for (i in 0 until n) {
            val c = candidates[i]
            val d = distanceOf(c)
            idxDist[i] = d
            if (d.isNaN() || d <= 0.0) {
                idxArr[i] = Double.NaN
                continue
            }
            idxArr[i] = c.etaS ?: arrivalAtDistance(d)
            // 진행 반대 방향(유턴) 후보의 거리는 경로를 따라간 거리가 아니라 단조 조건에 넣지 않는다
            if (c.directionOk != false) order[m++] = i
        }
        // 경로 거리순 (같으면 id순) — 결정적. 후보는 수십 개라 삽입 정렬
        for (a in 1 until m) {
            val x = order[a]
            var b = a - 1
            while (b >= 0 && before(candidates, x, order[b])) {
                order[b + 1] = order[b]
                b--
            }
            order[b + 1] = x
        }
        // 더 가까운 내비 ETA 중 가장 늦은 것과, 더 가까운 후보들의 가장 큰 지연(도착 − 엔진 계산). 같은 거리 후보끼리는 서로 묶지 않는다
        var maxNavi = Double.NEGATIVE_INFINITY
        var maxDelay = 0.0
        var k = 0
        while (k < m) {
            var g = k
            while (g < m && idxDist[order[g]] == idxDist[order[k]]) g++
            val est = arrivalAtDistance(idxDist[order[k]])
            for (x in k until g) {
                val i = order[x]
                val own = idxArr[i]
                idxArr[i] = if (candidates[i].etaS != null) maxOf(own, maxNavi) else maxOf(own, est + maxDelay)
            }
            for (x in k until g) {
                val i = order[x]
                val a = idxArr[i]
                if (candidates[i].etaS != null && a > maxNavi) maxNavi = a
                if (a - est > maxDelay) maxDelay = a - est
            }
            k = g
        }
    }

    private fun before(cands: List<StopCandidate>, x: Int, y: Int): Boolean {
        val dx = idxDist[x]
        val dy = idxDist[y]
        if (dx != dy) return dx < dy
        return cands[x].id < cands[y].id
    }

    /** [candidates]의 도착 시간이 계산돼 있게 한다 ([arrivalOf]를 [opportunities] 없이 쓸 때) */
    fun ensureIndex(candidates: List<StopCandidate>) {
        if (candidates !== idxCands) index(candidates)
    }

    /**
     * 이번 후보의 도착 시간 (초). 지금 목록에 같은 후보(같은 객체, 없으면 같은 id)가 없거나 거리를 모르면 NaN.
     * 앱에 내보내는 추천·질문의 etaS를 이것 하나로 채운다.
     */
    fun arrivalOf(c: StopCandidate): Double {
        val list = idxCands
        for (i in list.indices) if (list[i] === c) return idxArr[i]
        for (i in list.indices) if (list[i].id == c.id) return idxArr[i]
        return Double.NaN
    }

    /**
     * 후보를 거리순으로 out에 채운다 (out은 재사용 리스트).
     * 거리를 모르거나 이미 지나친(0 이하) 후보, filter를 통과하지 못한 후보는 뺀다.
     */
    fun opportunities(candidates: List<StopCandidate>, out: ArrayList<Opportunity>, filter: (StopCandidate) -> Boolean) {
        if (candidates !== idxCands) index(candidates)
        out.clear()
        for (i in candidates.indices) {
            val c = candidates[i]
            if (!filter(c)) continue
            val d = idxDist[i]
            if (d.isNaN() || d <= 0.0) continue
            out += Opportunity(c, d, idxArr[i])
        }
        // 거리순 (같으면 id순) — 결정적
        out.sortWith(OPP_ORDER)
    }

    /**
     * 정체 항목 → 겹치지 않는 구간 목록 (jamStart/jamEnd/jamDur, 시작순). 클래스 설명의 '정체 목록 정리'.
     * 1. 값이 이상한 항목(길이·시간 0 이하, NaN·무한대), 이미 지나온 항목, 목적지 너머에서 시작하는 항목은 뺀다.
     * 2. 같은 항목이 두 번 온 것은 한 번만 남긴다 ([sameItem]).
     * 3. 시작순으로 놓고(같으면 받은 순서: 내비는 경로 순서로 보낸다), 앞 항목과 겹치면 앞 항목이 끝나는 곳부터 이어 놓는다.
     * 4. [0, 목적지]로 자른다 (잘린 만큼 통과 시간도 비례해서 줄인다). 이어진 같은 속도 구간은 하나로 합친다.
     */
    private fun buildJams(items: List<CongestionSegment>) {
        jamN = 0
        val n = items.size
        if (n == 0) return
        if (rawIdx.size < n) rawIdx = IntArray(maxOf(n, rawIdx.size * 2))
        var r = 0
        outer@ for (i in 0 until n) {
            val c = items[i]
            val start = c.startDistanceM
            val len = c.lengthM
            val dur = c.durationS
            if (!start.isFinite() || !len.isFinite() || !dur.isFinite() || len <= 0.0 || dur <= 0.0) continue
            if (start + len <= 0.0) continue // 이미 지나온 정체
            if (!destM.isNaN() && start >= destM) continue // 목적지 너머는 경로가 아니다
            for (k in 0 until r) if (sameItem(items[rawIdx[k]], c)) continue@outer
            rawIdx[r++] = i
        }
        // 시작순. 항목은 수십 개라 삽입 정렬 (안정 정렬: 시작 거리가 같으면 받은 순서)
        for (a in 1 until r) {
            val x = rawIdx[a]
            val sx = items[x].startDistanceM
            var b = a - 1
            while (b >= 0 && items[rawIdx[b]].startDistanceM > sx) {
                rawIdx[b + 1] = rawIdx[b]
                b--
            }
            rawIdx[b + 1] = x
        }
        var cursor = Double.NEGATIVE_INFINITY // 앞 항목이 끝난 곳
        for (k in 0 until r) {
            val c = items[rawIdx[k]]
            val from = maxOf(c.startDistanceM, cursor)
            val to = from + c.lengthM
            cursor = to
            val s = maxOf(from, 0.0) // 정체 안에 있다: 남은 부분만
            val e = if (destM.isNaN()) to else minOf(to, destM)
            if (e <= s) continue
            val pace = c.durationS / c.lengthM
            val last = jamN - 1
            if (last >= 0 && jamEnd[last] == s && samePace(jamDur[last] / (jamEnd[last] - jamStart[last]), pace)) {
                jamDur[last] += pace * (e - s)
                jamEnd[last] = e
            } else {
                addJam(s, e, pace * (e - s))
            }
        }
    }

    /**
     * 같은 항목이 두 번 온 것인지: 시작 거리·길이·통과 시간·도로명·시작/끝 이름이 모두 같을 때만.
     * 이름과 길이만으로는 가릴 수 없다: 실측 목록에는 같은 시작 거리(반올림)에 길이 100m인 서로 다른 항목이 여럿 있고,
     * 이름이 비어 오는 항목도 있다. 조금이라도 다르면 별개 구간으로 보고 이어 놓는다(작게 세는 쪽이 더 나쁘다: 먼 정체를 놓친다)
     */
    private fun sameItem(a: CongestionSegment, b: CongestionSegment): Boolean =
        a.startDistanceM == b.startDistanceM && a.lengthM == b.lengthM && a.durationS == b.durationS &&
            a.roadName == b.roadName && a.startName == b.startName && a.endName == b.endName

    private fun samePace(p: Double, q: Double): Boolean = kotlin.math.abs(p - q) <= 1e-9 * maxOf(p, q)

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

        /** 주유·충전할 수 있는 곳 */
        fun isFuelOpportunity(c: StopCandidate, energy: EnergyKind? = null): Boolean =
            (when (energy) {
                EnergyKind.ELECTRIC -> c.facilities.evCharger
                EnergyKind.FUEL -> c.facilities.fuel
                null -> c.facilities.fuel || c.facilities.evCharger
            }) && isRestOpportunity(c)
    }
}
