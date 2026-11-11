package dev.shimpyo.engine

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

internal const val SEC = 1_000L
internal const val MIN = 60_000L
internal val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

/** 서울 시각 → epoch millis */
internal fun kst(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
    ZonedDateTime.of(y, mo, d, h, mi, s, 0, SEOUL).toInstant().toEpochMilli()

internal fun adult(id: String = "me") = Occupant(id, OccupantType.ADULT)
internal fun child(id: String = "kid", age: Int? = 5, label: String = "") = Occupant(id, OccupantType.CHILD, ageYears = age, label = label)
internal fun infant(id: String = "baby") = Occupant(id, OccupantType.INFANT)

internal fun stop(
    id: String,
    routeM: Double?,
    kind: StopKind = StopKind.HIGHWAY_REST_AREA,
    name: String = id,
    facilities: Facilities = Facilities(),
    parkable: Boolean = true,
    openNow: Boolean? = true,
    etaS: Double? = null,
    directionOk: Boolean? = true,
    straightM: Double? = null,
    detourS: Double = 0.0,
) = StopCandidate(
    id = id, name = name, kind = kind, lat = 37.0, lon = 127.0, facilities = facilities, parkable = parkable,
    openNow = openNow, routeDistanceM = routeM, etaS = etaS, directionOk = directionOk, detourS = detourS,
    straightDistanceM = straightM,
)

/** 정상 조향: 20초 주기 ±1.5도 + 잡음 */
internal fun normalSteer(t: Long, rnd: Random): Float = (1.5 * sin(2 * PI * t / 20.0) + rnd.nextGaussian() * 0.3).toFloat()

/** 흔들리는 조향 (Virtual Console 조향 흔들림 재생): 4초 주기 ±5도 + 잡음 */
internal fun wobbleSteer(t: Long, rnd: Random): Float = (5.0 * sin(2 * PI * t / 4.0) + rnd.nextGaussian() * 1.0).toFloat()

/** 경로 위에 고정된 정차 후보. 매 update마다 차 위치 기준 거리로 바꿔 넘긴다 */
internal class RouteStop(val posM: Double, val template: StopCandidate)

/** 경로 위에 고정된 정체 구간 */
internal class Jam(val startM: Double, val lengthM: Double, val durationS: Double)

/**
 * 고속도로 주행 시뮬레이터. 1초마다 차를 움직이고 표본 하나와 함께 update를 부른다.
 * 정체 안에서는 정체 길이 / 정체 시간의 속도로 움직인다.
 */
internal class HighwaySim(
    val engine: CoachEngine,
    startWall: Long,
    val routeLengthM: Double = 400_000.0,
    val stops: List<RouteStop> = emptyList(),
    val jams: List<Jam> = emptyList(),
    val cruiseKph: Double = 100.0,
    seed: Int = 7,
) {
    var now: Long = startWall
    var posM = 0.0
    val rnd = Random(seed)
    var steering: (Long, Random) -> Float = ::normalSteer
    var cabin = CabinState()
    var speedNoise = 0.6
    val outputs = ArrayList<Pair<Double, CoachOutput>>()
    val prompts = ArrayList<Triple<Double, Long, Prompt>>()
    private val seenPromptIds = HashSet<String>()
    var last: CoachOutput? = null

    fun speedAt(pos: Double): Double {
        for (j in jams) if (pos >= j.startM && pos < j.startM + j.lengthM) return j.lengthM / j.durationS * 3.6
        return cruiseKph
    }

    fun routeState(): RouteState {
        val remain = routeLengthM - posM
        var jamLen = 0.0
        var jamTime = 0.0
        val segs = ArrayList<CongestionSegment>()
        for (j in jams) {
            val end = j.startM + j.lengthM
            if (end <= posM) continue
            val startRel = j.startM - posM
            segs += CongestionSegment(startDistanceM = startRel, lengthM = j.lengthM, durationS = j.durationS, roadName = "경부고속도로")
            val coveredLen = if (startRel < 0) end - posM else j.lengthM
            jamLen += coveredLen
            jamTime += j.durationS * coveredLen / j.lengthM
        }
        val remainTime = (remain - jamLen) / (cruiseKph / 3.6) + jamTime
        return RouteState(guiding = true, remainDistanceM = remain, remainTimeS = remainTime, congestion = segs, nextManeuverDistanceM = 20_000.0)
    }

    fun candidates(): List<StopCandidate> = stops.filter { it.posM > posM }.map { it.template.copy(routeDistanceM = it.posM - posM) }

    /** 1초 주행 */
    fun step(intent: VoiceIntent? = null, gear: Gear = Gear.DRIVE, door: Boolean = false): CoachOutput {
        val moving = gear == Gear.DRIVE
        val v = if (moving) speedAt(posM) + rnd.nextGaussian() * speedNoise else 0.0
        if (moving) posM += maxOf(0.0, v) / 3.6
        now += SEC
        val sample = DrivingSample(
            t = now, speedKph = v.toFloat(), steeringDeg = if (moving) steering(now / 1000, rnd) else 0f,
            brake = false, turnSignal = TurnSignal.NONE, cruiseActive = false,
        )
        val out = engine.update(
            CoachInput(
                now = now, gear = gear, speedKph = v.toFloat(), samples = listOf(sample), doorOpened = door,
                cabin = cabin, route = routeState(), candidates = candidates(), intent = intent,
            ),
        )
        last = out
        out.prompt?.let { p -> if (seenPromptIds.add(p.id)) prompts += Triple(posM, now, p) }
        return out
    }

    fun driveSeconds(n: Int, onStep: ((CoachOutput) -> Unit)? = null): CoachOutput {
        var o: CoachOutput? = null
        repeat(n) { o = step(); onStep?.invoke(o!!) }
        return o!!
    }

    fun driveUntilPos(targetM: Double, maxSteps: Int = 200_000, onStep: ((CoachOutput) -> Unit)? = null): CoachOutput {
        var o = last ?: step()
        var n = 0
        while (posM < targetM && n++ < maxSteps) { o = step(); onStep?.invoke(o) }
        return o
    }

    fun driveUntilWall(targetWall: Long, onStep: ((CoachOutput) -> Unit)? = null): CoachOutput {
        var o = last ?: step()
        while (now < targetWall) { o = step(); onStep?.invoke(o) }
        return o
    }

    /** 기어 P로 n초 (첫 doorAtS초에 문 열림) */
    fun parkSeconds(n: Int, doorAtS: Int = -1): CoachOutput {
        var o: CoachOutput? = null
        for (i in 0 until n) o = step(gear = Gear.PARK, door = i == doorAtS)
        return o!!
    }
}

/** 테스트 편의: 엔진 하나를 만들고 시각 now에 update */
internal fun CoachEngine.at(now: Long, gear: Gear = Gear.DRIVE, speed: Float? = 80f, candidates: List<StopCandidate> = emptyList(),
                            route: RouteState? = null, intent: VoiceIntent? = null, cabin: CabinState = CabinState(),
                            door: Boolean = false, samples: List<DrivingSample> = emptyList()): CoachOutput =
    update(CoachInput(now = now, gear = gear, speedKph = speed, samples = samples, doorOpened = door, cabin = cabin,
        route = route, candidates = candidates, intent = intent))
