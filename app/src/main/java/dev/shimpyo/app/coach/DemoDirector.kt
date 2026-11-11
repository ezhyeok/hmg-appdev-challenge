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
 * - holiday: 경부선 남행, 7살 아이 동승(46분 전 화장실), 옥산휴게소 1km 뒤부터 20km·60분 정체.
 *   옥산휴게소 다음 화장실은 죽암휴게소(약 21km, 정체 속 약 1시간) → 옥산휴게소 4km 전쯤 '마지막 화장실' 질문
 * - fatigue: 오후 3시, 연속 주행 95분, 안성휴게소를 지나 졸음쉼터·망향휴게소 앞 → 피로 게이지와 휴식 제안
 * - urgent: 밤 11시 강남역 부근, 영아 동승 → 급해 모드
 * - stop: 시간을 10배로 돌려 휴게소 정차(15분 이상)와 게이지 회복을 짧게 보여 준다
 *
 * 시간 배속: 엔진은 입력 시각으로만 판단하므로, 시연 시각을 실제보다 빠르게 흐르게 하면 15분 휴식이나
 * 25분 뒤의 피로 단계를 몇 분 안에 보여 줄 수 있다. 1초 간격 호출이 배속 후에도 2분을 넘지 않게 한다.
 */
class DemoDirector(private val realClock: () -> Long = System::currentTimeMillis) {
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
        /** 시작할 때 모든 탑승자의 마지막 화장실이 몇 분 전인지 */
        val lastRestroomMin: Int = drivenMin,
        /** 경로 위 정체: 경로 시작점 기준 시작 거리, 길이, 소요 시간 */
        val congestion: List<Triple<Double, Double, Double>>,
        val routeTotalM: Double,
        /** 시연 시각이 실제보다 몇 배 빨리 흐르는지 */
        val timeScale: Double = 1.0,
    ) {
        /** 후보 id → 시나리오 경로 위 위치(m). 시나리오마다 따로 둔다(바꾼 직후 옛 경로 값이 섞이지 않게) */
        val along = java.util.concurrent.ConcurrentHashMap<String, Double>()
    }

    @Volatile private var scenario: Scenario? = null
    @Volatile private var startedAtReal = 0L
    @Volatile private var clockOffsetMs = 0L

    val name: String? get() = scenario?.name
    val active: Boolean get() = scenario != null
    /** 시연 중에는 requestTrip 대신 가상 경로 거리를 쓴다 */
    val oracleEnabled: Boolean get() = scenario == null

    fun now(): Long {
        val real = realClock()
        val s = scenario ?: return real
        return startedAtReal + clockOffsetMs + ((real - startedAtReal) * s.timeScale).toLong()
    }

    /** 시연 시작 조건. 서비스가 새 엔진의 프로필로 쓴다 */
    data class Start(val occupants: List<Occupant>, val drivenMin: Int, val lastRestroomMin: Int)

    /** 시나리오 시작. 탑승자, 이미 달린 시간, 마지막 화장실을 돌려준다. 시연을 끄면 null */
    fun start(name: String?, realNow: Long = realClock()): Start? {
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
        return s?.let { Start(it.occupants, it.drivenMin, it.lastRestroomMin) }
    }

    /** 시연 경로에서 점까지의 거리와 현재 진행 거리 (테스트용). 경로 밖이면 null */
    internal fun alongForTest(p: Pt): Double? = scenario?.let { alongPath(it.path, p) }
    internal fun travelledForTest(): Double? = scenario?.let { progressM(it) }
    internal fun metersPerSecondForTest(): Double? = scenario?.metersPerSecond

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
            if (fromCar + len < 0) null else CongestionSegment(startDistanceM = fromCar, lengthM = len, durationS = dur, type = "HEAVY", roadName = "경부고속도로", startName = "청주IC", endName = "남이JC")
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
            // 시나리오 경로 위 위치는 후보마다 변하지 않으니 한 번만 계산한다 (매초 후보마다 경로 전체를 훑었다)
            val along = s.along.getOrPut(c.id) { alongPath(s.path, Pt(c.lat, c.lon)) ?: NOT_ON_PATH }.takeIf { it != NOT_ON_PATH } ?: return@mapNotNull null
            val ahead = along - travelled
            // etaS는 넣지 않는다: 엔진이 시연 정체 구간으로 도착 시간을 계산한다 (StopCandidate.etaS 주석)
            if (ahead < -200) null else c.copy(routeDistanceM = ahead.coerceAtLeast(0.0), etaS = null, directionOk = true)
        }
    }

    private fun progressM(s: Scenario): Double = (realClock() - startedAtReal) / 1000.0 * s.metersPerSecond

    // ------------------------------------------------------------ 시나리오

    private fun driver() = Occupant("driver", OccupantType.ADULT, label = "운전자")

    /**
     * 경부선 남행. 앱에 든 공공데이터의 부산방향 휴게소·졸음쉼터 좌표를 잇는 단순화 경로라서,
     * 시연 후보는 실제 데이터에서 나온다(반대 차로 시설은 차로 방위로 걸러진다)
     */
    private val gyeongbuSouth = listOf(
        Pt(37.235125, 127.104595), // 기흥휴게소
        Pt(37.156708, 127.087165), // 오산졸음쉼터
        Pt(37.096192, 127.120738), // 남사졸음쉼터
        Pt(37.013407, 127.144800), // 안성휴게소(부산방향)
        Pt(36.954311, 127.183007), // 연곡졸음쉼터
        Pt(36.914432, 127.188465), // 입장졸음쉼터
        Pt(36.855650, 127.180929), // 망향휴게소(부산방향)
        Pt(36.730226, 127.263879), // 천안호두휴게소(부산방향)
        Pt(36.657743, 127.369817), // 옥산휴게소(부산방향)
        Pt(36.486810, 127.429261), // 죽암휴게소(부산방향)
        Pt(36.425354, 127.416697), // 신탄진졸음쉼터
        Pt(36.328096, 127.540893), // 옥천졸음쉼터(부산방향)
        Pt(36.296863, 127.595223), // 옥천휴게소(부산방향)
        Pt(36.279148, 127.672231), // 금강휴게소
    )
    private val oksan = Pt(36.657743, 127.369817)
    private val jukam = Pt(36.486810, 127.429261)

    private fun holiday(): Scenario {
        // 망향휴게소와 천안호두휴게소 사이(천안호두 약 6km 전)에서 출발
        val start = Pt(36.7779, 127.2324)
        val path = listOf(start) + gyeongbuSouth.dropWhile { it.lat > 36.75 }
        val oksanAt = alongPath(path, oksan) ?: 18_400.0
        // 시연 경로는 휴게소 좌표를 직선으로 이어 실제 도로(옥산→죽암 약 21km)보다 짧다(약 19.7km).
        // 정체를 20km로 두면 죽암휴게소가 정체 안에 들어가 '정체 안에 화장실 없음' 조건이 깨지므로, 죽암 300m 앞에서 끝낸다
        val jamStart = oksanAt + 1_000.0
        val jukamAt = alongPath(path, jukam) ?: (jamStart + 20_300.0)
        val jamLength = minOf(20_000.0, jukamAt - 300.0 - jamStart)
        return Scenario(
            name = "holiday",
            path = path,
            // 실제 1초에 가상 200m. 시각도 7.2배로 흘러 가상 시속 100km와 맞는다 → 옥산휴게소 4km 전까지 약 70초
            metersPerSecond = 200.0,
            timeScale = 7.2,
            occupants = listOf(driver(), Occupant("kid", OccupantType.CHILD, ageYears = 7, label = "7살 아이")),
            timeOfDay = LocalTime.of(14, 0),
            // 46분 전 안성휴게소에서 쉬고 출발했다 → 질문 지점에서 아이의 화장실 게이지 약 50분
            drivenMin = 46,
            congestion = listOf(Triple(jamStart, jamLength, 60 * 60.0)),
            routeTotalM = 310_000.0,
        )
    }

    private fun fatigue(): Scenario {
        val start = gyeongbuSouth.dropWhile { it.lat > 37.02 }
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

    private companion object {
        /** alongCache에서 '경로 밖'을 뜻하는 값 */
        const val NOT_ON_PATH = Double.NEGATIVE_INFINITY
    }

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
