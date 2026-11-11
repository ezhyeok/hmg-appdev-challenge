package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.WorldClock
import dev.shimpyo.coach.fakes.seededEngines
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CoachOutput
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.PersonalMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 정차 판정이 두 벌이다: 엔진의 StopTracker(화장실 시계·휴식 리듬을 돌린다)와 코치의 StopDetector(휴식 기록을 남긴다).
 * 규칙이 어긋나면 게이지는 다녀온 것으로 찼는데 기록에는 그 정차가 없다 (구조 리뷰 §2.3e·B9, 서비스 닫힘 검증 N3).
 * 엔진이 정차 사건을 내보내기 전까지는(B9) 이 테스트가 둘을 묶는다: 같은 신호 순서를 둘에 넣고, 엔진이 화장실 정차·휴식으로
 * 본 것과 감지기가 낸 정차가 같은 말을 하는지 본다. 엔진의 정차 규칙을 바꾸면 여기서 먼저 깨진다 → StopDetector에 같은 규칙을 넣는다.
 */
class StopRulesCrossCheckTest {
    private val min = 60_000L
    /** 2026-11-12 10:00 KST */
    private val start = 1_762_909_200_000L

    /** 엔진과 감지기에 같은 신호를 넣는 한 벌. 여정: 100분 전 출발, 마지막 화장실 100분 전, 연속 주행 80분 */
    private inner class Both(restored: ParkState? = null, lastSeen: Long? = null) {
        private val clock = WorldClock(start)
        private val world = CoachWorld.Real(clock)
        private val anchor = TripAnchor(start - 100 * min, mapOf("driver" to start - 100 * min), start - 100 * min, continuousDrivingMin = 80.0)
        val engine: CoachEngine = world.newEngine(seededEngines(1), FakeSettings(listOf(DRIVER)), PersonalMemory(), anchor, restored)
        val detector = StopDetector()
        val stops = mutableListOf<ParkedStop>()
        var out: CoachOutput? = null
        private var doorHeld = false

        init {
            if (restored != null) detector.restore(restored, start, lastSeen ?: start)
        }

        /** 신호 한 틱. 코어의 틱과 같은 순서다: 감지기 → (되돌린 정차의 판정을 기다리지 않으면) 엔진 */
        fun feed(gear: Gear, kph: Float?, door: Boolean = false, stepMs: Long = 1_000) {
            clock.advance(stepMs)
            val t = clock.now()
            detector.onTick(t, gear, kph, door)?.let { stops += it }
            if (detector.restorePending) {
                doorHeld = doorHeld || door
                return
            }
            detector.takeRestoredEnd()?.let { world.endPark(engine, it) }
            out = engine.update(CoachInput(now = t, gear = gear, speedKph = kph, doorOpened = door || doorHeld))
            doorHeld = false
        }

        fun park(ms: Long, doorAtMs: Long? = null, kph: Float? = 0f, gear: Gear = Gear.PARK) {
            var at = 0L
            while (at < ms) {
                at += 1_000
                feed(gear, kph, door = doorAtMs != null && at == doorAtMs)
            }
        }

        fun drive(ms: Long, kph: Float, gear: Gear = Gear.DRIVE) = repeat((ms / 1_000).toInt()) { feed(gear, kph) }

        fun clockBack(ms: Long) = clock.jumpWall(-ms)

        /** 떠나서 달린다: 감지기가 다시 대기 창을 닫고 정차를 확정한다 */
        fun driveOff() = drive(4 * min, 80f)

        /** 엔진이 화장실 정차로 봤다: 화장실 시계가 돌아가 게이지가 찼다 (정차가 없으면 50분에서 줄어들 뿐이다) */
        val engineSawRestroomStop: Boolean get() = out!!.restroom.minutes!! > 100
        /** 엔진이 휴식으로 봤다: 휴식 리듬이 돌아가 피로 게이지가 찼다 (휴식이 없으면 40분에서 줄어들 뿐이다) */
        val engineSawRest: Boolean get() = out!!.fatigue.minutes!! > 80
        val detectorSawRestroomStop: Boolean get() = stops.any { it.doorOpened }
        val detectorSawRest: Boolean get() = stops.any { it.end - it.start >= 15 * min }
    }

    private fun check(name: String, restroom: Boolean, rest: Boolean, b: Both) {
        val what = "$name: 엔진(화장실 ${b.out!!.restroom.minutes}분, 피로 ${b.out!!.fatigue.minutes}분) / 감지기 ${b.stops.map { "${(it.end - it.start) / 1000}s door=${it.doorOpened}" }}"
        assertEquals("화장실 정차를 다르게 본다. $what", b.engineSawRestroomStop, b.detectorSawRestroomStop)
        assertEquals("휴식을 다르게 본다. $what", b.engineSawRest, b.detectorSawRest)
        assertEquals("화장실 정차 판정이 기대와 다르다. $what", restroom, b.detectorSawRestroomStop)
        assertEquals("휴식 판정이 기대와 다르다. $what", rest, b.detectorSawRest)
    }

    @Test
    fun theBaselineWithoutAnyStopShowsNoResetSoTheThresholdsMeanSomething() {
        val b = Both()
        b.drive(25 * min, 100f)
        assertTrue("정차 없이 달리면 화장실 게이지는 50분 아래 (${b.out!!.restroom.minutes})", b.out!!.restroom.minutes!! < 60)
        assertTrue("피로 게이지는 40분 아래 (${b.out!!.fatigue.minutes})", b.out!!.fatigue.minutes!! < 45)
        check("정차 없음", restroom = false, rest = false, b)
    }

    @Test
    fun plainStopsAndTheirBoundaries() {
        Both().let { b -> b.park(4 * min, doorAtMs = min); b.driveOff(); check("P 4분 + 문", restroom = true, rest = false, b) }
        Both().let { b -> b.park(3 * min - 1_000, doorAtMs = min); b.driveOff(); check("P 2분 59초 + 문", restroom = false, rest = false, b) }
        Both().let { b -> b.park(3 * min, doorAtMs = min); b.driveOff(); check("P 정확히 3분 + 문", restroom = true, rest = false, b) }
        Both().let { b -> b.park(4 * min); b.driveOff(); check("P 4분, 문 없음", restroom = false, rest = false, b) }
        Both().let { b -> b.park(16 * min); b.driveOff(); check("P 16분", restroom = false, rest = true, b) }
        Both().let { b -> b.park(15 * min - 1_000); b.driveOff(); check("P 14분 59초", restroom = false, rest = false, b) }
        Both().let { b -> b.park(16 * min, doorAtMs = 10 * min); b.driveOff(); check("P 16분 + 문", restroom = true, rest = true, b) }
    }

    @Test
    fun doorTiming() {
        // 세우는 틱의 문, 떠나는 틱의 문은 그 정차의 문이다. 달리는 중이나 옮겨 대는 사이의 문은 아니다
        Both().let { b -> b.feed(Gear.PARK, 0f, door = true); b.park(4 * min); b.driveOff(); check("세우는 틱의 문", restroom = true, rest = false, b) }
        Both().let { b -> b.park(4 * min); b.feed(Gear.DRIVE, 40f, door = true); b.driveOff(); check("떠나는 틱의 문", restroom = true, rest = false, b) }
        Both().let { b -> b.feed(Gear.DRIVE, 30f, door = true); b.park(4 * min); b.driveOff(); check("세우기 전의 문", restroom = false, rest = false, b) }
        Both().let { b ->
            b.park(4 * min)
            b.drive(20_000, 10f)
            b.feed(Gear.DRIVE, 10f, door = true)
            b.drive(20_000, 10f)
            b.park(min)
            b.driveOff()
            check("옮겨 대는 사이의 문", restroom = false, rest = false, b)
        }
    }

    @Test
    fun flappingAndUnknownGear() {
        // Car 서비스가 잠깐 끊겨 기어를 모른다: 움직이지 않으면 정차는 이어진다. 차속이 3km/h를 넘으면 떠난 것이다
        Both().let { b ->
            b.park(2 * min, doorAtMs = min)
            b.park(min, gear = Gear.UNKNOWN, kph = null)
            b.park(2 * min)
            b.driveOff()
            check("P 2분 → 기어 모름 1분 → P 2분", restroom = true, rest = false, b)
        }
        Both().let { b ->
            b.park(2 * min, doorAtMs = min)
            b.park(20_000, gear = Gear.UNKNOWN, kph = 2f)
            b.park(min)
            b.driveOff()
            check("기어 모름 + 시속 2km (잡음)", restroom = true, rest = false, b)
        }
        Both().let { b ->
            b.park(2 * min, doorAtMs = min)
            b.drive(4 * min, 50f, gear = Gear.UNKNOWN)
            b.park(2 * min)
            b.driveOff()
            check("기어 모름 + 시속 50km로 4분: 정차 둘", restroom = false, rest = false, b)
        }
        Both().let { b ->
            repeat(120) { i -> b.feed(if (i % 2 == 0) Gear.PARK else Gear.UNKNOWN, null, door = i == 30) }
            b.park(2 * min)
            b.driveOff()
            check("P와 기어 모름이 1초마다 번갈아", restroom = true, rest = false, b)
        }
        Both().let { b -> b.park(4 * min, doorAtMs = min, kph = 25f); b.driveOff(); check("P인데 차속 잡음", restroom = true, rest = false, b) }
    }

    @Test
    fun reparking() {
        // 휴게소 안에서 옮겨 대기: 3분 안에 시속 30km를 넘지 않고 다시 P면 같은 정차다 (옮긴 시간도 선 시간)
        Both().let { b ->
            b.park(10 * min)
            b.drive(min, 10f)
            b.park(5 * min)
            b.driveOff()
            check("10분 → 1분 옮김 → 5분", restroom = false, rest = true, b)
        }
        Both().let { b ->
            b.park(10 * min)
            b.drive(min, 8f, gear = Gear.REVERSE)
            b.park(5 * min)
            b.driveOff()
            check("후진으로 옮겨 대기", restroom = false, rest = true, b)
        }
        Both().let { b ->
            b.park(10 * min)
            b.drive(min, 50f)
            b.park(6 * min)
            b.driveOff()
            check("시속 50km로 나갔다 다시 섬: 정차 둘", restroom = false, rest = false, b)
        }
        Both().let { b ->
            b.park(10 * min)
            b.drive(3 * min + 10_000, 10f)
            b.park(6 * min)
            b.driveOff()
            check("3분 넘게 옮김: 정차 둘", restroom = false, rest = false, b)
        }
        Both().let { b ->
            b.park(2 * min, doorAtMs = min)
            b.drive(30_000, 10f)
            b.park(min)
            b.driveOff()
            check("2분(문) → 옮김 → 1분: 합쳐 3분 넘는 화장실 정차", restroom = true, rest = false, b)
        }
    }

    @Test
    fun creepingBetweenParks() {
        // 정체 속에서 P와 기어가기를 되풀이한다 (2분씩 다섯 번, 사이마다 시속 20km로 2분 = 670m). 예전에는 엔진도 감지기도 '다시 대기'로
        // 이어 세어 18분 정차 하나로 봤다: 달린 적 없는 휴식이 생기고, 문이 한 번 열렸으면 화장실 시계까지 돌아갔다.
        // 한 정차를 시작한 뒤 P 밖에서 움직인 거리가 300m를 넘으면 다시 대기가 아니다 (엔진 닫힘 검증 E4): 2분짜리 정차 다섯이다
        Both().let { b ->
            repeat(5) { i ->
                b.park(2 * min, doorAtMs = if (i == 0) min else null)
                if (i < 4) b.drive(2 * min, 20f)
            }
            b.driveOff()
            check("P 2분 · 시속 20km 2분 다섯 번", restroom = false, rest = false, b)
        }
        // 시속 15km로 2분(500m)도 넘는다 (엔진 AuditDecisionsTest.stopAndGoInParkIsNotARestOrARestroomStop의 순서)
        Both().let { b ->
            repeat(5) { i ->
                b.park(2 * min, doorAtMs = if (i == 2) min else null)
                if (i < 4) b.drive(2 * min, 15f)
            }
            b.driveOff()
            check("P 2분 · 시속 15km 2분 다섯 번", restroom = false, rest = false, b)
        }
    }

    @Test
    fun reparkingOnlyWithinThreeHundredMetresOfTheStop() {
        // 다시 대기는 그 정차를 시작한 뒤 움직인 거리가 모두 합쳐 300m 이하일 때만이다. 거리는 차속을 틱마다 더해 잰다 (지난 틱의 차속으로
        // 이번 틱까지 달렸다고 본다). 시간(3분)과 최고 차속(30km/h)은 넘지 않는 순서들이다
        Both().let { b ->
            b.park(10 * min)
            b.drive(50_000, 20f) // 278m
            b.park(5 * min)
            b.driveOff()
            check("278m 옮김: 같은 정차", restroom = false, rest = true, b)
        }
        Both().let { b ->
            b.park(10 * min)
            b.drive(60_000, 20f) // 333m
            b.park(5 * min)
            b.driveOff()
            check("333m 옮김: 정차 둘", restroom = false, rest = false, b)
        }
        // 여러 번 옮기면 거리를 이어 더한다: 150m + 200m. 따로 세면 둘 다 300m 아래라 18분 정차가 된다
        Both().let { b ->
            b.park(6 * min)
            b.drive(54_000, 10f) // 150m
            b.park(5 * min)
            b.drive(72_000, 10f) // 200m
            b.park(5 * min)
            b.driveOff()
            check("150m + 200m: 두 번째 옮김에서 새 정차", restroom = false, rest = false, b)
        }
        Both().let { b ->
            b.park(6 * min)
            b.drive(36_000, 10f) // 100m
            b.park(5 * min)
            b.drive(36_000, 10f) // 100m
            b.park(5 * min)
            b.driveOff()
            check("100m + 100m: 같은 정차", restroom = false, rest = true, b)
        }
        // 새 정차가 시작되면 거리도 새로 센다: 앞 정차에서 333m를 움직였어도 새 정차에서 100m 옮긴 것은 다시 대기다
        Both().let { b ->
            b.park(2 * min)
            b.drive(60_000, 20f) // 333m: 새 정차
            b.park(10 * min)
            b.drive(36_000, 10f) // 100m
            b.park(5 * min)
            b.driveOff()
            check("새 정차의 거리는 0부터", restroom = false, rest = true, b)
        }
        // 차속을 모르는 동안은 다시 대기로 볼 수 있는 가장 빠른 속도(시속 30km)로 움직였다고 본다
        Both().let { b ->
            b.park(10 * min)
            repeat(30) { b.feed(Gear.DRIVE, null) } // 250m로 본다
            b.park(5 * min)
            b.driveOff()
            check("차속 모름 30초: 같은 정차", restroom = false, rest = true, b)
        }
        Both().let { b ->
            b.park(10 * min)
            repeat(40) { b.feed(Gear.DRIVE, null) } // 333m로 본다
            b.park(5 * min)
            b.driveOff()
            check("차속 모름 40초: 정차 둘", restroom = false, rest = false, b)
        }
        // 화장실 정차도 같다: 2분(문) 서고 400m 기어간 뒤 2분은 화장실 정차가 아니다
        Both().let { b ->
            b.park(2 * min, doorAtMs = min)
            b.drive(72_000, 20f) // 400m
            b.park(2 * min)
            b.driveOff()
            check("2분(문) → 400m → 2분", restroom = false, rest = false, b)
        }
    }

    @Test
    fun speedNoiseOnTheTickTheCarParksAgain() {
        // 다시 P가 된 틱에 함께 온 차속은 잡음이다 (P에서는 바퀴가 잠긴다): 다시 대기의 최고 차속에 넣지 않는다
        Both().let { b ->
            b.park(10 * min)
            b.drive(30_000, 10f)
            b.feed(Gear.PARK, 45f)
            b.park(5 * min)
            b.driveOff()
            check("다시 세운 틱의 차속 잡음", restroom = false, rest = true, b)
        }
    }

    @Test
    fun aClockSetBackWhileParked() {
        // 정차 중 시각이 1시간 뒤로 맞춰졌다 (NTP): 둘 다 틱 사이 시간을 더해 선 시간을 잰다
        Both().let { b ->
            b.park(2 * min, doorAtMs = min)
            b.clockBack(60 * min)
            b.park(2 * min)
            b.driveOff()
            check("정차 중 시계가 뒤로", restroom = true, rest = false, b)
        }
    }

    @Test
    fun aRestoredStopThatIsMovedToAnotherSpotAroundTheRestart() {
        // 휴게소에서 2분(문 열림) 서 있다가 옮겨 대는 사이에 앱이 다시 떴다: 마지막 맥박은 20초 전(그때는 P), 지금은 D로 기어가는 중이고
        // 곧 다시 선다. 보지 못한 20초는 차속을 모르는 것이라 시속 30km로 본다(167m). 그 뒤 60m를 더 가서 다시 서면 같은 정차다
        fun restored() = Both(ParkState(start - 140_000, doorSeen = true), lastSeen = start - 20_000)

        restored().let { b ->
            b.drive(20_000, 10f) // 56m
            b.park(5 * min)
            b.driveOff()
            check("재시작을 낀 옮겨 대기: 같은 정차 (2분 + 5분, 문)", restroom = true, rest = false, b)
        }
        restored().let { b ->
            b.drive(60_000, 10f) // 167m를 더해 300m를 넘는다
            b.park(5 * min)
            b.driveOff()
            check("재시작 뒤 멀리 옮김: 앞 정차는 2분으로 끝났다", restroom = false, rest = false, b)
        }
        restored().let { b ->
            b.drive(5 * min, 60f)
            check("재시작 뒤 그대로 떠남", restroom = false, rest = false, b)
        }
    }

    @Test
    fun aRestoredStopAfterARestart() {
        // 재시작: 12분 전 P(문 열림), 마지막 맥박은 그 2분 뒤 또는 5분 뒤. 코어와 같은 순서로 둘에 되돌린다 (판정 전에는 엔진을 돌리지 않는다)
        val since = start - 12 * min
        fun restored(seenMin: Long) = Both(ParkState(since, doorSeen = true), lastSeen = since + seenMin * min)

        restored(2).let { b -> b.drive(5 * min, 60f); check("본 것은 2분, 이미 떠나 있다", restroom = false, rest = false, b) }
        restored(5).let { b -> b.drive(5 * min, 60f); check("본 것은 5분, 이미 떠나 있다", restroom = true, rest = false, b) }
        restored(2).let { b -> b.park(min); b.driveOff(); check("아직 P다: 못 본 10분도 선 것", restroom = true, rest = false, b) }
        restored(2).let { b -> b.park(4 * min); b.driveOff(); check("아직 P이고 16분을 채웠다", restroom = true, rest = true, b) }
        restored(2).let { b ->
            repeat(3) { b.feed(Gear.UNKNOWN, null) }
            b.drive(5 * min, 60f)
            check("기어가 3초 늦게 왔고 떠나 있다", restroom = false, rest = false, b)
        }
        restored(2).let { b ->
            repeat(3) { b.feed(Gear.UNKNOWN, null) }
            b.park(min)
            b.driveOff()
            check("기어가 3초 늦게 왔고 아직 P다", restroom = true, rest = false, b)
        }
        restored(2).let { b ->
            repeat(15) { b.feed(Gear.UNKNOWN, null) }
            b.drive(5 * min, 60f)
            check("기어가 끝내 오지 않았다 (10초 뒤 떠난 것으로)", restroom = false, rest = false, b)
        }
        Both(ParkState(since, doorSeen = false), lastSeen = since + 2 * min).let { b ->
            b.feed(Gear.UNKNOWN, null, door = true)
            b.park(min)
            b.driveOff()
            check("판정을 기다리는 동안 열린 문도 그 정차의 문이다", restroom = true, rest = false, b)
        }
    }
}
