package dev.shimpyo.engine

import kotlin.math.roundToInt

/**
 * 급해 모드 순위. SPEC 4.4.
 *
 * 총 소요(분) = 주행(etaS, 없으면 경로 거리 또는 직선거리 × 도로계수 ÷ 평균 속도)
 *            + 주차·도보(주유소 1, 졸음쉼터 1, 휴게소 2, 공중화장실·기타 2, 공영주차장 3 + 도보)
 *            + 유턴(필요 5, 모름 1)
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
    ): List<RankedStop> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        val hour = local.hourOfDay(wallNow)
        val night = hour >= config.nightStartHour || hour < config.nightEndHour
        val avgKph = if (speedKph != null && speedKph >= 60f) config.highwayAvgSpeedKph else config.cityAvgSpeedKph
        val scored = ArrayList<Scored>(candidates.size)
        for (c in candidates) {
            if (!c.parkable || c.openNow == false || !c.facilities.restroom) continue
            val dist = c.routeDistanceM ?: c.straightDistanceM?.let { it * config.roadFactor } ?: continue
            if (dist < 0.0) continue
            val driveMin = c.etaS?.let { it / 60.0 } ?: (dist / 1000.0 / avgKph * 60.0)
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
