package dev.shimpyo.coach

import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.StopCandidate
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * 시연 시나리오 (불변 정의). 에뮬레이터는 실제로 달리지 않고 교통 정보도 비어 있을 수 있어서, Virtual Console로 못 바꾸는 것
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
class DemoScenario internal constructor(
    val name: String,
    val path: List<DemoRun.Pt>,
    /** 실제 1초에 가상으로 가는 거리 (m) */
    val metersPerSecond: Double,
    val occupants: List<Occupant>,
    /** 시연 시각 (하루 중 시각). null이면 실제 시각 */
    val timeOfDay: LocalTime?,
    /** 시작할 때 이미 달린 시간 (분) */
    val drivenMin: Int,
    /** 시작할 때 모든 탑승자의 마지막 화장실이 몇 분 전인지 */
    val lastRestroomMin: Int = drivenMin,
    /** 경로 위 정체 (경로 시작점 기준) */
    val congestion: List<Jam>,
    val routeTotalM: Double,
    /** 시연 시각이 실제보다 몇 배 빨리 흐르는지 */
    val timeScale: Double = 1.0,
) {
    /** 경로 위 정체 한 구간: 경로 시작점 기준 시작 거리(m), 길이(m), 지나는 데 걸리는 시간(초) */
    data class Jam(val startM: Double, val lengthM: Double, val durationS: Double)

    /**
     * 정체 밖에서 달리는 가상 속도 (m/s) = 실제 1초에 가는 거리 ÷ 시각 배속. 귀성길 200 ÷ 7.2 = 27.8(시속 100km), 오후 졸음 250 ÷ 10 = 25(시속 90km).
     * 서 있는 시나리오(stop)는 달리는 속도가 없어 [PARKED_CRUISE_MPS]로 둔다 (계획 화면의 도착 시간에만 쓰인다).
     */
    val cruiseMps: Double
        get() = (metersPerSecond / timeScale).takeIf { it >= MIN_CRUISE_MPS } ?: PARKED_CRUISE_MPS

    companion object {
        /** 화면 시연 패널·adb `--es demo`가 쓰는 이름 */
        val NAMES: List<String> = listOf("holiday", "fatigue", "urgent", "stop")

        /** 이보다 느리면 달리는 시나리오가 아니다 (엔진이 받는 자유 속도의 아래 끝, 시속 10km) */
        private const val MIN_CRUISE_MPS = 10 / 3.6
        /** 서 있는 시나리오의 경로 시간에 쓰는 속도: 고속도로 시속 90km */
        private const val PARKED_CRUISE_MPS = 25.0

        /** 이름으로 시나리오를 만든다. 모르는 이름이나 null이면 null (= 시연 끄기) */
        fun byName(name: String?): DemoScenario? = when (name) {
            "holiday" -> DemoScenarios.holiday()
            "fatigue" -> DemoScenarios.fatigue()
            "urgent" -> DemoScenarios.urgent()
            "stop" -> DemoScenarios.stop()
            else -> null
        }
    }
}

/**
 * 시작된 시연 한 번. 불변 값이다: 시연을 시작하면 새 값을 만들고, 코치는 세계(CoachWorld.Demo)째 바꿔 끼운다.
 * 예전 DemoDirector는 시나리오·시작 시각·시계 차이를 세 필드로 따로 바꿔서 다른 스레드가 섞어 읽을 수 있었다 (구조 리뷰 Q2).
 *
 * 시각 (docs/design/coach-layer/world.md '시계' 표)
 * - 시연 시각 [now] = 시작한 실제 시각 + 시계 차이 + 시작 뒤 경과(ElapsedClock) × 배속. 엔진과 시연 세계 상태가 쓴다.
 * - 진행 거리 = 시작 뒤 경과(ElapsedClock) × 속도. 벽시계(NTP 보정)가 바뀌어도 시연 차가 순간 이동하지 않는다.
 *
 * 경로 위치 캐시(후보 id → 경로 위 위치)는 경로가 바뀌지 않으니 결과를 바꾸지 않는 메모다. 시연마다 따로 둔다.
 */
class DemoRun private constructor(
    val scenario: DemoScenario,
    /** 시작한 실제 시각 (epoch ms) */
    val startedAtWall: Long,
    /** 시작한 경과 시각 (ElapsedClock) */
    val startedAtElapsed: Long,
    /** 시작 순간 시연 시각 - 실제 시각 */
    val clockOffsetMs: Long,
    private val elapsed: ElapsedClock,
) : CoachClock {
    data class Pt(val lat: Double, val lon: Double)

    private val along = ConcurrentHashMap<String, Double>()

    val name: String get() = scenario.name

    /** 시작 순간의 시연 시각 */
    val startedAt: Long get() = startedAtWall + clockOffsetMs

    override fun now(): Long = startedAt + (sinceStartMs() * scenario.timeScale).toLong()

    /** 시작 뒤 실제로 흐른 시간 (ms) */
    private fun sinceStartMs(): Long = (elapsed.elapsedMs() - startedAtElapsed).coerceAtLeast(0L)

    /** 시연 경로를 따라 간 거리 (m) */
    fun travelledM(): Double = sinceStartMs() / 1000.0 * scenario.metersPerSecond

    /**
     * 시연 여정의 기준점 (시작 순간 기준): 이미 달린 시간만큼 앞선 출발, 모두 같은 마지막 화장실, 휴식 리듬에 채울 연속 주행.
     * 예전에는 '이미 달린 시간'이 출발 시각에만 들어가 피로 게이지가 0분부터 셌다 (2026-11-11)
     */
    val anchor: TripAnchor
        get() {
            val restroomAt = startedAt - scenario.lastRestroomMin * 60_000L
            return TripAnchor(
                tripStart = startedAt - scenario.drivenMin * 60_000L,
                lastRestroomAt = scenario.occupants.associate { it.id to restroomAt },
                restroomFallbackAt = restroomAt,
                continuousDrivingMin = scenario.drivenMin.toDouble(),
            )
        }

    /** 가상 위치와 진행 방향 */
    fun position(): Fix = DemoPath.pointAt(scenario.path, travelledM())

    /** 점을 시연 경로에 투영한 경로 위 위치 (m). 경로에서 2km 넘게 떨어지면 null */
    fun alongM(p: Pt): Double? = DemoPath.along(scenario.path, p)

    /** 경로 상태와 정체를 시나리오 값으로 바꾼다 */
    fun apply(input: CoachInput): CoachInput {
        val s = scenario
        val travelled = travelledM()
        val congestion = s.congestion.mapNotNull { jam ->
            val fromCar = jam.startM - travelled
            if (fromCar + jam.lengthM < 0) null
            else CongestionSegment(startDistanceM = fromCar, lengthM = jam.lengthM, durationS = jam.durationS, type = "HEAVY", roadName = "경부고속도로", startName = "청주IC", endName = "남이JC")
        }
        val remain = (s.routeTotalM - travelled).coerceAtLeast(0.0)
        // 시연 경로는 시나리오가 정한다. 실제 내비에 경로가 켜져 있어도 그 값(다음 회전 거리 등)을 섞지 않는다:
        // 에뮬레이터 내비는 판교에 서 있어 '다음 회전 수백 m'가 계속 와서 '회전 500m 안에는 묻지 않기'에 걸려 질문이 막혔다
        // (2026-11-11, DemoScenarioTest가 지킨다)
        val route = RouteState(
            guiding = true,
            remainDistanceM = remain,
            remainTimeS = remainTimeS(remain, travelled),
            congestion = congestion,
            // 시연 후보는 직선 60km 안의 시설이라 경로 거리 55km까지만 다 들어 있다. 그 뒤를 목적지까지의 공백으로 보지 않게
            candidatesUntilM = DEMO_CANDIDATES_UNTIL_M,
        )
        return input.copy(route = route)
    }

    /**
     * 목적지까지 남은 시간 = 정체 밖 거리 ÷ 가상 속도 + 남은 정체를 지나는 시간. 엔진은 남은 거리·시간에서 정체 몫을 빼
     * 정체 밖 자유 속도를 구하므로(RouteContext.prepare), 이렇게 줘야 엔진의 도착 시간이 시나리오 속도와 맞는다.
     * 예전에는 남은 거리 ÷ 25m/s였다: 귀성길에서 정체 1시간을 빼고 나면 자유 속도가 시속 119km가 되어 화면이 "옥산휴게소 14km · 7분"을
     * 보였다 (2026-11-12 기기 확인, UI 감사 U2). 정체는 엔진과 같게 경로 안([0, 남은 거리])에 든 부분만 센다.
     */
    private fun remainTimeS(remainM: Double, travelledM: Double): Double {
        var jamM = 0.0
        var jamS = 0.0
        for (jam in scenario.congestion) {
            val from = (jam.startM - travelledM).coerceAtLeast(0.0)
            val to = (jam.startM + jam.lengthM - travelledM).coerceAtMost(remainM)
            if (to <= from) continue
            jamM += to - from
            jamS += jam.durationS * (to - from) / jam.lengthM
        }
        return (remainM - jamM) / scenario.cruiseMps + jamS
    }

    /** 후보의 경로 거리를 가상 경로를 따라 다시 계산한다 */
    fun candidates(list: List<StopCandidate>): List<StopCandidate> {
        val travelled = travelledM()
        return list.mapNotNull { c ->
            // 시나리오 경로 위 위치는 후보마다 변하지 않으니 한 번만 계산한다 (매초 후보마다 경로 전체를 훑었다)
            val at = along.getOrPut(c.id) { alongM(Pt(c.lat, c.lon)) ?: NOT_ON_PATH }.takeIf { it != NOT_ON_PATH } ?: return@mapNotNull null
            val ahead = at - travelled
            // etaS는 넣지 않는다: 엔진이 시연 정체 구간으로 도착 시간을 계산한다 (StopCandidate.etaS 주석)
            if (ahead < -200) null else c.copy(routeDistanceM = ahead.coerceAtLeast(0.0), etaS = null, directionOk = true)
        }
    }

    companion object {
        /** 시연 시각을 정하는 시간대 (엔진 CoachConfig.zoneId 기본값과 같다) */
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        /** alongCache에서 '경로 밖'을 뜻하는 값 */
        private const val NOT_ON_PATH = Double.NEGATIVE_INFINITY
        /** 시연 후보가 빠짐없이 들어 있는 경로 거리 (서비스가 직선 60km 안을 찾는다. 경로 거리 ≥ 직선거리) */
        private const val DEMO_CANDIDATES_UNTIL_M = 55_000.0

        /**
         * 시연 시작. 모르는 이름이나 null이면 null (= 시연 끄기).
         * 시연 날짜는 주입한 실제 시각 [wallNow]의 [zone] 날짜다 (예전에는 시스템 시계 LocalDate.now라 테스트가 날짜에 따라 달랐다).
         */
        fun start(
            name: String?,
            wallNow: Long,
            elapsed: ElapsedClock,
            elapsedNow: Long = elapsed.elapsedMs(),
            zone: ZoneId = ZONE,
        ): DemoRun? {
            val s = DemoScenario.byName(name) ?: return null
            val offset = s.timeOfDay?.let { tod ->
                val today = Instant.ofEpochMilli(wallNow).atZone(zone).toLocalDate()
                today.atTime(tod).atZone(zone).toInstant().toEpochMilli() - wallNow
            } ?: 0L
            return DemoRun(s, startedAtWall = wallNow, startedAtElapsed = elapsedNow, clockOffsetMs = offset, elapsed = elapsed)
        }
    }
}

/** 시나리오 정의. 경로는 앱에 든 공공데이터의 시설 좌표를 잇는다 */
private object DemoScenarios {
    private fun driver() = Occupant("driver", OccupantType.ADULT, label = "운전자")

    /**
     * 경부선 남행. 앱에 든 공공데이터의 부산방향 휴게소·졸음쉼터 좌표를 잇는 단순화 경로라서,
     * 시연 후보는 실제 데이터에서 나온다(반대 차로 시설은 차로 방위로 걸러진다)
     */
    private val gyeongbuSouth = listOf(
        DemoRun.Pt(37.235125, 127.104595), // 기흥휴게소
        DemoRun.Pt(37.156708, 127.087165), // 오산졸음쉼터
        DemoRun.Pt(37.096192, 127.120738), // 남사졸음쉼터
        DemoRun.Pt(37.013407, 127.144800), // 안성휴게소(부산방향)
        DemoRun.Pt(36.954311, 127.183007), // 연곡졸음쉼터
        DemoRun.Pt(36.914432, 127.188465), // 입장졸음쉼터
        DemoRun.Pt(36.855650, 127.180929), // 망향휴게소(부산방향)
        DemoRun.Pt(36.730226, 127.263879), // 천안호두휴게소(부산방향)
        DemoRun.Pt(36.657743, 127.369817), // 옥산휴게소(부산방향)
        DemoRun.Pt(36.486810, 127.429261), // 죽암휴게소(부산방향)
        DemoRun.Pt(36.425354, 127.416697), // 신탄진졸음쉼터
        DemoRun.Pt(36.328096, 127.540893), // 옥천졸음쉼터(부산방향)
        DemoRun.Pt(36.296863, 127.595223), // 옥천휴게소(부산방향)
        DemoRun.Pt(36.279148, 127.672231), // 금강휴게소
    )
    private val oksan = DemoRun.Pt(36.657743, 127.369817)
    private val jukam = DemoRun.Pt(36.486810, 127.429261)

    fun holiday(): DemoScenario {
        // 망향휴게소와 천안호두휴게소 사이(천안호두 약 6km 전)에서 출발
        val start = DemoRun.Pt(36.7779, 127.2324)
        val path = listOf(start) + gyeongbuSouth.dropWhile { it.lat > 36.75 }
        val oksanAt = DemoPath.along(path, oksan) ?: 18_400.0
        // 시연 경로는 휴게소 좌표를 직선으로 이어 실제 도로(옥산→죽암 약 21km)보다 짧다(약 19.7km).
        // 정체를 20km로 두면 죽암휴게소가 정체 안에 들어가 '정체 안에 화장실 없음' 조건이 깨지므로, 죽암 300m 앞에서 끝낸다
        val jamStart = oksanAt + 1_000.0
        val jukamAt = DemoPath.along(path, jukam) ?: (jamStart + 20_300.0)
        val jamLength = minOf(20_000.0, jukamAt - 300.0 - jamStart)
        return DemoScenario(
            name = "holiday",
            path = path,
            // 실제 1초에 가상 200m. 시각도 7.2배로 흘러 가상 시속 100km와 맞는다 → 옥산휴게소 4km 전까지 약 70초
            metersPerSecond = 200.0,
            timeScale = 7.2,
            occupants = listOf(driver(), Occupant("kid", OccupantType.CHILD, ageYears = 7, label = "7살 아이")),
            timeOfDay = LocalTime.of(14, 0),
            // 46분 전 안성휴게소에서 쉬고 출발했다 → 질문 지점에서 아이의 화장실 게이지 약 50분
            drivenMin = 46,
            congestion = listOf(DemoScenario.Jam(jamStart, jamLength, 60 * 60.0)),
            routeTotalM = 310_000.0,
        )
    }

    fun fatigue(): DemoScenario = DemoScenario(
        name = "fatigue",
        path = gyeongbuSouth.dropWhile { it.lat > 37.02 },
        // 10배속: 실제 1초에 가상 250m(시속 90km)
        metersPerSecond = 250.0,
        timeScale = 10.0,
        occupants = listOf(driver()),
        timeOfDay = LocalTime.of(15, 0),
        drivenMin = 95,
        congestion = emptyList(),
        routeTotalM = 250_000.0,
    )

    fun urgent(): DemoScenario = DemoScenario(
        name = "urgent",
        path = listOf(DemoRun.Pt(37.4979, 127.0276), DemoRun.Pt(37.5009, 127.0396), DemoRun.Pt(37.5045, 127.0490)), // 강남역 → 역삼 → 선릉 방향
        metersPerSecond = 8.0,
        occupants = listOf(driver(), Occupant("baby", OccupantType.INFANT, label = "아기")),
        timeOfDay = LocalTime.of(23, 10),
        drivenMin = 20,
        congestion = emptyList(),
        routeTotalM = 12_000.0,
    )

    fun stop(): DemoScenario = DemoScenario(
        name = "stop",
        // 안성휴게소(부산방향)에 서 있다(0m/s). 경로는 그 앞 경부선 남행이라 계획 화면에 앞쪽 휴게소가 나온다
        path = listOf(DemoRun.Pt(37.01354, 127.14516)) + gyeongbuSouth.dropWhile { it.lat > 37.0 },
        metersPerSecond = 0.0,
        occupants = listOf(driver(), Occupant("kid", OccupantType.CHILD, ageYears = 7, label = "7살 아이")),
        timeOfDay = null,
        drivenMin = 110,
        congestion = emptyList(),
        routeTotalM = 300_000.0,
        timeScale = 10.0,
    )
}

/** 시연 경로 계산 (평면 근사) */
private object DemoPath {
    fun pointAt(path: List<DemoRun.Pt>, distance: Double): Fix {
        var left = distance.coerceAtLeast(0.0)
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val seg = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
            val heading = Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon).toFloat()
            if (left <= seg) {
                val f = left / seg
                return Fix(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, heading)
            }
            left -= seg
        }
        val a = path[path.size - 2]
        val b = path.last()
        return Fix(b.lat, b.lon, Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon).toFloat())
    }

    /** 점을 경로에 투영했을 때 경로 시작점부터의 거리. 경로에서 2km 넘게 떨어지면 null */
    fun along(path: List<DemoRun.Pt>, p: DemoRun.Pt): Double? {
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
