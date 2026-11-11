package dev.shimpyo.app.coach

import dev.shimpyo.app.data.Geo
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.StopCandidate
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 시연 모드. 에뮬레이터는 실제로 달리지 않고 교통 정보도 비어 있을 수 있어서, Virtual Console로 못 바꾸는 것
 * (위치 이동, 시각, 정체 구간, 주행 시작 시각)만 시나리오대로 넣는다. 차량 신호(기어·차속·조향·문)는
 * Virtual Console 시나리오(tools/vc/shimpyo-*.vc)로 바꾼다. 화면에는 시연 중임을 표시한다.
 *
 * 시나리오 (docs/product/SPEC.md 7장)
 * - holiday: 경부선 남행, 아이 동승, 안성휴게소 3km 뒤부터 40분 정체 → 마지막 화장실 질문
 * - fatigue: 오후 3시, 연속 주행 95분, 망향휴게소 약 8km 앞 → 피로 게이지와 휴식 제안
 * - urgent: 밤 11시 강남역 부근, 영아 동승 → 급해 모드
 * - stop: 시간을 10배로 돌려 휴게소 정차(15분 이상)와 게이지 회복을 짧게 보여 준다
 *
 * 시간 배속: 엔진은 입력 시각으로만 판단하므로, 시연 시각을 실제보다 빠르게 흐르게 하면 15분 휴식이나
 * 25분 뒤의 피로 단계를 몇 분 안에 보여 줄 수 있다. 1초 간격 호출이 배속 후에도 2분을 넘지 않게 한다.
 */
class DemoDirector {
    data class Pt(val lat: Double, val lon: Double)

    private class Scenario(
        val name: String,
        val path: List<Pt>,
        /** 실제 1초에 가상으로 가는 거리 (m) */
        val metersPerSecond: Double,
        val occupants: List<Occupant>,
        /** 시연 시각 (하루 중 시각). null이면 실제 시각 */
        val timeOfDay: LocalTime?,
        /** 시작할 때 이미 달린 시간 (분) */
        val drivenMin: Int,
        /** 경로 위 정체: 경로 시작점 기준 시작 거리, 길이, 소요 시간 */
        val congestion: List<Triple<Double, Double, Double>>,
        val routeTotalM: Double,
        /** 시연 시각이 실제보다 몇 배 빨리 흐르는지 */
        val timeScale: Double = 1.0,
    )

    @Volatile private var scenario: Scenario? = null
    @Volatile private var startedAtReal = 0L
    @Volatile private var clockOffsetMs = 0L

    val name: String? get() = scenario?.name
    val active: Boolean get() = scenario != null
    /** 시연 중에는 requestTrip 대신 가상 경로 거리를 쓴다 */
    val oracleEnabled: Boolean get() = scenario == null

    fun now(): Long {
        val real = System.currentTimeMillis()
        val s = scenario ?: return real
        return startedAtReal + clockOffsetMs + ((real - startedAtReal) * s.timeScale).toLong()
    }

    /** 시나리오 시작. 탑승자와 '이미 달린 시간'을 돌려준다 (서비스가 엔진 프로필에 반영) */
    fun start(name: String?, realNow: Long = System.currentTimeMillis()): Pair<List<Occupant>, Int>? {
        val s = when (name) {
            "holiday" -> holiday()
            "fatigue" -> fatigue()
            "urgent" -> urgent()
            "stop" -> stopDemo()
            else -> null
        }
        scenario = s
        startedAtReal = realNow
        clockOffsetMs = s?.timeOfDay?.let { tod ->
            val zone = ZoneId.of("Asia/Seoul")
            val target = LocalDate.now(zone).atTime(tod).atZone(zone).toInstant().toEpochMilli()
            target - realNow
        } ?: 0L
        return s?.let { it.occupants to it.drivenMin }
    }

    /** 가상 위치와 진행 방향. 시연이 아니면 null */
    fun position(): Triple<Double, Double, Float>? {
        val s = scenario ?: return null
        val travelled = progressM(s)
        return pointAt(s.path, travelled)
    }

    /** 경로 상태와 정체를 시나리오 값으로 바꾼다 */
    fun apply(input: CoachInput): CoachInput {
        val s = scenario ?: return input
        val travelled = progressM(s)
        val congestion = s.congestion.mapNotNull { (start, len, dur) ->
            val fromCar = start - travelled
            if (fromCar + len < 0) null else CongestionSegment(startDistanceM = fromCar, lengthM = len, durationS = dur, type = "HEAVY", roadName = "경부고속도로", startName = "시연 정체 시작", endName = "시연 정체 끝")
        }
        val remain = (s.routeTotalM - travelled).coerceAtLeast(0.0)
        val route = (input.route ?: RouteState(guiding = true)).copy(
            guiding = true,
            remainDistanceM = remain,
            remainTimeS = remain / 25.0,
            congestion = congestion,
        )
        return input.copy(route = route)
    }

    /** 시연 중에는 후보의 경로 거리를 가상 경로를 따라 다시 계산한다 */
    fun candidates(list: List<StopCandidate>): List<StopCandidate> {
        val s = scenario ?: return list
        val travelled = progressM(s)
        return list.mapNotNull { c ->
            val along = alongPath(s.path, Pt(c.lat, c.lon)) ?: return@mapNotNull null
            val ahead = along - travelled
            if (ahead < -200) null else c.copy(routeDistanceM = ahead.coerceAtLeast(0.0), etaS = ahead.coerceAtLeast(0.0) / 25.0, directionOk = true)
        }
    }

    private fun progressM(s: Scenario): Double = (System.currentTimeMillis() - startedAtReal) / 1000.0 * s.metersPerSecond

    // ------------------------------------------------------------ 시나리오

    private fun driver() = Occupant("driver", OccupantType.ADULT, label = "운전자")

    /** 경부선 남행 (OpenStreetMap 휴게소 좌표를 잇는 단순화 경로) */
    private val gyeongbuSouth = listOf(
        Pt(37.3050, 127.0960),  // 신갈 부근
        Pt(37.23518, 127.10509), // 기흥휴게소
        Pt(37.1500, 127.1100),
        Pt(37.07616, 127.13158), // 안성 부근
        Pt(37.01354, 127.14516), // 안성휴게소(부산방향)
        Pt(36.9300, 127.1650),
        Pt(36.85461, 127.18136), // 망향휴게소(부산방향)
        Pt(36.7600, 127.2100),
    )

    private fun holiday(): Scenario {
        val anseong = alongPath(gyeongbuSouth, Pt(37.01354, 127.14516)) ?: 30_000.0
        return Scenario(
            name = "holiday",
            path = gyeongbuSouth,
            // 실제 1초에 가상 200m. 시각도 7.2배로 흘러 가상 시속 100km와 맞는다 → 안성휴게소 앞까지 2분 안팎
            metersPerSecond = 200.0,
            timeScale = 7.2,
            occupants = listOf(driver(), Occupant("kid", OccupantType.CHILD, ageYears = 7, label = "7살 아이")),
            timeOfDay = null,
            drivenMin = 40,
            congestion = listOf(Triple(anseong + 3_000.0, 18_000.0, 40 * 60.0)),
            routeTotalM = 330_000.0,
        )
    }

    private fun fatigue(): Scenario {
        val start = listOf(Pt(36.9200, 127.1680)) + gyeongbuSouth.dropWhile { it.lat > 36.9 }
        return Scenario(
            name = "fatigue",
            path = start,
            // 10배속: 실제 1초에 가상 250m(시속 90km)
            metersPerSecond = 250.0,
            timeScale = 10.0,
            occupants = listOf(driver()),
            timeOfDay = LocalTime.of(15, 0),
            drivenMin = 95,
            congestion = emptyList(),
            routeTotalM = 250_000.0,
        )
    }

    private fun urgent(): Scenario = Scenario(
        name = "urgent",
        path = listOf(Pt(37.4979, 127.0276), Pt(37.5009, 127.0396), Pt(37.5045, 127.0490)), // 강남역 → 역삼 → 선릉 방향
        metersPerSecond = 8.0,
        occupants = listOf(driver(), Occupant("baby", OccupantType.INFANT, label = "아기")),
        timeOfDay = LocalTime.of(23, 10),
        drivenMin = 20,
        congestion = emptyList(),
        routeTotalM = 12_000.0,
    )

    private fun stopDemo(): Scenario = Scenario(
        name = "stop",
        path = listOf(Pt(37.01354, 127.14516), Pt(37.01300, 127.14530)), // 안성휴게소(부산방향)
        metersPerSecond = 0.0,
        occupants = listOf(driver(), Occupant("kid", OccupantType.CHILD, ageYears = 7, label = "7살 아이")),
        timeOfDay = null,
        drivenMin = 110,
        congestion = emptyList(),
        routeTotalM = 300_000.0,
        timeScale = 10.0,
    )

    // ------------------------------------------------------------ 경로 계산

    private fun pointAt(path: List<Pt>, distance: Double): Triple<Double, Double, Float> {
        var left = distance.coerceAtLeast(0.0)
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val seg = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
            val heading = Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon).toFloat()
            if (left <= seg) {
                val f = left / seg
                return Triple(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, heading)
            }
            left -= seg
        }
        val a = path[path.size - 2]
        val b = path.last()
        return Triple(b.lat, b.lon, Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon).toFloat())
    }

    /** 점을 경로에 투영했을 때 경로 시작점부터의 거리. 경로에서 2km 넘게 떨어지면 null */
    private fun alongPath(path: List<Pt>, p: Pt): Double? {
        var best: Pair<Double, Double>? = null // (거리, 경로상 위치)
        var acc = 0.0
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val seg = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
            // 평면 근사 투영
            val kx = Math.cos(Math.toRadians(a.lat))
            val ax = a.lon * kx; val ay = a.lat
            val bx = b.lon * kx; val by = b.lat
            val px = p.lon * kx; val py = p.lat
            val dx = bx - ax; val dy = by - ay
            val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            val qLat = ay + dy * t
            val qLon = (ax + dx * t) / kx
            val d = Geo.distanceM(p.lat, p.lon, qLat, qLon)
            if (best == null || d < best.first) best = d to (acc + seg * t)
            acc += seg
        }
        return best?.takeIf { it.first < 2_000.0 }?.second
    }
}
