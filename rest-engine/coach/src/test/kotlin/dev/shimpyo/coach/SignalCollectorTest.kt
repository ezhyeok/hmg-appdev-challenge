package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.FakeLink
import dev.shimpyo.coach.fakes.FakeSignal
import dev.shimpyo.coach.fakes.Received
import dev.shimpyo.coach.fakes.RecordingLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 차량 신호 수집 (SignalWatch.kt). 가짜 연결·신호와 가상 시간으로 돈다.
 * 닫는 결함: 리뷰 R-F5(연결 실패를 삼켜 기어·차속이 재시작까지 없다), R-F10(공조 영역 조회가 실패하면 문·조향·브레이크를 모두 잃는다),
 * 서비스 감사 SV-D13(영역 조회·구독 실패가 한 번이면 끝, 늦게 받은 권한을 모른다), P8(Car 서비스 재시작), P12(서비스가 죽으면 값이 멈춘다).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignalCollectorTest {
    private val log = RecordingLog()

    private fun TestScope.collector() = SignalCollector(ElapsedClock { testScheduler.currentTime }, log)

    private fun TestScope.link(name: String, vararg groups: SignalGroup) = FakeLink(name, groups.toList()) { testScheduler.currentTime }

    private fun TestScope.start(link: SignalLink): Job {
        val c = collector()
        val job = backgroundScope.launch { c.run(link) }
        runCurrent()
        return job
    }

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // ------------------------------------------------------------ 연결

    @Test
    fun aConnectionThatFailsIsRetriedUntilItWorksAndThenTheValuesArrive() = runTest {
        // R-F5: 예전에는 Car 연결 실패를 삼키고 값이 오지 않는 흐름을 영영 기다렸다
        val gear = FakeSignal("gear", 4)
        val got = Received<Int>()
        val car = link("car", got.group(gear)).also { it.failConnects = 3 }
        start(car)
        assertTrue(got.values.isEmpty())
        pass(14_000)
        assertEquals("2초, 4초, 8초 뒤에 다시", listOf(0L, 2_000L, 6_000L, 14_000L), car.connects.map { it.first })
        assertEquals(listOf(4), got.values)
        assertEquals(1, gear.subscriptions)
        gear.set(8)
        runCurrent()
        assertEquals(8, got.last)
    }

    @Test
    fun aLinkThatKnowsItWasLostReconnectsAndTheValuesAreUnknownMeanwhile() = runTest {
        // P8: Car 서비스가 죽었다 살아난다. 값은 그동안 모름(null)이고, 다시 붙으면 새 값이 온다
        val gear = FakeSignal("gear", 8)
        val speed = FakeSignal("speed", 27.5f)
        val gears = Received<Int>()
        val speeds = Received<Float>()
        val car = link("car", gears.group(gear), speeds.group(speed))
        start(car)
        pass(120_000)
        car.lose()
        runCurrent()
        assertNull("끊기면 기어를 모른다", gears.last)
        assertNull(speeds.last)
        assertEquals(0, gear.active)
        gear.value = 4 // 다시 붙었을 때는 P
        pass(2_000)
        assertEquals(listOf(0L to false, 122_000L to true), car.connects)
        assertEquals(4, gears.last)
        assertEquals(27.5f, speeds.last)
        assertEquals(2, gear.subscriptions)
    }

    // ------------------------------------------------------------ 묶음

    @Test
    fun aGroupThatCannotBeResolvedDoesNotTakeTheOtherSignalsDown() = runTest {
        // R-F10: 공조 영역 조회(cabin)가 실패해도 문·조향·브레이크는 받는다. 실패한 묶음만 간격을 늘려 다시 묻는다
        var cabinAsked = 0
        val cabin = SignalGroup("cabin") {
            cabinAsked++
            throw IllegalStateException("checkHvacRecirculationCapabilities: no response in 20000ms")
        }
        val door = FakeSignal("door", 0)
        val steering = FakeSignal("steering", 1.5f)
        val doors = Received<Int>()
        val steerings = Received<Float>()
        start(link("vehicle", cabin, doors.group(door), steerings.group(steering)))
        assertEquals(listOf(0), doors.values)
        assertEquals(listOf(1.5f), steerings.values)
        // 다른 신호의 이벤트가 오는 한 연결은 살아 있다 (다시 붙지 않는다)
        repeat(20) {
            pass(10_000)
            steering.tick()
        }
        door.set(1)
        runCurrent()
        assertEquals(1, doors.last)
        assertEquals("2, 4, 8, 16, 32, 60, 60초 간격", 8, cabinAsked)
        assertEquals(1, door.subscriptions)
    }

    @Test
    fun aGroupResolvedLaterStartsItsSignalsThen() = runTest {
        // SV-D13: 문 영역 조회가 한 번 실패하면 문 이벤트가 영영 없었다 (화장실 정차를 못 알아본다)
        val door = FakeSignal("door", 0)
        val doors = Received<Int>()
        var asked = 0
        val group = SignalGroup("doors") {
            if (++asked < 3) throw IllegalStateException("checkDoorPositionCapabilities failed")
            listOf(doors.watch(door))
        }
        val brake = FakeSignal("brake", false)
        start(link("vehicle", group, Received<Boolean>().group(brake)))
        assertEquals(0, door.subscriptions)
        pass(6_000)
        assertEquals(3, asked)
        assertEquals(listOf(0), doors.values)
        door.set(1)
        runCurrent()
        assertEquals(1, doors.last)
    }

    @Test
    fun aGroupTheCarDoesNotSupportIsAskedOnce() = runTest {
        var asked = 0
        val group = SignalGroup("temperature") {
            asked++
            emptyList()
        }
        val brake = FakeSignal("brake", false)
        start(link("vehicle", group, Received<Boolean>().group(brake)))
        pass(600_000)
        assertEquals(1, asked)
        assertTrue(log.lines.any { it == "I vehicle.temperature: not supported on this car" })
    }

    // ------------------------------------------------------------ 신호 하나

    @Test
    fun aSignalThatCannotSubscribeIsRetriedAloneAndPicksUpALatePermission() = runTest {
        // SV-D13: 차속 권한(CAR_SPEED)을 늦게 받으면 화면의 PermissionsChanged 없이는 몰랐다
        val gear = FakeSignal("gear", 8)
        val speed = FakeSignal("speed", 13.9f).also {
            it.subscribeError = { SecurityException("CAR_SPEED") }
            it.readError = { SecurityException("CAR_SPEED") }
        }
        val gears = Received<Int>()
        val speeds = Received<Float>()
        val car = link("car", gears.group(gear), speeds.group(speed))
        start(car)
        pass(200_000)
        assertTrue(speeds.values.isEmpty())
        assertEquals("기어는 영향받지 않는다", 1, gear.subscriptions)
        assertEquals("기어를 읽을 수 있는 한 다시 붙지 않는다", 1, car.connects.size)
        speed.subscribeError = null
        speed.readError = null
        pass(60_000) // 늦어도 다음 다시 시도(최대 60초)에 걸린다
        assertEquals(13.9f, speeds.last)
        assertEquals(1, speed.subscriptions)
    }

    @Test
    fun aSubscriptionThatDiedSilentlyIsFoundByReadingAgain() = runTest {
        // P12: 리스너가 조용히 죽으면 마지막 값이 그대로 남았다. 30초마다 다시 읽어서, 이벤트 없이 값이 달라졌으면 다시 건다
        val recirc = FakeSignal("recirculation", true)
        val got = Received<Boolean>()
        start(link("vehicle", got.group(recirc), Received<Float>().group(FakeSignal("steering", 0f))))
        pass(45_000)
        recirc.mute = true
        recirc.set(false) // 차에서는 외기로 바뀌었지만 리스너는 조용하다
        runCurrent()
        assertEquals(true, got.last)
        pass(17_000) // 62초 시각의 다시 읽기
        assertEquals("다시 읽은 값을 곧바로 넘긴다", false, got.last)
        recirc.mute = false
        pass(2_000)
        assertEquals("그 신호만 다시 걸었다", 2, recirc.subscriptions)
        recirc.set(true)
        runCurrent()
        assertEquals(true, got.last)
    }

    @Test
    fun aQuietSignalWhoseValueDidNotChangeIsLeftAlone() = runTest {
        val door = FakeSignal("door", 0)
        val brake = FakeSignal("brake", false)
        val doors = Received<Int>()
        val car = link("vehicle", doors.group(door), Received<Boolean>().group(brake))
        start(car)
        pass(600_000)
        assertEquals(1, door.subscriptions)
        assertEquals("리스너가 첫 값을 줬으니 처음에는 읽지 않고, 30초마다 다시 읽는다 (32초, 62초, …, 572초)", 19, door.reads)
        assertEquals("값이 같으면 다시 넘기지 않는다", listOf(0), doors.values)
        assertEquals(1, car.connects.size)
    }

    @Test
    fun aSignalThatKeepsReportingIsNotResubscribedWhenAReadDiffers() = runTest {
        // 조향각처럼 계속 바뀌는 신호: 읽은 값이 리스너의 마지막 값과 달라도 이벤트가 오고 있으면 살아 있다
        val steering = FakeSignal("steering", 0f)
        val got = Received<Float>()
        start(link("vehicle", got.group(steering)))
        var angle = 0f
        repeat(120) {
            pass(1_000)
            angle += 0.5f
            steering.set(angle)
            runCurrent()
            steering.value = angle + 0.2f // 읽으면 조금 더 돌아가 있다
        }
        assertEquals(1, steering.subscriptions)
        assertEquals(60f, got.last)
    }

    @Test
    fun aStreamThatEndsIsSubscribedAgain() = runTest {
        val brake = FakeSignal("brake", false).also { it.endAfterSubscribe = 1 }
        val got = Received<Boolean>()
        start(link("vehicle", got.group(brake)))
        assertEquals(1, brake.subscriptions)
        pass(2_000)
        assertEquals(2, brake.subscriptions)
        brake.set(true)
        runCurrent()
        assertEquals(true, got.last)
    }

    @Test
    fun theFirstReadIsUsedOnlyWhenTheListenerGaveNothing() = runTest {
        // 걸자마자 값을 주지 않는 리스너: 읽은 값이 첫 값이다. 읽기도 실패했으면 다음 다시 읽기가 첫 값이다
        val cruise = FakeSignal("cruise", "ENABLED", announce = false)
        val got = Received<String>()
        val late = FakeSignal("turnSignal", "OFF", announce = false).also { it.readError = { IllegalStateException("busy") } }
        val gotLate = Received<String>()
        start(link("vehicle", got.group(cruise), gotLate.group(late)))
        assertTrue("리스너에게 먼저 기회를 준다", got.values.isEmpty())
        pass(2_000)
        assertEquals(listOf<String?>("ENABLED"), got.values)
        assertTrue(gotLate.values.isEmpty())
        late.readError = null
        pass(30_000)
        assertEquals(listOf<String?>("OFF"), gotLate.values)
        assertEquals("값을 받았을 뿐 구독은 그대로다", 1, late.subscriptions)
        // 걸자마자 값을 주는 리스너면 읽지 않는다 (같은 값을 두 번 넘기지 않는다)
        val door = FakeSignal("door", 1)
        val doors = Received<Int>()
        start(link("vehicle2", doors.group(door)))
        pass(10_000)
        assertEquals(listOf(1), doors.values)
        assertEquals(0, door.reads)
    }

    // ------------------------------------------------------------ 연결 점검

    @Test
    fun whenNothingCanBeReadAndNothingArrivesTheLinkIsReconnected() = runTest {
        // P12: Pleos 차량 서비스가 멈추면(am force-stop) SDK는 다시 붙지 않고 값이 멈춘다 (2026-11-12 에뮬레이터)
        val door = FakeSignal("door", 0)
        val recirc = FakeSignal("recirculation", true)
        val steering = FakeSignal("steering", 9f)
        val doors = Received<Int>()
        val recircs = Received<Boolean>()
        val vehicle = link("vehicle", doors.group(door), recircs.group(recirc), Received<Float>().group(steering))
        start(vehicle)
        repeat(12) {
            pass(10_000)
            steering.tick()
        }
        assertEquals(1, vehicle.connects.size)
        // 서비스가 죽었다: 이벤트가 끊기고 읽기는 모두 실패한다
        val dead = { IllegalStateException("getProperty: no response in 20000ms") }
        listOf(door, recirc, steering).forEach {
            it.mute = true
            it.readError = dead
        }
        pass(62_000)
        assertEquals("늦어도 점검 두 번 안에 다시 붙는다", 2, vehicle.connects.size)
        assertTrue(vehicle.connects[1].second)
        assertNull("다시 붙는 동안 값은 모른다", recircs.last)
        // 다시 붙은 뒤에는 서비스가 살아 있다
        listOf(door, recirc, steering).forEach {
            it.mute = false
            it.readError = null
        }
        recirc.value = false
        pass(60_000)
        assertEquals(false, recircs.last)
        assertEquals(0, doors.last)
        assertTrue(log.lines.any { it.startsWith("W vehicle failed: all ") })
    }

    @Test
    fun oneSignalThatCannotBeReadDoesNotReconnectTheLink() = runTest {
        // 공조 전원이 꺼져 있으면 설정 온도는 읽히지 않는다. 서 있는 차는 이벤트도 없다. 그래도 연결은 살아 있다
        val temp = FakeSignal("temperature", 22f, announce = false).also { it.readError = { IllegalStateException("PropertyNotAvailable") } }
        val door = FakeSignal("door", 0)
        val temps = Received<Float>()
        val vehicle = link("vehicle", temps.group(temp), Received<Int>().group(door))
        start(vehicle)
        pass(900_000)
        assertEquals(1, vehicle.connects.size)
        assertEquals(1, temp.subscriptions)
        temp.readError = null
        temp.set(23.5f) // 공조를 켜면 리스너가 값을 준다
        runCurrent()
        assertEquals(23.5f, temps.last)
    }

    @Test
    fun theLinkHealthRuleNeedsEvidence() {
        assertTrue(LinkHealth.lost(reads = 2, failed = 2, events = 0))
        assertTrue(LinkHealth.lost(reads = 13, failed = 13, events = 0))
        assertFalse("읽기 하나로는 판단하지 않는다", LinkHealth.lost(reads = 1, failed = 1, events = 0))
        assertFalse("읽을 것이 없었다", LinkHealth.lost(reads = 0, failed = 0, events = 0))
        assertFalse("하나라도 읽혔다", LinkHealth.lost(reads = 5, failed = 4, events = 0))
        assertFalse("이벤트가 오고 있다", LinkHealth.lost(reads = 5, failed = 5, events = 1))
    }

    @Test
    fun theSignalHealthRule() {
        assertTrue(SignalHealth.stale(quiet = true, last = true, fresh = false))
        assertTrue("값이 생겼는데 몰랐다", SignalHealth.stale(quiet = true, last = null, fresh = 1))
        assertFalse(SignalHealth.stale(quiet = true, last = 22.5f, fresh = 22.5f))
        assertFalse(SignalHealth.stale(quiet = false, last = 1f, fresh = 2f))
    }

    // ------------------------------------------------------------ 끝

    @Test
    fun cancellingStopsEverythingAndLeavesTheValuesUnknown() = runTest {
        val gear = FakeSignal("gear", 8)
        val got = Received<Int>()
        val car = link("car", got.group(gear))
        val job = start(car)
        pass(5_000)
        job.cancelAndJoin()
        assertEquals(0, gear.active)
        assertNull(got.last)
        pass(600_000)
        assertEquals("취소한 뒤에는 다시 붙지 않는다", 1, car.connects.size)
    }
}
