package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.CoreRig
import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.FakeVoice
import dev.shimpyo.coach.fakes.InMemoryMemory
import dev.shimpyo.coach.fakes.KID
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 여정의 시작과 끝을 코어 끝까지: 정차 중 재시작(되돌린 정차의 판정), 오래 세운 뒤의 새 여정, 서비스 종료 때의 마지막 저장.
 * 규칙은 StopDetectorTest·TripSessionTest·WorldSwitchTest가 지키고, 여기는 감지기·여정·엔진이 같은 답을 내는지 본다
 * (서비스 닫힘 검증 N2·N3: 재시작 뒤 게이지와 기록이 어긋났다).
 */
class CoachCoreTripTest {
    private val wall = 1_762_909_200_000L
    private val min = 60_000L

    /** 정차 중에 꺼졌다가 다시 켜진 코어. [gapMin]분 전 마지막 맥박, 그때 [sinceMin]분 전부터 P였고 문이 열렸다 */
    private fun restarted(scope: TestScope, gapMin: Long, sinceMin: Long, restroomMinAgo: Long = 120): CoreRig {
        val rig = CoreRig(scope, settings = FakeSettings(listOf(DRIVER)), wallBase = wall)
        rig.savedTrip(
            TripHeartbeat(
                savedAt = wall - gapMin * min, tripStart = wall - 150 * min, lastRestroomAt = mapOf("driver" to wall - restroomMinAgo * min),
                restroomFallbackAt = wall - restroomMinAgo * min, continuousDrivingMin = 80.0, park = ParkState(wall - sinceMin * min, doorSeen = true),
            ),
        )
        rig.fix(37.40, 127.10)
        return rig
    }

    // ------------------------------------------------------------ 재시작: 되돌린 정차 (서비스 닫힘 검증 N2·N3)

    @Test
    fun aStopThatEndedWhileTheAppWasDownIsRecordedTheSameWhenTheGearSignalArrivesLate() = runTest {
        // '앱이 꺼진 사이 떠났으면 정차는 마지막 맥박 때 끝난다'는 규칙이 되돌린 뒤 첫 틱의 기어로만 판단했다. 서비스가 켜지면 첫 틱이
        // Car 연결(기어 구독)보다 먼저 올 수 있고 그 틱의 기어는 UNKNOWN이라 '아직 서 있다'로 보고 꺼져 있던 시간을 정차에 더했다:
        // 같은 상황이 신호 도착 순서에 따라 10분 정차 / 40분 정차로 갈렸다. 기어를 알 때까지 판정을 미룬다
        val rig = restarted(this, gapMin = 30, sinceMin = 40)
        rig.start()
        rig.run(3_000) // 기어·차속 신호가 아직 없다 (SignalHub 기본값)
        rig.drive(60f)
        rig.run(5_000)
        val log = rig.appended().single()
        assertEquals("정차 시작", wall - 40 * min, log.start)
        assertEquals("정차 끝 = 마지막 맥박 (기어를 첫 틱에 알았을 때와 같다)", wall - 30 * min, log.end)
    }

    @Test
    fun afterARestartTheGaugeAndTheHistoryAgreeAboutTheStop() = runTest {
        // 12분 전 P(문 열림), 2분 뒤 맥박을 쓰고 꺼졌다가 10분 뒤 주행 중에 다시 켜졌다. 예전에는 여정과 엔진(P 입력으로 미리 채움)이
        // '지금까지 서 있었다'로 보고 화장실 시계를 정차 시작 + 3분으로 당겼는데(게이지 140분), 정차 감지기는 '마지막 맥박 때 떠났다'로
        // 보고 2분짜리라 기록하지 않았다. 화면: 게이지는 방금 다녀온 것처럼 찼는데 휴식 기록에는 그 정차가 없었다
        val rig = restarted(this, gapMin = 10, sinceMin = 12, restroomMinAgo = 150)
        rig.drive(60f)
        rig.start()
        rig.run(5_000)
        val restroomAt = checkNotNull(rig.trips.stored?.lastRestroomAt?.get("driver")) { "전제: 첫 틱의 맥박" }
        val gaugeSawARestroomStop = restroomAt > wall - 150 * min
        val historyHasIt = rig.state.history.any { it.restroom }
        assertEquals(
            "게이지가 화장실 정차로 센 정차(마지막 화장실 ${(wall - restroomAt) / min}분 전, 게이지 ${rig.state.restroom?.minutes}분)는 기록에도 있어야 한다",
            gaugeSawARestroomStop, historyHasIt,
        )
        assertFalse("2분 정차는 화장실 정차가 아니다", historyHasIt)
        assertTrue("엔진의 게이지도 다녀오지 않은 것으로 센다: ${rig.state.restroom?.minutes}분", rig.state.restroom!!.minutes!! < 30)
    }

    @Test
    fun aRestartWhileStillParkedCountsTheWholeStopEvenWhenTheGearArrivesLate() = runTest {
        // 같은 정차인데 차가 아직 P다 (기어 신호는 3초 늦게 온다): 못 본 10분도 서 있던 것이고, 감지기·여정·엔진이 모두 그렇게 센다
        val rig = restarted(this, gapMin = 10, sinceMin = 12, restroomMinAgo = 150)
        rig.start()
        rig.run(3_000)
        assertNull("기어를 알기 전에는 게이지를 내지 않는다 (엔진을 돌리지 않는다)", rig.state.restroom)
        rig.park()
        rig.run(3_000)
        assertTrue("문 열림 + 12분: 화장실 시계가 돌아갔다 (${rig.state.restroom?.minutes}분)", rig.state.restroom!!.minutes!! > 100)
        assertEquals("여정도 같은 정차로 화장실을 당겼다", wall - 12 * min + 3 * min, rig.trips.stored!!.lastRestroomAt["driver"])
        rig.drive(50f)
        rig.run(3_000)
        val log = rig.appended().single()
        assertEquals("기록도 원래 시작부터", wall - 12 * min, log.start)
        assertTrue("지금 떠났다", log.end >= wall + 6_000)
        assertTrue(log.restroom)
    }

    @Test
    fun whileTheRestoredStopIsUndecidedTheTripIsNotSavedAndCommandsWait() = runTest {
        val rig = restarted(this, gapMin = 30, sinceMin = 40)
        val saved = checkNotNull(rig.trips.stored)
        rig.start()
        rig.run(2_000)
        assertEquals("판정 전에는 맥박을 새로 쓰지 않는다", saved, rig.trips.stored)
        rig.command(UiCommand.Urgent(9L))
        assertEquals("급해는 받되 엔진을 부르지 않고 기다린다", UrgentState.LOCATING, rig.state.urgentState)
        rig.voice.listens += "졸려"
        rig.command(UiCommand.Listen)
        rig.run(2_000)
        rig.drive(60f)
        rig.run(2_000)
        assertTrue("기어를 알면 이어서 찾는다: ${rig.state.urgentState}", rig.state.urgentState == UrgentState.READY || rig.state.urgentState == UrgentState.NO_RESULT)
        assertTrue("기다리는 동안 한 말(졸려)도 엔진에 간다: ${rig.voice.asked}", rig.voice.asked.any { "졸릴" in it })
        assertTrue("판정 뒤에는 맥박을 쓴다", rig.trips.stored!!.savedAt > saved.savedAt)
        assertNull(rig.trips.stored!!.park)
    }

    @Test
    fun withoutAnyGearSignalTheCoachStillStartsAfterTenSeconds() = runTest {
        // Car 연결이 끝내 안 되면(기어 UNKNOWN) 엔진을 영영 기다리지 않는다. 보지 못한 시간은 선 것으로 세지 않는다
        val rig = restarted(this, gapMin = 30, sinceMin = 40)
        rig.start()
        rig.run(StopDetector.RESTORE_WAIT_MS - 2_000)
        assertNull(rig.state.restroom)
        rig.run(4_000)
        assertNotNull("10초 뒤에는 게이지가 나온다", rig.state.restroom)
        val log = rig.appended().single()
        assertEquals(wall - 30 * min, log.end)
    }

    @Test
    fun leavingADemoWithTheCarNoLongerParkedEndsTheRealStopWhenTheDemoStartedInTheEngineToo() = runTest {
        // 실제로 P(문 열림)로 2분 서 있다가 시연을 켰고, 시연 중에 D로 바꾼 뒤 시연을 껐다. 실제 정차는 시연을 켠 때 끝난 것이다:
        // 기록에도(3분 미만이라 없다), 옆에 두었던 실제 엔진에도 (시연 20분을 선 것으로 세면 화장실 시계가 돌아간다)
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)), wallBase = wall)
        rig.drive(80f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(40 * min)
        rig.park()
        rig.run(10_000)
        rig.hub.markDoorOpened()
        rig.run(2 * min)
        val before = checkNotNull(rig.state.restroom?.minutes)
        rig.command(UiCommand.Demo("stop"))
        rig.run(20 * min)
        rig.drive(60f)
        rig.command(UiCommand.Demo(null))
        rig.run(5_000)
        assertTrue("실제 기록에 시연 시간만큼의 정차가 없다", rig.appended().isEmpty())
        val after = checkNotNull(rig.state.restroom?.minutes)
        assertTrue("실제 엔진도 2분 정차로 센다: 화장실 시계가 돌아가지 않았다 ($before → $after)", after <= before)
    }

    // ------------------------------------------------------------ 재시작: 들르기로 한 곳 (서비스 닫힘 검증 N7)

    /** 달리던 중 10초 전에 꺼졌다가 다시 켜진 코어. 꺼지기 [acceptedMinAgo]분 전에 입장졸음쉼터에 들르기로 했다 */
    private fun resumedMidDrive(scope: TestScope, acceptedMinAgo: Long?, gapMs: Long = 10_000L): CoreRig {
        val rig = CoreRig(scope, settings = FakeSettings(listOf(DRIVER)), wallBase = wall)
        rig.savedTrip(
            TripHeartbeat(
                savedAt = wall - gapMs, tripStart = wall - 100 * min, lastRestroomAt = mapOf("driver" to wall - 100 * min),
                restroomFallbackAt = wall - 100 * min, continuousDrivingMin = 100.0, park = null,
                accepted = acceptedMinAgo?.let { AcceptedStop("ipjang", wall - gapMs - it * min) },
            ),
        )
        rig.drive(100f)
        rig.fix(37.0, 127.19) // 입장졸음쉼터 10km 전
        return rig
    }

    @Test
    fun aStopAcceptedBeforeAMidDriveRestartIsNotedToTheNewEngineOnce() = runTest {
        // 주행 중 재시작하면 새 엔진은 방금 수락한 정차를 모른다: 같은 곳을 다시 물을 수 있었다. 맥박이 이어받은 곳이 후보에 보이면
        // 엔진에 '들르기로 한 곳'으로 다시 알린다 (EngineAccepted: 엔진의 noteAccepted가 들어오면 엔진이 그곳을 묻지 않는다)
        val rig = resumedMidDrive(this, acceptedMinAgo = 3)
        rig.start()
        rig.runUntil(30_000) { rig.accepted.notes.isNotEmpty() } ?: error("다시 알리지 않았다: ${rig.log.lines.takeLast(10)}")
        assertEquals("ipjang", rig.accepted.notes.single().stopId)
        assertTrue("후보에 보인 틱에 알린다", rig.state.ahead.any { it.id == "ipjang" })
        rig.run(60_000)
        assertEquals("한 번만", 1, rig.accepted.notes.size)
        assertEquals("맥박에는 처음 수락한 때가 그대로 남는다", wall - 10_000L - 3 * min, rig.trips.stored!!.accepted?.at)
    }

    @Test
    fun nothingIsNotedAfterABreakOrWhenTheStopNeverShowsUpAgain() = runTest {
        // 15분 넘게 꺼져 있었으면 그사이 섰던 것이다
        val afterBreak = resumedMidDrive(this, acceptedMinAgo = 3, gapMs = 20 * min)
        afterBreak.start()
        afterBreak.run(60_000)
        assertTrue(afterBreak.accepted.notes.isEmpty())

        // 그곳이 후보에 없다 (지나쳤다, 경로 후보가 아니었다): 2분 기다리고 잊는다. 나중에 같은 id가 보여도 알리지 않는다
        val passed = resumedMidDrive(this, acceptedMinAgo = 3)
        passed.fix(36.0, 127.4) // 입장졸음쉼터에서 100km: 가짜 표의 앞쪽 검색(반경 60km)에 들지 않는다
        passed.start()
        passed.run(CoachCore.ACCEPT_RESTORE_MS + 5_000)
        passed.fix(37.0, 127.19)
        passed.run(60_000)
        assertTrue("지나친 뒤에는 알리지 않는다: ${passed.accepted.notes}", passed.accepted.notes.none { it.stopId == "ipjang" })

        // 수락한 곳이 없던 여정
        val none = resumedMidDrive(this, acceptedMinAgo = null)
        none.start()
        none.run(60_000)
        assertTrue(none.accepted.notes.isEmpty())
    }

    // ------------------------------------------------------------ 새 여정

    @Test
    fun aLongParkThenDepartureStartsANewTripThroughTheCore() = runTest {
        // 4시간 넘게 세워 둔 뒤 출발하면 새 여정이다 (world.md 4.2): 엔진의 주행 상태가 새로 시작되고(피로 0부터), 떠 있던 질문과
        // 계획 요청은 새 엔진 기준으로 다시, 급해 요청은 살아 있으면 새 엔진에 다시 알린다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)), wallBase = wall)
        rig.drive(100f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(100 * min)
        val tired = checkNotNull(rig.state.fatigue?.minutes)
        rig.command(UiCommand.MakePlan)
        val gen = rig.state.planGeneration
        rig.park()
        rig.run(5 * 60 * min)
        val savesBefore = rig.trips.saves.size
        assertFalse(rig.log.lines.any { "new trip" in it })
        rig.drive(60f)
        rig.run(3_000)
        assertTrue("출발하는 틱에 새 여정", rig.log.lines.any { "new trip" in it })
        val trip = checkNotNull(rig.trips.stored)
        assertTrue("새 여정은 바로 저장한다", rig.trips.saves.size > savesBefore)
        assertTrue("출발 시각이 새 여정의 시작이다", trip.tripStart > wall + 6 * 60 * min)
        assertEquals(0.0, trip.continuousDrivingMin, 0.1)
        val fresh = checkNotNull(rig.state.fatigue?.minutes)
        assertTrue("피로는 새로 센다 (새 여정 전 $tired 분 → $fresh 분)", fresh > tired + 30)
        assertNull("지난 여정의 질문은 남지 않는다", rig.state.prompt)
        assertEquals("계획은 요청 없이 번호를 올리지 않는다", gen, rig.state.planGeneration)
        assertEquals("5시간 정차는 기록에 남는다", 1, rig.appended().size)
    }

    // ------------------------------------------------------------ 시작

    @Test
    fun aNewCoreDoesNotInheritWhatTheLastOneLeftInProgressOnTheScreen() = runTest {
        // 같은 프로세스에서 서비스만 다시 뜨면 화면 상태(CoachStore)에 죽은 코어의 값이 남아 있다. 넣는 중 표시가 남으면 화면의 버튼이
        // 끝내 켜지지 않는다 (새 코어에는 끝낼 들르기가 없다)
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, KID)), wallBase = wall)
        rig.ui.update { copy(goToPending = dev.shimpyo.coach.fakes.Gyeongbu.oksan, notice = "누가 함께 타나요?", asleepOccupantIds = setOf("kid")) }
        rig.park()
        rig.start()
        assertTrue(rig.state.serviceRunning)
        assertNull(rig.state.goToPending)
        assertNull(rig.state.notice)
        assertTrue(rig.state.asleepOccupantIds.isEmpty())
    }

    // ------------------------------------------------------------ 종료 (shutdown)

    @Test
    fun shutdownSavesTheTripAndTheLearningThatChangedSinceTheLastSave() = runTest {
        val memory = InMemoryMemory()
        val elder = Occupant("grandma", OccupantType.ELDERLY, label = "할머니")
        val rig = CoreRig(this, memory = memory, settings = FakeSettings(listOf(DRIVER, KID, elder)), wallBase = wall)
        rig.core.shutdown() // 시작 전: 아무것도 하지 않는다
        assertTrue(rig.trips.saves.isEmpty() && memory.saves.isEmpty())
        rig.drive(100f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(5 * min + 20_000)
        // 저장 간격(1분) 안에 학습이 두 번 바뀐다: 첫 변화는 바로 저장되고 두 번째는 다음 주기를 기다린다
        rig.voice.listens += listOf("아이가 화장실 가고 싶대", "할머니가 화장실 가고 싶으시대")
        rig.command(UiCommand.Listen)
        rig.run(10_000)
        rig.command(UiCommand.Listen)
        rig.run(5_000)
        val savedBefore = memory.saves.size
        val pending = memory.stored
        val tripSaves = rig.trips.saves.size
        rig.core.shutdown()
        assertEquals("맥박을 바로 쓴다", tripSaves + 1, rig.trips.saves.size)
        assertEquals(rig.clock.now(), rig.trips.stored!!.savedAt)
        assertTrue("달린 시간이 담긴다", rig.trips.stored!!.continuousDrivingMin >= 5.0)
        assertEquals("아직 못 쓴 학습을 쓴다", savedBefore + 1, memory.saves.size)
        assertTrue("저장 뒤 내용이 달라졌다", memory.stored != pending && memory.stored.restroomIntervalsMin.size == 2)
        rig.core.shutdown()
        assertEquals("바뀐 것이 없으면 학습을 다시 쓰지 않는다", savedBefore + 1, memory.saves.size)
    }

    @Test
    fun shutdownDuringADemoSavesTheRealTripAndNoDemoLearning() = runTest {
        val memory = InMemoryMemory()
        val rig = CoreRig(this, memory = memory, voice = FakeVoice(), wallBase = wall)
        rig.drive(100f)
        rig.start()
        rig.run(2 * min)
        rig.command(UiCommand.Demo("holiday"))
        rig.run(30_000)
        val memorySaves = memory.saves.size
        rig.core.shutdown()
        val hb = checkNotNull(rig.trips.stored)
        assertTrue("실제 여정의 맥박이다 (시연 시각이 아니다)", hb.tripStart in (wall - min)..(wall + min) && hb.savedAt <= rig.clock.now())
        assertEquals("시연의 학습은 쓰지 않는다", memorySaves, memory.saves.size)
        assertEquals(PersonalMemory(), memory.stored)
    }

    @Test
    fun shutdownBeforeTheRestoredStopIsDecidedLeavesTheSavedTripAlone() = runTest {
        val rig = restarted(this, gapMin = 30, sinceMin = 40)
        val saved = checkNotNull(rig.trips.stored)
        rig.start()
        rig.run(2_000)
        assertEquals(Gear.UNKNOWN, rig.hub.gear)
        rig.core.shutdown()
        assertEquals("확인하지 못한 정차를 '지금까지 서 있었다'로 남기지 않는다", saved, rig.trips.stored)
    }
}
