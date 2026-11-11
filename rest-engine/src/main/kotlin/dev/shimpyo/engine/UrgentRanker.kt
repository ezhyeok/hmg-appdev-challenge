package dev.shimpyo.engine

import kotlin.math.roundToInt

/**
 * 급해 모드 순위. SPEC 4.4.
 *
 * 총 소요(분) = 주행([driveS])
 *            + 주차·도보(주유소 1, 졸음쉼터 1, 휴게소 2, 공중화장실·기타 2, 공영주차장 3 + 도보)
 *            + 유턴(필요 5, 모름 1)
 * 주행 시간은 급해 모드의 모든 출력(순위, 목록의 총 소요, 음성의 '약 N분', 카드의 도착 시간)이 함께 쓰는 값 하나다:
 * 1. 내비 ETA(etaS)가 있으면 그것
 * 2. 길안내 경로 위 후보(경로 거리를 안다)면 엔진의 도착 시간 (RouteContext: 정체 구간, 앞 후보의 지연). 다른 카드의 도착 시간과 같은 값
 * 3. 그 밖에는 거리(경로 거리, 없으면 직선거리 × 도로계수) ÷ 평균 속도. 단 엔진의 도착 시간이 그보다 늦으면 그 값
 *    (더 가까운 후보의 내비 ETA가 알려 준 지연은 그 뒤 후보에도 있다)
 * 예전에는 etaS가 없으면 거리 ÷ 80km/h만 썼다: 명절 정체 1km 앞에서 "가졸음쉼터까지 약 6분"이라고 말하고 같은 카드에는 17분을
 * 보였고, 내비 25분인 8km 앞 휴게소가 재지 못한 20km 앞 졸음쉼터(21.5분, 엔진 도착 시간으로는 36분)에 밀렸다 (닫힘 검증 E1, 감사 U6).
 * 순위 점수 = 총 소요 + 영업 여부 모름 3 − 야간(22~06시) 비상벨·CCTV 2 − 영아 동승 시 기저귀 교환대 3
 * 고속도로 졸음쉼터는 지침상 화장실·여성화장실 비상벨·방범 CCTV가 기본 시설이라, 데이터에 표시가 없어도 야간 가점을 준다 (AI §3-C).
 * 제외: 주차 불가, 영업 종료, 화장실 없음, 거리 정보 없음.
 * RankedStop.totalTimeS에는 감점·가점을 뺀 실제 예상 시간을 넣는다.
 */
internal class UrgentRanker(private val config: CoachConfig, private val texts: CoachTexts) {

    private class Scored(val stop: StopCandidate, val driveMin: Double, val parkWalkMin: Double, val uTurnMin: Double, val score: Double) {
        val totalMin: Double get() = driveMin + parkWalkMin + uTurnMin
    }

    fun rank(
        candidates: List<StopCandidate>,
        wallNow: Long,
        local: LocalClock,
        hasInfant: Boolean,
        speedKph: Float?,
        limit: Int,
        /** 이번 경로 (정체, 후보 도착 시간). null이면 내비 ETA와 평균 속도만 쓴다 */
        route: RouteContext? = null,
    ): List<RankedStop> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        val hour = local.hourOfDay(wallNow)
        val night = hour >= config.nightStartHour || hour < config.nightEndHour
        route?.ensureIndex(candidates)
        val scored = ArrayList<Scored>(candidates.size)
        for (c in candidates) {
            if (!c.parkable || c.openNow == false || !c.facilities.restroom) continue
            val driveS = driveS(c, speedKph, route)
            if (driveS.isNaN()) continue
            val driveMin = driveS / 60.0
            val park = parkWalkMin(c)
            val uTurn = when (c.directionOk) {
                false -> config.uTurnPenaltyMin
                null -> config.uTurnUnknownPenaltyMin
                true -> 0.0
            }
            var score = driveMin + park + uTurn
            if (c.openNow == null && !c.facilities.open24h) score += config.unknownOpenPenaltyMin
            if (night && nightSafe(c)) score -= config.nightSafetyBonusMin
            if (hasInfant && c.facilities.diaperTable) score -= config.diaperTableBonusMin
            scored += Scored(c, driveMin, park, uTurn, score)
        }
        scored.sortWith(compareBy<Scored> { it.score }.thenBy { it.totalMin }.thenBy { it.stop.id })
        val n = minOf(limit, scored.size)
        val out = ArrayList<RankedStop>(n)
        for (i in 0 until n) {
            val s = scored[i]
            out += RankedStop(s.stop, s.totalMin * 60.0, reasons(s, night, hasInfant))
        }
        return out
    }

    /**
     * 그곳까지 주행 시간 (초, 클래스 설명의 1~3). 거리를 모르거나 지나친 곳이면 NaN.
     * [route]는 이 후보가 든 목록으로 도착 시간을 계산해 둔 것이어야 한다 ([rank]가 맞춘다. 따로 부를 때는 엔진의 이번 경로).
     */
    fun driveS(c: StopCandidate, speedKph: Float?, route: RouteContext?): Double {
        val dist = c.routeDistanceM ?: c.straightDistanceM?.let { it * config.roadFactor } ?: return Double.NaN
        if (dist < 0.0) return Double.NaN
        c.etaS?.let { return it }
        val arrival = route?.arrivalOf(c) ?: Double.NaN
        if (route != null && route.guiding && c.routeDistanceM != null && arrival.isFinite()) return arrival
        val avgKph = if (speedKph != null && speedKph >= 60f) config.highwayAvgSpeedKph else config.cityAvgSpeedKph
        val flat = dist / 1000.0 / avgKph * 3_600.0
        return if (arrival.isFinite()) maxOf(flat, arrival) else flat
    }

    /** 비상벨·CCTV가 있거나, 규정상 갖춘 고속도로 졸음쉼터 */
    fun nightSafe(c: StopCandidate): Boolean =
        c.facilities.emergencyBell || c.facilities.cctv || c.kind == StopKind.DROWSY_SHELTER

    fun parkWalkMin(c: StopCandidate): Double = when (c.kind) {
        StopKind.GAS_STATION -> config.parkWalkGasStationMin
        StopKind.HIGHWAY_REST_AREA -> config.parkWalkRestAreaMin
        StopKind.DROWSY_SHELTER -> config.parkWalkShelterMin
        StopKind.PUBLIC_PARKING ->
            config.parkWalkParkingMin + (c.walkDistanceM?.let { it / config.walkSpeedMPerMin } ?: config.defaultWalkMin)
        StopKind.PUBLIC_RESTROOM, StopKind.OTHER ->
            config.parkWalkOtherMin + (c.walkDistanceM?.let { it / config.walkSpeedMPerMin } ?: 0.0)
    }

    private fun reasons(s: Scored, night: Boolean, hasInfant: Boolean): List<Reason> {
        val c = s.stop
        val r = ArrayList<Reason>(5)
        r += Reason(
            "URGENT_TIME",
            texts.reasonUrgentTime(
                s.totalMin.roundToInt().coerceAtLeast(1),
                s.driveMin.roundToInt(),
                s.parkWalkMin.roundToInt(),
                s.uTurnMin.roundToInt(),
            ),
        )
        when {
            c.facilities.open24h -> r += Reason("URGENT_OPEN_24H", texts.reasonUrgentOpen24h())
            c.openNow == true -> r += Reason("URGENT_OPEN", texts.reasonUrgentOpen())
            else -> r += Reason("URGENT_OPEN_UNKNOWN", texts.reasonUrgentOpenUnknown())
        }
        when (c.directionOk) {
            true -> r += Reason("URGENT_DIRECTION", texts.reasonUrgentDirection())
            false -> r += Reason("URGENT_UTURN", texts.reasonUrgentUTurn())
            null -> {}
        }
        if (night && nightSafe(c)) {
            r += if (c.facilities.emergencyBell || c.facilities.cctv) {
                Reason("URGENT_SAFETY", texts.reasonUrgentSafety(c.facilities.emergencyBell, c.facilities.cctv))
            } else {
                Reason("URGENT_SAFETY", texts.reasonUrgentShelterSafety())
            }
        }
        if (hasInfant && c.facilities.diaperTable) r += Reason("URGENT_DIAPER", texts.reasonUrgentDiaper())
        return r
    }
}
