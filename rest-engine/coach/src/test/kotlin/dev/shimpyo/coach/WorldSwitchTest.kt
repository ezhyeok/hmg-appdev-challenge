package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.InMemoryMemory
import dev.shimpyo.coach.fakes.InMemoryTripStore
import dev.shimpyo.coach.fakes.KID
import dev.shimpyo.coach.fakes.WorldClock
import dev.shimpyo.coach.fakes.seededEngines
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CoachOutput
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PromptStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

/**
 * WorldSwitch: 세계 전환 (구조 리뷰 3.8·B7, 서비스 감사 D4·D10·D12·D15, UI #15). 엔진은 고정 Random으로 만든다.
 * 예전 서비스: 시연을 켜면 실제 학습을 저장하지 않고 엔진을 버렸고, 끄면 파일에서 연속 주행 0으로 새로 만들었다.
 * 시연 중 탑승자·설정을 바꾸면 실제 여정의 tripStart로 프로필을 만들어 시연 여정이 리셋됐고, 시연 탑승자가 설정에 저장됐다.
 */
class WorldSwitchTest {
    private val min = 60_000L
    private val hour = 60 * min
    /** 2026-11-11 10:00 KST */
    private val clock = WorldClock(Instant.parse("2026-11-11T01:00:00Z").toEpochMilli())
    private val settings = FakeSettings(occupants = listOf(DRIVER))
    private val learned = PersonalMemory(promptStats = mapOf("LAST_CHANCE:2-5km" to PromptStat(accepted = 3, declined = 1)))
    private val memory = InMemoryMemory(stored = learned)
    private val trips = InMemoryTripStore()

    private fun worlds() = WorldSwitch(seededEngines(), settings, memory, trips, wall = clock, elapsed = clock)
    private fun opened(): WorldSwitch = worlds().also { it.open(trips.load(), memory.load(), emptyList()) }

    /** 코치 틱 한 번 (코어와 같은 순서: 여정 → 엔진) */
    private fun WorldSwitch.tick(gear: Gear = Gear.DRIVE, speed: Float = 100f, park: ParkState? = null, door: Boolean = false, stepMs: Long = 5_000L): CoachOutput {
        clock.advance(stepMs)
        val now = active.clock.now()
        onTick(now, park)
        val a = active
        return a.engine.update(a.world.apply(CoachInput(now = now, gear = gear, speedKph = speed, doorOpened = door)))
    }

    private fun WorldSwitch.drive(minutes: Int): CoachOutput {
        var out: CoachOutput? = null
        repeat((minutes * min / 5_000L).toInt()) { out = tick() }
        return checkNotNull(out)
    }

    @Test
    fun openBuildsTheRealWorldFromTheTripSession() {
        val w = worlds()
        val c = w.open(null, memory.load(), emptyList())
        assertEquals(WorldChange.Kind.WORLD, c.kind)
        assertTrue(c.active.world is CoachWorld.Real)
        assertEquals(1, c.active.generation)
        assertEquals(clock.now(), c.active.profile(settings).tripStart)
        assertEquals(c.active.session.anchor.tripStart, c.active.profile(settings).tripStart)
        assertEquals("실제 엔진은 저장된 학습으로", learned, c.active.engine.memory())
        assertNull(c.restorePark)
        assertSame(c.active, w.real)
    }

    @Test
    fun aDemoKeepsTheRealEngineAsideAndExitRestoresIt() {
        val w = opened()
        val before = w.drive(30)
        val realEngine = w.active.engine
        val drivenBefore = w.active.session.anchor.continuousDrivingMin
        val enter = w.enterDemo("holiday")
        assertTrue(enter.active.world is CoachWorld.Demo)
        assertNotSame(realEngine, enter.active.engine)
        assertSame("실제 엔진은 옆에 둔다", realEngine, w.real.engine)
        repeat(36) { w.tick() } // 시연 3분
        val exit = w.exitDemo()
        assertEquals(WorldChange.Kind.WORLD, exit.kind)
        assertTrue(exit.active.world is CoachWorld.Real)
        assertSame("B7/D10: 파일에서 새로 만들지 않고 같은 엔진", realEngine, exit.active.engine)
        val after = w.tick()
        assertTrue("D10: 피로가 0부터 다시 세지 않는다 (${before.fatigue.minutes} → ${after.fatigue.minutes})", after.fatigue.minutes!! <= before.fatigue.minutes!!)
        assertTrue(w.active.session.anchor.continuousDrivingMin >= drivenBefore)
    }

    @Test
    fun enteringADemoSavesTheRealLearningAndTripFirst() {
        val w = opened()
        w.drive(1)
        val tripSaves = trips.saves.size
        val c = w.enterDemo("fatigue")
        assertEquals("D15: 시연 전에 실제 학습 저장", MemorySave(learned, memory.generation), c.saveMemory)
        assertEquals("여정 맥박도 바로", tripSaves + 1, trips.saves.size)
        assertEquals(clock.now(), trips.stored!!.savedAt)
    }

    @Test
    fun aDemoStartsFromEmptyLearningAndWritesNothingReal() {
        val w = opened()
        w.drive(1)
        w.enterDemo("stop")
        assertEquals(PersonalMemory(), w.active.engine.memory())
        val tripSaves = trips.saves.size
        val since = w.active.clock.now()
        repeat(120) { w.tick(gear = Gear.PARK, speed = 0f, park = ParkState(since, doorSeen = it > 3), door = it == 4) }
        w.onStop(StopLog(since, w.active.clock.now(), null, null, restroom = true, name = "안성휴게소"), w.active.clock.now())
        assertEquals("시연 여정은 trip.json에 쓰지 않는다", tripSaves, trips.saves.size)
        assertNull("시연 학습은 저장하지 않는다", w.memoryToSave())
        assertTrue("실제 여정의 마지막 화장실은 그대로", w.real.session.anchor.restroomFallbackAt < since)
    }

    @Test
    fun switchingBetweenDemosKeepsTheOriginalRealWorld() {
        val w = opened()
        val realEngine = w.active.engine
        val first = w.enterDemo("holiday")
        val second = w.enterDemo("fatigue")
        assertNotNull(first.saveMemory)
        assertNull("실제 학습은 처음 시연에 들어갈 때 한 번만 저장", second.saveMemory)
        assertEquals("fatigue", second.active.world.name)
        assertSame(realEngine, w.real.engine)
        assertSame(realEngine, w.exitDemo().active.engine)
    }

    @Test
    fun demoOffOrAnUnknownNameReturnsToTheRealWorld() {
        val w = opened()
        val realEngine = w.active.engine
        w.enterDemo("holiday")
        val off = w.enterDemo(null)
        assertEquals(WorldChange.Kind.WORLD, off.kind)
        assertSame(realEngine, off.active.engine)
        w.enterDemo("holiday")
        assertSame("모르는 이름 = 끄기 (예전 DemoDirector와 같다)", realEngine, w.enterDemo("nope").active.engine)
    }

    @Test
    fun exitingWhileRealChangesNothing() {
        val w = opened()
        val a = w.active
        val c = w.exitDemo()
        assertEquals(WorldChange.Kind.NONE, c.kind)
        assertSame(a, w.active)
    }

    @Test
    fun rapidDemoTogglesEndOnTheOriginalRealWorld() {
        val w = opened()
        val realEngine = w.active.engine
        val gen = w.active.generation
        w.enterDemo("holiday"); w.enterDemo(null); w.enterDemo("holiday"); w.enterDemo(null)
        assertTrue(w.active.world is CoachWorld.Real)
        assertSame(realEngine, w.active.engine)
        assertSame(w.active, w.real)
        assertEquals("전환마다 세대가 늘어난다 (옛 질문을 버린다)", gen + 4, w.active.generation)
    }

    @Test
    fun occupantChangesDuringADemoStayInTheDemoAndKeepItsTrip() {
        val w = opened()
        w.enterDemo("holiday")
        repeat(30) { w.tick() }
        val before = w.tick()
        val demoEngine = w.active.engine
        val tripStart = w.active.profile(settings).tripStart
        val baby = Occupant("baby", OccupantType.INFANT, label = "아기")
        w.setOccupants(listOf(DRIVER, KID, baby))
        assertEquals("UI #15: 시연 탑승자는 설정에 쓰지 않는다", 0, settings.occupantWrites)
        assertEquals(listOf(DRIVER), settings.occupants)
        assertSame(demoEngine, w.active.engine)
        assertEquals(listOf("driver", "kid", "baby"), w.active.world.occupants(settings).map { it.id })
        assertEquals("D4/D5: 시연 여정 그대로", tripStart, w.active.profile(settings).tripStart)
        val after = w.tick()
        assertTrue("D4: 피로가 리셋되지 않는다 (${before.fatigue.minutes} → ${after.fatigue.minutes})", after.fatigue.minutes!! <= before.fatigue.minutes!!)
        assertEquals("시연을 끄면 실제 탑승자", listOf(DRIVER), w.exitDemo().active.world.occupants(settings))
    }

    @Test
    fun aSettingsChangeDuringADemoKeepsTheDemoTrip() {
        val w = opened()
        w.enterDemo("fatigue")
        repeat(30) { w.tick() }
        val before = w.tick()
        val tripStart = w.active.profile(settings).tripStart
        settings.sleepHours = 4f
        w.profileChanged()
        val after = w.tick()
        assertEquals("D5: 수면 시간을 바꿔도 시연 여정", tripStart, w.active.profile(settings).tripStart)
        assertTrue("짧은 수면은 피로를 더할 뿐 리셋하지 않는다 (${before.fatigue.minutes} → ${after.fatigue.minutes})", after.fatigue.minutes!! <= before.fatigue.minutes!!)
    }

    @Test
    fun occupantChangesInTheRealWorldAreSavedAndKeepTheTrip() {
        val w = opened()
        w.drive(10)
        val engine = w.active.engine
        val tripStart = w.active.profile(settings).tripStart
        w.setOccupants(listOf(DRIVER, KID))
        assertEquals(1, settings.occupantWrites)
        assertEquals(listOf(DRIVER, KID), settings.occupants)
        assertSame(engine, w.active.engine)
        assertEquals(tripStart, w.active.profile(settings).tripStart)
        assertEquals("나중에 탄 아이는 'NOW'가 아니라 여정의 마지막 화장실", w.active.session.anchor.restroomFallbackAt, w.active.profile(settings).lastRestroomAt["kid"])
    }

    @Test
    fun withdrawingHealthConsentRemovesHealthOccupantsFromEveryWorld() {
        // UI 닫힘 검증 N3: 동의는 설정 하나인데 탑승자는 세계마다 따로다. 시연 중에 동의를 끄면 옆에 둔 실제 세계의 임신부가
        // 시연을 끈 뒤 동의 없이 돌아왔다
        val pregnant = Occupant("p", OccupantType.PREGNANT, label = "임신부")
        val oab = Occupant("o", OccupantType.OAB, label = "과민성 방광")
        settings.occupants = listOf(DRIVER, pregnant)
        val writes = settings.occupantWrites
        val w = opened()
        w.drive(5)
        w.enterDemo("holiday")
        w.setOccupants(listOf(DRIVER, KID, oab)) // 시연에서 사용자가 고친 탑승자
        assertTrue(w.dropHealthOccupants())
        assertEquals("옆에 둔 실제 세계의 탑승자를 설정에서 뺀다", listOf(DRIVER), settings.occupants)
        assertEquals(writes + 1, settings.occupantWrites)
        assertEquals("시연에서 고친 탑승자에서도 뺀다", listOf(DRIVER, KID), w.active.world.occupants(settings))
        assertEquals("시연 여정은 그대로", "holiday", w.active.world.name)
        val real = w.exitDemo().active
        assertEquals(listOf(DRIVER), real.world.occupants(settings))
        assertEquals("실제 엔진도 그 사람을 더는 세지 않는다", "driver", w.tick().restroom.drivenBy)
        assertEquals("뺄 사람이 없으면 아무것도 바꾸지 않는다", false, w.dropHealthOccupants())
        assertEquals(writes + 1, settings.occupantWrites)
    }

    @Test
    fun scenarioOccupantsOfADemoAreNotTheUsersHealthData() {
        val w = opened()
        w.enterDemo("holiday")
        val before = w.active
        assertEquals(false, w.dropHealthOccupants())
        assertSame("시나리오가 정한 탑승자는 건드리지 않는다", before, w.active)
        assertEquals(0, settings.occupantWrites)
    }

    @Test
    fun clearingLearningDuringADemoAlsoClearsTheKeptRealEngine() {
        val w = opened()
        w.drive(5)
        val tripStart = w.real.session.anchor.tripStart
        w.enterDemo("holiday")
        val demoEngine = w.active.engine
        assertEquals("시연 엔진은 그대로", WorldChange.Kind.NONE, w.clearLearning().kind)
        assertSame(demoEngine, w.active.engine)
        val exit = w.exitDemo()
        assertEquals("시연을 끈 뒤 자동 저장이 지운 학습을 되살리지 않는다", PersonalMemory(), exit.active.engine.memory())
        assertEquals(PersonalMemory(), w.memoryToSave()!!.memory)
        assertEquals("여정은 이어진다", tripStart, exit.active.profile(settings).tripStart)
    }

    @Test
    fun clearingLearningInTheRealWorldRebuildsTheEngineButKeepsTheTrip() {
        val w = opened()
        val before = w.drive(30)
        val engine = w.active.engine
        val gen = w.active.generation
        val c = w.clearLearning()
        assertEquals(WorldChange.Kind.ENGINE, c.kind)
        assertEquals(gen + 1, c.active.generation)
        assertNotSame(engine, c.active.engine)
        assertEquals(PersonalMemory(), c.active.engine.memory())
        val after = w.tick()
        assertTrue("연속 주행을 기준점으로 이어 간다 (${before.fatigue.minutes} → ${after.fatigue.minutes})", abs(after.fatigue.minutes!! - before.fatigue.minutes!!) <= 2)
    }

    @Test
    fun aLongParkThenDepartureStartsANewTripInTheEngine() {
        val w = opened()
        w.drive(10)
        val since = clock.now()
        repeat(5 * 60) { w.tick(gear = Gear.PARK, speed = 0f, park = ParkState(since, doorSeen = false), stepMs = min) }
        val gen = w.active.generation
        clock.advance(1_000)
        val departure = clock.now()
        val c = checkNotNull(w.onTick(departure, null))
        assertEquals(WorldChange.Kind.ENGINE, c.kind)
        assertEquals(gen + 1, c.active.generation)
        assertEquals(departure, c.active.profile(settings).tripStart)
    }

    @Test
    fun learningIsSavedOnlyInTheRealWorld() {
        val w = opened()
        assertEquals(MemorySave(learned, memory.generation), w.memoryToSave())
        w.enterDemo("urgent")
        assertNull(w.memoryToSave())
        w.exitDemo()
        assertNotNull(w.memoryToSave())
    }

    @Test
    fun exitingADemoRestoresTheRealParkForTheStopDetector() {
        val w = opened()
        w.drive(10)
        val park = ParkState(clock.now(), doorSeen = true)
        repeat(6) { w.tick(gear = Gear.PARK, speed = 0f, park = park) }
        assertNull("시연은 정차 감지를 비우고 시작한다", w.enterDemo("stop").restorePark)
        assertEquals("실제 정차로 되돌린다 (멈춰 있던 실제 엔진도 같은 정차를 기억한다)", park, w.exitDemo().restorePark)
    }

    @Test
    fun aStickyRestartMidDriveKeepsTheFatigueGauge() {
        val first = opened()
        val before = first.drive(100)
        clock.advance(20_000) // 프로세스 재시작 (같은 저장소)
        val second = worlds()
        val c = second.open(trips.load(), memory.load(), emptyList())
        assertEquals(TripOrigin.RESUMED, c.active.session.origin)
        val after = second.tick()
        assertTrue("D8: 재시작해도 피로 게이지가 이어진다 (${before.fatigue.minutes} → ${after.fatigue.minutes})", abs(after.fatigue.minutes!! - before.fatigue.minutes!!) <= 3)
    }

    @Test
    fun aRestartWhileParkedResumesTheStopInTheEngine() {
        val first = opened()
        first.drive(100)
        val since = clock.now()
        repeat(24) { first.tick(gear = Gear.PARK, speed = 0f, park = ParkState(since, doorSeen = it >= 6), door = it == 6) } // 2분, 30초째 문
        clock.advance(20_000)
        val second = worlds()
        val c = second.open(trips.load(), memory.load(), emptyList())
        assertEquals("D12: 정차 감지기에 되돌릴 정차", ParkState(since, doorSeen = true), c.restorePark)
        var out: CoachOutput? = null
        repeat(14 * 12) { out = second.tick(gear = Gear.PARK, speed = 0f, park = ParkState(since, doorSeen = true)) } // 14분 더
        val parked = checkNotNull(out)
        assertTrue("재시작을 넘은 화장실 정차 (${parked.restroom.minutes})", parked.restroom.minutes!! > 60)
        assertTrue("재시작을 넘은 15분 휴식 (${parked.fatigue.minutes})", parked.fatigue.minutes!! > 100)
        assertEquals(0.0, second.active.session.anchor.continuousDrivingMin, 0.0)
    }
}
