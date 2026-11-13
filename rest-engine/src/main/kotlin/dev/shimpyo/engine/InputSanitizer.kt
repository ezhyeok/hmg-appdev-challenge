package dev.shimpyo.engine

import kotlin.math.abs

/**
 * 입력 경계 정리. 앱·내비가 넘긴 값 하나(NaN·무한대)가 도착 시간·게이지 전체를 NaN으로 만들지 않게,
 * 엔진에 들어오는 곳(update·urgent·plan)에서 한 번만 고친다 (감사 R23).
 *
 * - 거리·시간이 NaN·무한대면 '모름'(null)으로 본다. 정체 항목은 그런 값이 하나라도 있으면 뺀다.
 * - 음수가 뜻이 없는 값(남은 거리·시간, 다음 회전, ETA, 도보 거리, 주행가능거리)도 '모름'으로 본다.
 *   후보의 경로 거리 음수는 '지나침'이라 그대로 둔다 (RouteContext가 뺀다).
 * - 후보 범위(candidatesUntilM)만은 깨진 값(NaN·음수)을 0으로 본다: 이 값의 '모름'(null)은 '목적지까지 다 넣었다'는 뜻이라,
 *   깨진 값을 null로 바꾸면 '아무것도 모른다'가 '빠짐없다'로 뒤집힌다 ([horizon]).
 * - 차속은 크기만 쓴다: AAOS 차속은 후진 때 음수로 온다.
 * 모두 정상이면 입력 객체를 그대로 돌려준다 (매초 새로 만들지 않게).
 */
internal object InputSanitizer {

    fun clean(input: CoachInput): CoachInput {
        val speed = speed(input.speedKph)
        val samples = samples(input.samples)
        val route = input.route?.let(::route)
        val cands = candidates(input.candidates)
        val nearby = candidates(input.nearby)
        val range = nonNegative(input.rangeKm)
        val setTemp = input.cabin.setTempC?.takeIf { it.isFinite() }
        if (speed == input.speedKph && samples === input.samples && route === input.route && cands === input.candidates &&
            nearby === input.nearby && range == input.rangeKm && setTemp == input.cabin.setTempC
        ) return input
        return input.copy(
            speedKph = speed, samples = samples, route = route, candidates = cands, nearby = nearby, rangeKm = range,
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
        val until = horizon(r.candidatesUntilM)
        val jams = if (r.congestion.all(::validJam)) r.congestion else r.congestion.filter(::validJam)
        val dest = r.destination?.let(::destination)
        if (rd == r.remainDistanceM && rt == r.remainTimeS && man == r.nextManeuverDistanceM && until == r.candidatesUntilM &&
            jams === r.congestion && dest == r.destination
        ) return r
        return r.copy(remainDistanceM = rd, remainTimeS = rt, nextManeuverDistanceM = man, candidatesUntilM = until, congestion = jams, destination = dest)
    }

    /** 좌표가 NaN·범위 밖이거나 (0, 0)이면 모르는 것으로, 빈 이름도 모르는 것으로 본다. 둘 다 모르면 목적지를 모른다 */
    private fun destination(d: RouteDestination): RouteDestination? {
        val lat = d.lat?.takeIf { it.isFinite() && it in -90.0..90.0 }
        val lon = d.lon?.takeIf { it.isFinite() && it in -180.0..180.0 }
        val coords = lat != null && lon != null && !(lat == 0.0 && lon == 0.0)
        val name = d.name?.takeIf { it.isNotBlank() }
        if (!coords && name == null) return null
        val out = RouteDestination(name, if (coords) lat else null, if (coords) lon else null)
        return if (out == d) d else out
    }

    /**
     * 후보 목록을 넣은 범위. NaN·음수는 0('차 앞에 대해 아는 것이 없다')으로 본다: 그러면 엔진은 공백도 정체 전 마지막 기회도
     * 주장하지 않는다. null로 바꾸면 '목적지까지 빠짐없이 넣었다'가 되어 마지막 후보 뒤를 목적지까지의 공백으로 본다(감사 R23의 남은 부분).
     * +∞는 '끝없이 다 넣었다'로 읽어 null(빠짐없음)이다.
     */
    private fun horizon(v: Double?): Double? = when {
        v == null -> null
        v.isNaN() || v < 0.0 -> 0.0
        v.isInfinite() -> null
        else -> v
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

    /** 후보 하나 정리 (update 밖에서 받는 후보에도 쓴다: CoachEngine.noteAccepted) */
    fun candidate(c: StopCandidate): StopCandidate {
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
