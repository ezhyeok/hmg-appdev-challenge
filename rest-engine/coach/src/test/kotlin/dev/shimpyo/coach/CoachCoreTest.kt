package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.CoreRig
import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeNaviPort
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.FakeVoice
import dev.shimpyo.coach.fakes.Gyeongbu
import dev.shimpyo.coach.fakes.InMemoryMemory
import dev.shimpyo.coach.fakes.KID
import dev.shimpyo.coach.fakes.PromptFakes
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PromptKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CoachCore를 서비스처럼 조립해(CoreRig: 같은 부품, 같은 루프, 가상 시간 하나) 시나리오 끝까지 돌린다
 * (docs/design/coach-layer/core.md 11장 '시나리오 → 테스트'). 엔진은 실제 엔진(고정 난수), 내비·음성·공조·저장소는 가짜다.
 * 급해는 CoachCoreUrgentTest, 들르기는 CoachCoreGoToTest, 음성은 CoachCoreVoiceTest, 재시작·새 여정·종료는 CoachCoreTripTest,
 * 자는 중 표시는 CoachCoreAsleepTest.
 */
class CoachCoreTest {

    private fun CoreRig.toOksanM(): Double? = state.ahead.firstOrNull { it.id == "oksan" }?.routeDistanceM

    // ------------------------------------------------------------ 귀성길 시연 (핵심 가치)

    @Test
    fun holidayDemoAsksOksanAsTheLastChanceAndYesStartsTheRouteThere() = runTest {
        val rig = CoreRig(this, voice = FakeVoice(answer = { if ("옥산" in it) "응" else null }))
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        assertEquals("holiday", rig.state.demo)

        var askedAtM: Double? = null
        rig.runUntil(150_000) {
            if (askedAtM == null && rig.state.prompt?.kind == PromptKind.LAST_CHANCE) askedAtM = rig.state.promptStop?.routeDistanceM
            rig.state.goToResult != null
        } ?: error("들르기 결과가 없다. 로그: ${rig.log.lines.takeLast(15)}")

        val at = checkNotNull(askedAtM) { "마지막 기회 카드가 뜨지 않았다" }
        assertTrue("옥산 진입로 2~5km 전에 묻는다 (${at}m)", at in 2_000.0..5_000.0)
        assertTrue("음성으로 옥산을 물었다", rig.voice.asked.any { "옥산" in it })
        val cmd = rig.navi.commands.single()
        assertEquals("길안내가 없던 내비는 새 길안내", "startRoute", cmd.name)
        assertEquals(Gyeongbu.oksan.lat, cmd.lat, 1e-9)
        assertEquals(Gyeongbu.oksan.lon, cmd.lon, 1e-9)
        val r = rig.state.goToResult!!
        assertEquals(GoToOutcome.ROUTE_STARTED, r.outcome)
        assertEquals("oksan", r.stop.id)
        assertTrue("결과를 말한다", rig.voice.said.any { "옥산" in it && "안내할게요" in it })

        // 엔진에 수락을 알렸으므로 옥산을 다시 묻지 않는다
        val asks = rig.voice.asked.count { "옥산" in it }
        rig.run(20_000)
        assertEquals(asks, rig.voice.asked.count { "옥산" in it })
    }

    @Test
    fun withARouteGuidingNaviTheAcceptAddsOksanAsAWaypoint() = runTest {
        val rig = CoreRig(this, voice = FakeVoice(answer = { if ("옥산" in it) "응" else null }))
        rig.fakeNavi.mode = { NaviMode.ROUTE_GUIDING }
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.runUntil(150_000) { rig.state.goToResult != null } ?: error("결과 없음")
        assertEquals(GoToOutcome.WAYPOINT_ADDED, rig.state.goToResult!!.outcome)
        val cmd = rig.navi.commands.single()
        assertEquals("addWaypoint", cmd.name)
        assertEquals(Gyeongbu.oksan.lat, cmd.lat, 1e-9)
    }

    @Test
    fun theLastChanceCardStaysLongEnoughToExplainAndTapInTheSevenTimesFasterDemo() = runTest {
        // 시연 엔진의 질문 수명을 배속만큼 늘리지 않으면 실제 6초 만에 카드가 사라졌다 (prompt.md 2.5).
        // 늘린 뒤에도 시연 차가 1초에 200m라 옥산 500m 앞에서 엔진이 거둘 때까지 실제 13~25초뿐이어서(2026-11-13 기기), PT에서
        // 카드를 설명하고 누를 시간이 빠듯했다. 이제 카드가 떠 있는 동안 시연이 4배 느리게 간다 (CoachWorld.questionShown)
        val rig = CoreRig(this) // 대답하지 않는다
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.runUntil(150_000) { rig.state.prompt?.kind == PromptKind.LAST_CHANCE } ?: error("카드가 뜨지 않았다")
        val askedAt = checkNotNull(rig.toOksanM())
        val shownFor = rig.runUntil(120_000) { rig.state.prompt?.kind != PromptKind.LAST_CHANCE } ?: 120_000
        // 2km에서 물어도 500m 앞까지 1.5km ÷ 50m/s = 30초. 카드 자체의 실제 수명(45초 + 음성 뒤 누를 시간)보다 오래 남지는 않는다
        assertTrue("카드가 ${shownFor}ms 떠 있었다 (${askedAt}m에서 물음)", shownFor in 30_000L..(PromptMachine.ENGINE_CARD_MS + PromptMachine.MIN_TAP_MS + 2_000))
        assertNull("카드가 내려가면", rig.state.prompt)
        // 카드가 내려간 뒤에는 원래 빠르기(실제 1초에 200m)로 달린다
        val before = rig.toOksanM()
        rig.run(2_000)
        val after = rig.toOksanM()
        if (before != null && after != null) assertEquals("원래 빠르기", 400.0, before - after, 30.0)
        assertTrue("빠르기를 바꾼 것이 로그에 남는다", rig.log.lines.any { "demo pace 0.25" in it } && rig.log.lines.any { "demo pace 1.0" in it })
    }

    @Test
    fun whileTheCardIsUpTheDemoCarMovesFourTimesSlowerAtTheSameVirtualSpeed() = runTest {
        val rig = CoreRig(this)
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.runUntil(150_000) { rig.state.prompt?.kind == PromptKind.LAST_CHANCE } ?: error("카드가 뜨지 않았다")
        val m0 = checkNotNull(rig.toOksanM())
        rig.run(4_000)
        val m1 = checkNotNull(rig.toOksanM())
        assertEquals("카드가 떠 있는 동안 실제 4초에 200m (원래 800m)", 200.0, m0 - m1, 30.0)
        assertEquals("게이지와 질문은 그대로", PromptKind.LAST_CHANCE, rig.state.prompt?.kind)
    }

    // ------------------------------------------------------------ 출발 전 한마디 (일상 주행)

    @Test
    fun beforeLeavingHomeWithADestinationTheAppSaysGoFirstAndYesSendsNoNaviCommand() = runTest {
        // 아침에 집 앞(기어 P)에서 목적지를 정했다: 내비는 길안내 중, 남은 시간 70분. 아이는 1시간 전에 다녀왔다(게이지 45분).
        // 휴게소가 없는 일상 길이라도 출발 전에 한 번 묻는다 (CoachEngine.departureCheck)
        val wall = 1_762_909_200_000L // CoreRig의 실제 시각 (2025-11-12 10:00 KST)
        val lastRestroom = StopLog(start = wall - 70 * 60_000L, end = wall - 60 * 60_000L, lat = null, lon = null, restroom = true)
        val rig = CoreRig(this, voice = FakeVoice(answer = { if ("출발 전" in it) "응" else null }), history = PromptFakes.History(listOf(lastRestroom)))
        rig.fakeNavi.mode = { NaviMode.ROUTE_GUIDING }
        rig.fakeNavi.destination = { FakeNaviPort.dest(48_000.0, timeS = 70 * 60.0, name = "판교 어린이도서관") }
        rig.park()
        rig.start()
        rig.runUntil(60_000) { rig.state.prompt?.kind == PromptKind.DEPARTURE } ?: error("출발 전 질문이 없다. 로그: ${rig.log.lines.takeLast(10)}")
        val card = rig.state.prompt!!
        assertTrue(card.speech, "출발 전" in card.speech && "1시간 10분" in card.speech)
        assertNull("정차지를 묻지 않는다", card.stop)
        rig.runUntil(30_000) { rig.voice.said.any { it == PromptMachine.DEPARTURE_ACCEPTED } } ?: error("'응'에 답하지 않았다: ${rig.voice.said}")
        assertTrue("내비 명령을 보내지 않는다", rig.navi.commands.isEmpty())
        // 출발하면 다시 묻지 않는다
        rig.drive(30f)
        rig.run(10_000)
        assertFalse(rig.state.prompt?.kind == PromptKind.DEPARTURE)
    }

    @Test
    fun theScreenGetsTheCoachClockByTheMinuteSoDemoHistoryAgesInDemoTime() = runTest {
        // 기록 화면은 이 시각으로 '몇 분 전'을 센다 (HistoryNowTest). 정차 시연은 12:40(가상)이다: 실제 시계로 세면 오후 2시 반에
        // 방금 끝낸 정차가 "1시간 27분 전"이었다 (2026-11-13 영상 녹화)
        val rig = CoreRig(this)
        rig.start()
        rig.command(UiCommand.Demo("stop"))
        rig.run(2_000)
        val clock = checkNotNull(rig.state.clockMinuteMs) { "틱이 돌면 코치 시각이 실린다" }
        assertEquals("분 단위로 내린다 (매초 화면을 다시 그리지 않게)", 0L, clock % 60_000L)
        val local = java.time.Instant.ofEpochMilli(clock).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalTime()
        assertEquals("시연 시각을 싣는다", 12, local.hour)
        assertTrue("12:40 시연 ($local)", local.minute in 40..41)
        // 10배속: 실제 1분이면 가상 10분이 흐른다
        rig.run(60_000)
        val later = java.time.Instant.ofEpochMilli(checkNotNull(rig.state.clockMinuteMs)).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalTime()
        assertTrue("가상 시각으로 흐른다 ($later)", later.minute in 50..51)
    }

    @Test
    fun voiceOffMeansNoSpeechAtAllButTheCardStillShows() = runTest {
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, KID), voiceEnabled = false))
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.runUntil(150_000) { rig.state.prompt?.kind == PromptKind.LAST_CHANCE } ?: error("카드가 뜨지 않았다")
        rig.command(UiCommand.Answer(rig.state.prompt!!.id, true))
        rig.runUntil(30_000) { rig.state.goToResult != null } ?: error("결과 없음")
        assertTrue("음성 끔: 묻지도 (${rig.voice.asked})", rig.voice.asked.isEmpty())
        assertTrue("말하지도 않는다 (${rig.voice.said})", rig.voice.said.isEmpty())
    }

    // ------------------------------------------------------------ 질문 선점

    @Test
    fun theEnginesUrgentAnswerReplacesTheCardWithinOneTick() = runTest {
        // 엔진이 지금 질문보다 앞선 질문(사용자 요청에 대한 답)을 내면 같은 틱에 카드가 바뀐다 (구조 리뷰 D1, B1)
        val rig = CoreRig(this)
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.runUntil(150_000) { rig.state.prompt?.kind == PromptKind.LAST_CHANCE } ?: error("카드가 뜨지 않았다")
        rig.command(UiCommand.Urgent(7L))
        assertEquals("명령에 바로 답한다", 7L, rig.state.urgentRequestId)
        assertEquals(UrgentState.READY, rig.state.urgentState)
        rig.run(1_000) // 엔진에 급해를 알린 틱
        assertEquals("다음 틱에 엔진의 급해 질문이 카드를 밀어낸다", PromptKind.URGENT_RESULT, rig.state.prompt?.kind)
        assertEquals("화면 목록 1등과 엔진 질문이 같은 곳", rig.state.urgent!!.first().stop.id, rig.state.prompt?.stop?.id)
    }

    // ------------------------------------------------------------ 질문 이유 (UI 닫힘 검증 #6)

    @Test
    fun aJamLastChanceAskedWhileTheGaugeIsNowIsStillPublishedAsAJamLastChance() = runTest {
        // 코어는 카드를 띄울 때의 이유를 엔진 추천(next)에서 가져온다. 예전에는 화장실 게이지가 NOW(10분 이하, 또는 누가 말함)면
        // 엔진 추천의 이유가 LAST_CHANCE_*가 아니라 NEXT_NEAREST_RESTROOM이었다(추천은 NOW를 먼저 봤다). 그래서 "앞에 1시간 정체가
        // 있어요 … 정체 전 마지막 화장실이에요"라고 묻는 카드의 promptReasons가 [NEXT_NEAREST_RESTROOM]이고, 화면은 맥락 줄을
        // '가까운 화장실', 칩을 '가장 빨리 가는 곳'으로 그렸다('정체 1시간' 칩이 없다). 가장 급할 때(아이가 화장실을 말했고 다음이
        // 정체 전 마지막) 생겼다. 엔진이 질문의 이유를 추천에 함께 싣는다 (엔진 PromptReasonsTest). 여기서는 화면 상태까지 본다
        val rig = CoreRig(this)
        rig.voice.listens += "아이가 화장실 가고 싶대"
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.runUntil(120_000) { rig.state.ahead.firstOrNull()?.id == "oksan" } ?: error("천안을 지나치지 않았다")
        rig.command(UiCommand.Listen)
        rig.runUntil(5_000) { rig.state.prompt != null } ?: error("답이 없다")
        val p = rig.state.prompt!!
        assertEquals("oksan", p.stop?.id)
        assertTrue("전제: 엔진은 정체 전 마지막 화장실이라고 묻는다 (${p.speech})", "정체 전 마지막 화장실" in p.speech)
        val codes = rig.state.promptReasons.map { it.code }
        assertTrue("카드 이유에 정체 전 마지막이 없다: $codes (화면 맥락 줄이 '가까운 화장실'이 된다)", "LAST_CHANCE_CONGESTION" in codes)
    }

    // ------------------------------------------------------------ 급해

    @Test
    fun urgentReportsLocatingThenResultsAndRefreshesWhileShown() = runTest {
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.drive(30f)
        rig.start()
        rig.command(UiCommand.Urgent(41L))
        assertEquals(41L, rig.state.urgentRequestId)
        assertEquals("위치를 모른다", UrgentState.LOCATING, rig.state.urgentState)
        rig.fix(37.4979, 127.0276, 70f)
        rig.run(1_000)
        assertEquals(UrgentState.READY, rig.state.urgentState)
        val first = rig.state.urgent!!
        assertEquals("가장 가까운 화장실이 1등", "gangnam-wc", first.first().stop.id)
        rig.run(1_000)
        assertEquals("엔진도 급해를 안다: 엔진의 급해 질문", PromptKind.URGENT_RESULT, rig.state.prompt?.kind)

        // 화면에 떠 있는 동안 위치가 바뀌면 10초 안에 다시 찾는다 (UI 감사 #9)
        rig.fix(37.5045, 127.0490, 70f)
        rig.run(UrgentFlow.REFRESH_MS)
        assertTrue("지난 위치의 결과가 그대로가 아니다", rig.state.urgent != first)
    }

    @Test
    fun dismissingWhileLocatingDropsTheLateResultAndSaysNothing() = runTest {
        val rig = CoreRig(this)
        rig.drive(30f)
        rig.start()
        rig.command(UiCommand.Urgent(5L))
        assertEquals(UrgentState.LOCATING, rig.state.urgentState)
        rig.command(UiCommand.DismissUrgent(5L))
        assertEquals(UrgentState.IDLE, rig.state.urgentState)
        rig.fix(37.4979, 127.0276, 70f) // 늦게 온 위치
        rig.run(15_000)
        assertEquals("닫은 요청의 결과는 올리지 않는다", UrgentState.IDLE, rig.state.urgentState)
        assertNull(rig.state.urgent)
        assertTrue("묻지 않는다", rig.voice.asked.isEmpty())
        assertTrue(rig.state.prompt == null || rig.state.prompt!!.kind != PromptKind.URGENT_RESULT)
    }

    @Test
    fun withoutALocationTheUrgentRequestEndsInNoLocation() = runTest {
        val rig = CoreRig(this)
        rig.fakeNavi.faults["currentLocation"] = FakeNaviPort.Fault.THROW // 내비 위치 보충도 안 된다
        rig.drive(30f)
        rig.start()
        rig.command(UiCommand.Urgent(9L))
        rig.run(UrgentFlow.LOCATE_MS + 1_000)
        assertEquals(UrgentState.NO_LOCATION, rig.state.urgentState)
        assertTrue(rig.voice.said.any { "위치" in it })
    }

    @Test
    fun acceptingTheUrgentCardRoutesThereAndClosesTheUrgentScreen() = runTest {
        val rig = CoreRig(this, voice = FakeVoice(answer = { if ("바로 안내할까요" in it) "응" else null }), settings = FakeSettings(listOf(DRIVER)))
        rig.drive(30f)
        rig.fix(37.4979, 127.0276, 70f)
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.Urgent(3L))
        rig.runUntil(20_000) { rig.state.goToResult != null } ?: error("결과 없음: ${rig.log.lines.takeLast(10)}")
        assertEquals(GoToOutcome.ROUTE_STARTED, rig.state.goToResult!!.outcome)
        assertEquals("화면은 같은 번호의 IDLE을 보고 닫는다", 3L, rig.state.urgentRequestId)
        assertEquals(UrgentState.IDLE, rig.state.urgentState)
        // 급해에서 고른 곳(경로 후보가 아니다)은 엔진이 계속 '들르기로 한 곳'으로 추천한다 (UI 감사 #9)
        rig.run(30_000)
        assertEquals("gangnam-wc", rig.state.next?.stop?.id)
    }

    @Test
    fun sayingImUrgentOpensAnUrgentRequestTheScreenCanFollow() = runTest {
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.voice.listens += "화장실 급해"
        rig.drive(30f)
        rig.fix(37.4979, 127.0276, 70f)
        rig.start()
        rig.command(UiCommand.Listen)
        rig.run(2_000)
        assertTrue("코어가 연 요청은 음수 번호 (화면 번호와 겹치지 않는다)", rig.state.urgentRequestId < 0)
        assertEquals(UrgentState.READY, rig.state.urgentState)
    }

    // ------------------------------------------------------------ 피로 시연

    @Test
    fun fatigueDemoSuggestsARestWithinThreeRealMinutes() = runTest {
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.drive(90f)
        rig.start()
        rig.command(UiCommand.Demo("fatigue"))
        val at = rig.runUntil(180_000) { rig.state.prompt?.kind == PromptKind.REST_SUGGESTION }
        assertNotNull("오후 졸음 시연은 실제 3분 안에 휴식을 권한다. 로그: ${rig.log.lines.takeLast(10)}", at)
    }

    // ------------------------------------------------------------ 세계 전환

    @Test
    fun rapidDemoSwitchesEndInOneConsistentWorld() = runTest {
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.drive(100f)
        rig.start()
        rig.run(3_000)
        rig.core.command(UiCommand.Demo("holiday"))
        rig.core.command(UiCommand.Demo(null))
        rig.core.command(UiCommand.Demo("stop"))
        rig.run(2_000)
        assertEquals("stop", rig.state.demo)
        assertEquals("시연 탑승자", listOf("driver", "kid"), rig.state.occupants.map { it.id })
        assertEquals(CandidatePolicy.DEMO, rig.loopInputs.request.policy)
        assertFalse(rig.loopInputs.naviFallback)
        rig.command(UiCommand.Demo(null))
        rig.run(1_000)
        assertNull(rig.state.demo)
        assertEquals("실제 탑승자", listOf("driver"), rig.state.occupants.map { it.id })
        assertEquals(CandidatePolicy.REAL, rig.loopInputs.request.policy)
        assertTrue(rig.loopInputs.naviFallback)
        assertEquals("시연 탑승자를 설정에 쓰지 않았다", 0, rig.settings.occupantWrites)
    }

    @Test
    fun demoOffReturnsToTheRealEngineAndTheRealPosition() = runTest {
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.drive(100f)
        rig.fix(37.40, 127.10, 180f)
        rig.start()
        rig.run(10 * 60_000L)
        val before = checkNotNull(rig.state.fatigue?.minutes)
        rig.command(UiCommand.Demo("holiday"))
        rig.run(30_000)
        assertTrue("시연 위치", rig.loopInputs.request.pos!!.lat < 37.0)
        rig.command(UiCommand.Demo(null))
        rig.run(2_000)
        // 실제 엔진을 옆에 두었다가 되돌린다 (파일에서 연속 주행 0으로 다시 만들면 피로 게이지가 처음으로 돌아갔다, 서비스 감사 D10)
        val after = checkNotNull(rig.state.fatigue?.minutes)
        assertTrue("피로가 이어진다: 시연 전 $before, 뒤 $after", after <= before && after >= before - 3)
        val pos = rig.loopInputs.request.pos!!
        assertEquals("시연을 끄면 실제 위치 (구조 리뷰 D3)", 37.40, pos.lat, 1e-6)
        assertEquals(127.10, pos.lon, 1e-6)
    }

    @Test
    fun aStopInTheStopDemoIsShownButNeverWrittenToHistory() = runTest {
        val rig = CoreRig(this)
        rig.park()
        rig.start()
        rig.command(UiCommand.Demo("stop"))
        rig.run(2_000)
        rig.hub.markDoorOpened()
        rig.run(30_000) // 10배속: 가상 5분
        rig.drive(40f)
        rig.run(3_000)
        assertEquals("화면에는 보인다", 1, rig.state.history.size)
        assertTrue("정차 기록 파일에는 쓰지 않는다 (서비스 감사 D3, UI #15)", rig.appended().isEmpty())
        rig.command(UiCommand.Demo(null))
        rig.run(1_000)
        assertTrue("시연을 끄면 시연 정차도 사라진다", rig.state.history.isEmpty())
        assertTrue(rig.appended().isEmpty())
    }

    @Test
    fun aRealStopIsRecordedAndItsLearningSavedButADemoSavesNothing() = runTest {
        val memory = InMemoryMemory()
        val rig = CoreRig(this, memory = memory, settings = FakeSettings(listOf(DRIVER)))
        rig.drive(100f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(30 * 60_000L)
        rig.park()
        rig.run(10_000)
        rig.hub.markDoorOpened()
        rig.run(4 * 60_000L)
        rig.drive(50f)
        rig.run(70_000)
        assertEquals("실제 정차는 저장한다", 1, rig.appended().size)
        assertTrue("화장실 정차", rig.appended().single().restroom)
        assertTrue("엔진이 배운 화장실 간격을 저장했다", memory.stored.restroomIntervalsMin.isNotEmpty())

        rig.command(UiCommand.Demo("stop"))
        val atDemo = memory.saves.size
        rig.park()
        rig.run(2_000)
        rig.hub.markDoorOpened()
        rig.run(60_000)
        rig.drive(40f)
        rig.run(120_000)
        assertEquals("시연 중에는 학습을 저장하지 않는다", atDemo, memory.saves.size)
        assertEquals("시연 정차는 기록 파일에 없다", 1, rig.appended().size)
    }

    @Test
    fun memoryIsSavedAtMostOnceAMinute() = runTest {
        val elder = Occupant("grandma", OccupantType.ELDERLY, label = "할머니")
        val memory = InMemoryMemory()
        val rig = CoreRig(this, memory = memory, settings = FakeSettings(listOf(DRIVER, KID, elder)))
        rig.drive(100f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(5_000)
        // 학습이 바뀌는 사건 둘을 몇 초 사이에 만든다: 아이와 할머니가 차례로 화장실을 말한다 (엔진이 각자의 간격을 배운다)
        rig.voice.listens += listOf("아이가 화장실 가고 싶대", "할머니가 화장실 가고 싶으시대")
        rig.command(UiCommand.Listen)
        // 엔진이 답을 말하는 동안 다시 누르면 멈추기다 (마이크 토글). 말이 끝난 뒤 누른다
        rig.run(10_000)
        rig.command(UiCommand.Listen)
        rig.run(10_000)
        assertEquals("첫 변화는 바로 저장", 1, rig.timedMemory.saveTimes.size)
        rig.run(2 * 60_000L)
        val times = rig.timedMemory.saveTimes
        assertEquals("두 번째 변화는 1분을 기다려 저장 ($times)", 2, times.size)
        assertTrue("저장 간격 ${times[1] - times[0]}ms", times[1] - times[0] >= MemorySaver.EVERY_MS)
        assertEquals("두 사람의 간격이 모두 저장됐다", setOf("kid", "grandma"), memory.stored.restroomIntervalsMin.keys)
    }

    @Test
    fun clearRecordsEmptiesHistoryAndLearningAndNothingResurrectsThem() = runTest {
        val old = StopLog(start = 1_762_900_000_000L, end = 1_762_900_600_000L, lat = null, lon = null, restroom = true, name = "옥산휴게소")
        val learned = PersonalMemory(restroomIntervalsMin = mapOf("driver" to listOf(150.0)))
        val memory = InMemoryMemory(learned)
        val rig = CoreRig(this, memory = memory, history = PromptFakes.History(listOf(old)), settings = FakeSettings(listOf(DRIVER)))
        rig.drive(100f)
        rig.start()
        rig.run(2_000)
        assertEquals(1, rig.state.history.size)
        rig.command(UiCommand.ClearRecords)
        assertEquals(1, rig.history.clears)
        assertTrue(rig.state.history.isEmpty())
        assertEquals(PersonalMemory(), memory.stored)
        rig.run(3 * 60_000L)
        assertTrue("지운 학습이 자동 저장으로 돌아오지 않는다", memory.stored.restroomIntervalsMin["driver"].orEmpty().none { it == 150.0 })
    }

    @Test
    fun settingsAndOccupantChangesDuringADemoKeepTheDemoTrip() = runTest {
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.run(20_000)
        val kidBefore = checkNotNull(rig.state.restroom?.minutes)
        rig.settings.sleepHours = 5f
        rig.command(UiCommand.ProfileChanged)
        rig.command(UiCommand.SetOccupants(listOf(Occupant("driver", OccupantType.ADULT), Occupant("kid", OccupantType.CHILD, ageYears = 7, label = "7살 아이"))))
        rig.run(2_000)
        assertEquals("holiday", rig.state.demo)
        val kidAfter = checkNotNull(rig.state.restroom?.minutes)
        // 실제 여정으로 프로필을 만들면 시연이 새 여정이 되어 화장실 게이지가 처음부터 다시 셌다 (서비스 감사 D4)
        assertTrue("시연 여정이 이어진다: $kidBefore → $kidAfter", kidAfter <= kidBefore)
        assertEquals("시연 탑승자는 설정에 쓰지 않는다", 0, rig.settings.occupantWrites)
    }

    @Test
    fun healthTypesNeedConsentWhenSettingOccupants() = runTest {
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER), healthConsent = false))
        rig.start()
        rig.command(UiCommand.SetOccupants(listOf(DRIVER, Occupant("p", OccupantType.PREGNANT))))
        assertEquals(listOf("driver"), rig.state.occupants.map { it.id })
        rig.settings.healthConsent = true
        rig.command(UiCommand.SetOccupants(listOf(DRIVER, Occupant("p", OccupantType.PREGNANT))))
        assertEquals(listOf("driver", "p"), rig.state.occupants.map { it.id })
    }

    @Test
    fun withdrawingHealthConsentDuringADemoAlsoStopsUsingHealthOccupantsOfTheRealTrip() = runTest {
        // 건강 정보 동의는 세계와 무관한 설정 하나인데 탑승자는 세계마다 따로다. 시연 중에는 화면의 탑승자가 시연 탑승자라 동의 스위치를
        // 꺼도 SetOccupants 없이 동의만 꺼진다. 예전에는 시연을 끄면 실제 탑승자(임신부)가 동의 없이 그대로 돌아와 화장실 게이지의
        // 기준이 됐다 (UI 닫힘 검증 N3). 코어가 동의가 꺼진 것을 보면 모든 세계와 설정에서 건강 유형 탑승자를 뺀다
        val pregnant = Occupant("p", OccupantType.PREGNANT, label = "임신부")
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, pregnant), healthConsent = true))
        rig.park()
        rig.start()
        rig.run(2_000)
        assertEquals("전제: 동의한 실제 탑승자", listOf("driver", "p"), rig.state.occupants.map { it.id })
        rig.command(UiCommand.Demo("holiday"))
        rig.run(2_000)
        rig.settings.healthConsent = false // 화면: prefs.updateHealthConsent(false)
        rig.run(2_000)
        assertEquals("시연 탑승자는 그대로", listOf("driver", "kid"), rig.state.occupants.map { it.id })
        assertEquals("설정에서도 뺀다", listOf("driver"), rig.settings.occupants.map { it.id })
        rig.command(UiCommand.Demo(null))
        rig.run(2_000)
        assertTrue("동의를 철회했는데 건강 유형 탑승자를 쓴다: ${rig.state.occupants.map { it.type }}", rig.state.occupants.none { HealthConsent.isHealth(it.type) })
        assertTrue("화장실 게이지의 기준이 동의 없는 건강 유형 탑승자다", rig.state.restroom?.drivenBy != "p")
    }

    @Test
    fun withdrawingHealthConsentInTheRealWorldRemovesTheOccupantAtOnce() = runTest {
        val pregnant = Occupant("p", OccupantType.PREGNANT, label = "임신부")
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, KID, pregnant), healthConsent = true))
        rig.drive(60f)
        rig.start()
        rig.run(2_000)
        rig.settings.healthConsent = false
        rig.run(1_000)
        assertEquals(listOf("driver", "kid"), rig.state.occupants.map { it.id })
        assertEquals(listOf("driver", "kid"), rig.settings.occupants.map { it.id })
        // 다시 동의해도 뺀 사람이 저절로 돌아오지 않는다
        rig.settings.healthConsent = true
        rig.run(2_000)
        assertEquals(listOf("driver", "kid"), rig.state.occupants.map { it.id })
    }

    @Test
    fun healthOccupantsStoredWithoutConsentAreRemovedAtStart() = runTest {
        // 서비스가 꺼져 있는 동안 동의를 거뒀다 (또는 예전 판이 동의 없이 저장했다)
        val oab = Occupant("o", OccupantType.OAB, label = "과민성 방광")
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, oab), healthConsent = false))
        rig.park()
        rig.start()
        assertEquals(listOf("driver"), rig.state.occupants.map { it.id })
        assertEquals(listOf("driver"), rig.settings.occupants.map { it.id })
    }

    // ------------------------------------------------------------ 재시작

    @Test
    fun aRestartWhileParkedResumesTheParkAndRecordsItFromTheOriginalStart() = runTest {
        val wall = 1_762_909_200_000L
        val parkedSince = wall - 5 * 60_000L
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)), wallBase = wall)
        rig.savedTrip(
            TripHeartbeat(
                savedAt = wall - 60_000L, tripStart = wall - 90 * 60_000L, lastRestroomAt = mapOf("driver" to wall - 90 * 60_000L),
                restroomFallbackAt = wall - 90 * 60_000L, continuousDrivingMin = 85.0, park = ParkState(parkedSince, doorSeen = true),
            ),
        )
        rig.park()
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(3_000)
        rig.drive(50f)
        rig.run(3_000)
        val log = rig.appended().single()
        assertEquals("재시작 전 정차 시작부터 (서비스 감사 D12)", parkedSince, log.start)
        assertTrue("재시작 전 문 열림", log.restroom)
    }

    // ------------------------------------------------------------ 환기

    @Test
    fun autoFreshAirIsGatedTenRealMinutesAndRevertedAfterNinetySeconds() = runTest {
        // 귀성길 시연(어른+아이, 7.2배속): 내기순환 30분(가상) = 실제 250초면 엔진이 환기를 낸다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, KID), autoFreshAir = true))
        rig.hub.hvacOn = true
        rig.hub.recirculation = true
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.run(1_100_000L)
        val on = rig.cabin.freshAirAt
        val off = rig.cabin.recirculateAt
        assertTrue("자동 환기가 있었다", on.isNotEmpty())
        on.zipWithNext().forEach { (a, b) -> assertTrue("자동 환기 간격 ${b - a}ms ≥ 10분 (실제 시간)", b - a >= FreshAirGate.GAP_MS) }
        assertEquals("바꾼 만큼 되돌린다", on.size, off.size)
        on.zip(off).forEach { (a, b) -> assertEquals("1분 30초 뒤 내기로", FreshAirGate.REVERT_MS.toDouble(), (b - a).toDouble(), 1_500.0) }
        assertTrue("두 번째 환기가 있었다 (문이 열린 뒤)", on.size >= 2)
    }

    // ------------------------------------------------------------ 멈춘 바깥

    @Test
    fun aHungVoiceAndAHungNaviNeverStallTheTick() = runTest {
        val rig = CoreRig(this)
        rig.voice.hang = true
        rig.fakeNavi.faults["routeStatus"] = FakeNaviPort.Fault.HANG
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.runUntil(150_000) { rig.state.prompt?.kind == PromptKind.LAST_CHANCE } ?: error("카드가 뜨지 않았다")
        rig.command(UiCommand.Answer(rig.state.prompt!!.id, true)) // 내비 명령이 멈춘다
        val remain = mutableListOf<Double>()
        repeat(20) {
            rig.run(1_000)
            remain += checkNotNull(rig.state.remainDistanceM)
        }
        remain.zipWithNext().forEach { (a, b) -> assertTrue("틱이 멈추지 않고 매초 줄어든다", b < a) }
        // 멈춘 들르기는 감시가 끝낸다 (GoToRunner 130초 상한)
        rig.runUntil(150_000) { rig.state.goToResult != null } ?: error("멈춘 들르기가 끝나지 않았다")
        assertEquals(GoToOutcome.FAILED, rig.state.goToResult!!.outcome)
    }

    // ------------------------------------------------------------ 계획·들르기 결과 (UI 계약)

    @Test
    fun thePlanGenerationRisesOnlyWhenAPlanIsRequested() = runTest {
        // 화면은 번호가 오르면 '계획을 세우는 중'을 끈다. 떠 있는 동안 고쳐 내는 계획으로 번호가 오르면 요청이 답을 받은 것처럼 보인다
        val rig = CoreRig(this)
        rig.drive(100f)
        rig.start()
        assertEquals("요청 전에는 번호가 없다", 0L, rig.state.planGeneration)
        rig.command(UiCommand.MakePlan) // 첫 틱 전 요청도 답한다
        rig.command(UiCommand.Demo("holiday"))
        rig.run(2_000)
        val first = rig.state.planGeneration
        assertTrue("요청에 답하면 번호가 생긴다 (0은 번호를 쓰지 않는 코치)", first > 0L)
        assertNotNull(rig.state.plan)
        rig.run(60_000) // 후보가 여러 번 바뀌어 계획을 다시 만든다
        assertEquals("고쳐 낸 계획은 번호를 올리지 않는다", first, rig.state.planGeneration)
        rig.command(UiCommand.MakePlan)
        assertEquals("새 요청마다 1 오른다", first + 1, rig.state.planGeneration)
    }

    @Test
    fun aClosedPlanIsNotRebuiltUntilItIsRequestedAgain() = runTest {
        // 계획 화면을 떠났다는 신호가 없어서 MakePlan 한 번에 10분 동안 계획을 30번 다시 세웠다 (리뷰 R-F12).
        // 화면이 ClosePlan을 보내면 멈춘다. 마지막 계획은 화면 상태에 남는다 (주행 화면의 '다음 계획' 카드)
        val rig = CoreRig(this)
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.run(3_000)
        rig.command(UiCommand.MakePlan)
        rig.run(1_000)
        val shown = checkNotNull(rig.state.plan)
        val gen = rig.state.planGeneration
        rig.command(UiCommand.ClosePlan)
        var rebuilt = 0
        repeat(600) {
            rig.run(1_000)
            if (rig.state.plan !== shown) rebuilt++
        }
        assertEquals("계획 화면을 떠난 뒤 10분 동안 다시 세운 횟수", 0, rebuilt)
        assertEquals(gen, rig.state.planGeneration)
        rig.command(UiCommand.MakePlan)
        assertTrue("다시 요청하면 지금 위치로 다시 세운다", rig.state.plan !== shown)
        assertEquals(gen + 1, rig.state.planGeneration)
    }

    @Test
    fun aRestartedCoreNeverRepeatsAPlanGeneration() = runTest {
        // 같은 프로세스에서 서비스만 다시 뜨면 화면 상태에 죽은 코어의 번호가 남는다. 새 코어가 같은 번호부터 세면 화면의
        // '계획을 세우는 중'이 풀리지 않는다 (서비스 닫힘 검증 N8, UI 닫힘 검증 N6)
        val seen = ArrayList<Long>()
        repeat(2) {
            val rig = CoreRig(this)
            rig.drive(100f)
            rig.start()
            rig.command(UiCommand.Demo("holiday"))
            rig.run(2_000)
            rig.command(UiCommand.MakePlan)
            rig.run(1_000)
            seen += rig.state.planGeneration
        }
        assertTrue("다시 뜬 코어의 첫 계획 번호가 앞 코어의 번호와 겹친다: $seen", seen[1] > seen[0] && seen[0] > 0L)
    }

    @Test
    fun goToResultsHaveDistinctTimestampsAndExpire() = runTest {
        // 화면은 at으로 새 결과를 가린다. 같은 순간에 끝난 두 들르기가 같은 at이면 두 번째 결과를 못 본다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.drive(100f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(2_000)
        val seen = mutableListOf<Long>()
        rig.command(UiCommand.GoTo(Gyeongbu.oksan))
        rig.state.goToResult?.let { seen += it.at }
        rig.command(UiCommand.GoTo(Gyeongbu.jukam))
        rig.state.goToResult?.let { seen += it.at }
        rig.run(10_000)
        rig.state.goToResult?.let { seen += it.at }
        assertEquals("두 결과 (${rig.navi.commands})", 2, seen.distinct().size)
        rig.run(PromptMachine.GO_TO_RESULT_TTL_MS)
        assertNull("오래된 결과는 거둔다", rig.state.goToResult)
    }
}
