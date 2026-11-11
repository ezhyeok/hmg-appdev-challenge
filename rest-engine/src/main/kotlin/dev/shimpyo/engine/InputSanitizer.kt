package dev.shimpyo.engine

import kotlin.math.abs

/**
 * 입력 경계 정리. 앱·내비가 넘긴 값 하나(NaN·무한대)가 도착 시간·게이지 전체를 NaN으로 만들지 않게,
 * 엔진에 들어오는 곳(update·urgent·plan)에서 한 번만 고친다 (감사 R23).
 *
 * - 거리·시간이 NaN·무한대면 '모름'(null)으로 본다. 정체 항목은 그런 값이 하나라도 있으면 뺀다.
 * - 음수가 뜻이 없는 값(남은 거리·시간, 다음 회전, 후보 범위, ETA, 도보 거리, 주행가능거리)도 '모름'으로 본다.
 *   후보의 경로 거리 음수는 '지나침'이라 그대로 둔다 (RouteContext가 뺀다).
 * - 차속은 크기만 쓴다: AAOS 차속은 후진 때 음수로 온다.
 * 모두 정상이면 입력 객체를 그대로 돌려준다 (매초 새로 만들지 않게).
 */
internal object InputSanitizer {

    fun clean(input: CoachInput): CoachInput {
        val speed = speed(input.speedKph)
        val samples = samples(input.samples)
        val route = input.route?.let(::route)
        val cands = candidates(input.candidates)
        val range = nonNegative(input.rangeKm)
        val setTemp = input.cabin.setTempC?.takeIf { it.isFinite() }
        if (speed == input.speedKph && samples === input.samples && route === input.route && cands === input.candidates &&
            range == input.rangeKm && setTemp == input.cabin.setTempC
        ) return input
        return input.copy(
            speedKph = speed, samples = samples, route = route, candidates = cands, rangeKm = range,
            cabin = if (setTemp == input.cabin.setTempC) input.cabin else input.cabin.copy(setTempC = setTemp),
        )
    }

    private fun speed(v: Float?): Float? = when {
        v == null || !v.isFinite() -> null
        v < 0f -> abs(v)
        else -> v
    }

    private fun nonNegative(v: Double?): Double? = v?.takeIf { it.isFinite() && it >= 0.0 }

    private fun positive(v: Double?): Double? = v?.takeIf { it.isFinite() && it > 0.0 }

    private fun finite(v: Double?): Double? = v?.takeIf { it.isFinite() }

    private fun samples(list: List<DrivingSample>): List<DrivingSample> {
        var out: ArrayList<DrivingSample>? = null
        for (i in list.indices) {
            val s = list[i]
            val sp = speed(s.speedKph)
            val st = s.steeringDeg?.takeIf { it.isFinite() }
            val fixed = if (sp == s.speedKph && st == s.steeringDeg) s else s.copy(speedKph = sp, steeringDeg = st)
            if (fixed !== s && out == null) out = ArrayList<DrivingSample>(list.size).also { o -> for (j in 0 until i) o += list[j] }
            out?.add(fixed)
        }
        return out ?: list
    }

    private fun route(r: RouteState): RouteState {
        val rd = positive(r.remainDistanceM)
        val rt = positive(r.remainTimeS)
        val man = nonNegative(r.nextManeuverDistanceM)
        val until = nonNegative(r.candidatesUntilM)
        val jams = if (r.congestion.all(::validJam)) r.congestion else r.congestion.filter(::validJam)
        if (rd == r.remainDistanceM && rt == r.remainTimeS && man == r.nextManeuverDistanceM && until == r.candidatesUntilM &&
            jams === r.congestion
        ) return r
        return r.copy(remainDistanceM = rd, remainTimeS = rt, nextManeuverDistanceM = man, candidatesUntilM = until, congestion = jams)
    }

    private fun validJam(c: CongestionSegment): Boolean =
        c.startDistanceM.isFinite() && c.lengthM.isFinite() && c.durationS.isFinite()

    private fun candidates(list: List<StopCandidate>): List<StopCandidate> {
        var out: ArrayList<StopCandidate>? = null
        for (i in list.indices) {
            val c = list[i]
            val fixed = candidate(c)
            if (fixed !== c && out == null) out = ArrayList<StopCandidate>(list.size).also { o -> for (j in 0 until i) o += list[j] }
            out?.add(fixed)
        }
        return out ?: list
    }

    private fun candidate(c: StopCandidate): StopCandidate {
        val route = finite(c.routeDistanceM)
        val straight = finite(c.straightDistanceM)
        val eta = nonNegative(c.etaS)
        val detour = c.detourS.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val walk = nonNegative(c.walkDistanceM)
        val lat = if (c.lat.isFinite()) c.lat else 0.0
        val lon = if (c.lon.isFinite()) c.lon else 0.0
        if (route == c.routeDistanceM && straight == c.straightDistanceM && eta == c.etaS && detour == c.detourS &&
            walk == c.walkDistanceM && lat == c.lat && lon == c.lon
        ) return c
        return c.copy(routeDistanceM = route, straightDistanceM = straight, etaS = eta, detourS = detour, walkDistanceM = walk, lat = lat, lon = lon)
    }
}
