package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.CoreRig
import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeNaviPort.Companion.dest
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.FakeVoice
import dev.shimpyo.coach.fakes.KID
import dev.shimpyo.engine.PromptKind
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 일상 확장(출발 전 한마디, 도심 가까운 화장실)의 감사 결과를 서비스 전체 흐름(가짜 포트, 가상 시간)으로 다시 본다 (2026-11-13).
 * 엔진 단위의 규칙은 DepartureEdgeTest·CityNearbyTest, 가까운 후보 목록은 NearbyStopsTest.
 */
class EverydayAuditCoreTest {
    private val wall = 1_762_909_200_000L // 2026-11-12 10:00 KST
    private val min = 60_000L

    /** 길안내 중인 내비: 목적지까지 [remainM], [tripMin]분. 첫 회전이 [turnM] 앞 */
    private fun CoreRig.guiding(remainM: Double, tripMin: Int, turnM: Double? = null, name: String = "고양 킨텍스") {
        fakeNavi.mode = { NaviMode.ROUTE_GUIDING }
        fakeNavi.destination = { dest(remainM, lat = 37.6687, lon = 126.7454, name = name, timeS = tripMin * 60.0) }
        if (turnM != null) fakeNavi.tbt = { listOf(TbtItem(TbtType.OTHER, turnM, "우회전")) }
    }

    // ---------------------------------------------------------------- 출발 전 한마디

    @Test
    fun theFirstTurnRightOutsideTheParkingLotDoesNotSilenceTheDepartureQuestion() = runTest {
        // 감사 1: 실제 길안내는 첫 회전이 대개 500m 안이다. 서 있는 동안 '회전 앞에서는 묻지 않기'에 막혀 거의 묻지 못했다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, KID)), wallBase = wall)
        rig.fix(37.3662, 127.1046, 0f)
        rig.park()
        rig.guiding(48_000.0, 70, turnM = 180.0)
        rig.start()
        rig.runUntil(60_000) { rig.state.prompt?.kind == PromptKind.DEPARTURE } ?: error("출발 전 질문이 없다: ${rig.log.lines.takeLast(8)}")
        assertTrue("카드가 제 이유를 싣는다 (감사 11): ${rig.state.promptReasons}", rig.state.promptReasons.any { it.code == "DEPARTURE_TRIP" })
    }

    @Test
    fun aRestartAtARestAreaDoesNotAskTheDepartureQuestion() = runTest {
        // 감사 3: 130분 달려 휴게소에 섰고(20분 전, 연속 주행은 휴식으로 0) 앱이 다시 떴다. 같은 길안내가 이어진다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, KID)), wallBase = wall)
        rig.savedTrip(
            TripHeartbeat(
                savedAt = wall - 1 * min, tripStart = wall - 150 * min, lastRestroomAt = emptyMap(),
                restroomFallbackAt = wall - 140 * min, continuousDrivingMin = 0.0, park = ParkState(wall - 20 * min, doorSeen = false),
            ),
        )
        rig.fix(36.6577, 127.3698, 180f)
        rig.park()
        rig.guiding(200_000.0, 150)
        rig.start()
        rig.run(90_000)
        assertNotEquals("여정 중간의 정차는 출발 전이 아니다", PromptKind.DEPARTURE, rig.state.prompt?.kind)
    }

    @Test
    fun boardingDoorsAreNotARestroomStopButTheDoorAfterAcceptingIs() = runTest {
        // 감사 2: 시동 뒤 아이가 타며 문을 열고 3분 서 있으면 집 앞이 '화장실 정차'가 되어 기록·여정·학습이 모두 틀어졌다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, KID)), wallBase = wall)
        rig.fix(37.3662, 127.1046, 0f)
        rig.park()
        rig.start()
        rig.run(30_000)
        rig.hub.markDoorOpened()
        rig.run(4 * min)
        val before = checkNotNull(rig.trips.stored).restroomFallbackAt
        assertTrue("탈 때 연 문으로 여정의 마지막 화장실을 옮기지 않는다", rig.trips.stored!!.lastRestroomAt.values.all { it <= before })
        assertFalse("기록에도 화장실 정차가 아니다", rig.state.history.any { it.restroom })
        // 목적지를 정하면 묻고, '다녀올게요' 뒤 문이 열리면 다녀온 것이다
        rig.guiding(48_000.0, 70)
        rig.runUntil(60_000) { rig.state.prompt?.kind == PromptKind.DEPARTURE } ?: error("출발 전 질문이 없다")
        rig.command(UiCommand.Answer(rig.state.prompt!!.id, true))
        rig.run(20_000)
        rig.hub.markDoorOpened()
        rig.run(10_000)
        assertTrue("다녀온 뒤 게이지: ${rig.state.restroom?.minutes}분", rig.state.restroom!!.minutes!! > 70)
        val after = checkNotNull(rig.trips.stored)
        assertTrue("여정도 화장실을 옮겼다", maxOf(after.restroomFallbackAt, after.lastRestroomAt.values.maxOrNull() ?: Long.MIN_VALUE) > before)
    }

    @Test
    fun saying_alreadyWent_onTheDepartureCardClosesItAndResetsTheClock() = runTest {
        // 감사 5: "이미 다녀왔어"를 알아듣지 못해 카드가 시간만 채웠고 게이지는 그대로였다
        val voice = FakeVoice(answer = { if ("출발 전에" in it) "이미 다녀왔어" else null })
        val rig = CoreRig(this, voice = voice, settings = FakeSettings(listOf(DRIVER, KID)), wallBase = wall)
        rig.fix(37.3662, 127.1046, 0f)
        rig.park()
        rig.guiding(48_000.0, 70)
        rig.start()
        rig.runUntil(60_000) { rig.voice.asked.any { "출발 전에" in it } } ?: error("출발 전 질문을 말하지 않았다")
        rig.run(10_000)
        assertNotEquals(PromptKind.DEPARTURE, rig.state.prompt?.kind)
        assertTrue("다녀왔다고 했다: ${rig.state.restroom?.minutes}분", rig.state.restroom!!.minutes!! > 90)
        val stored = checkNotNull(rig.trips.stored)
        assertTrue("여정 기준점도 옮겨 저장했다 (재시작해도 남는다)", (stored.lastRestroomAt.values + stored.restroomFallbackAt).max() > wall - 2 * min)
    }

    // ---------------------------------------------------------------- 도심 가까운 화장실

    @Test
    fun aCityQuestionStaysUpAndIsSpokenOnce() = runTest {
        // 감사 1: 질문 카드가 한 틱 만에 내려가고 30초마다 다시 떠서 TTS가 잘린 문장을 되풀이했다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, KID)), wallBase = wall)
        rig.savedTrip(
            TripHeartbeat(
                savedAt = wall - 10_000, tripStart = wall - 30 * min, lastRestroomAt = emptyMap(),
                restroomFallbackAt = wall - 110 * min, continuousDrivingMin = 30.0, park = null,
            ),
        )
        rig.drive(30f)
        rig.fix(37.4958, 127.0290, 0f)
        rig.start()
        rig.runUntil(60_000) { rig.state.prompt?.stop?.id == "gangnam-wc" } ?: error("가까운 화장실을 묻지 않았다: ${rig.state.prompt?.speech}")
        val id = rig.state.prompt!!.id
        rig.run(10_000)
        assertEquals("10초 뒤에도 같은 카드다", id, rig.state.prompt?.id)
        rig.run(80_000)
        assertEquals("한 번만 말한다: ${rig.voice.asked}", 1, rig.voice.asked.count { "강남역 공중화장실" in it })
    }

    @Test
    fun theHolidayDemoNeverOffersRoadsideStops() = runTest {
        // 감사 8: 귀성길 시연 경로는 시설을 잇는 직선이라 지도 맞춤이 고속도로로 보지 못했고, Virtual Console 시속 60이면 마을 주유소를 냈다
        val rig = CoreRig(this, wallBase = wall)
        rig.drive(60f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        repeat(90) {
            rig.run(1_000)
            assertFalse("귀성길 시연의 다음 화장실이 일상 후보다: ${rig.state.next}", rig.state.next?.reasons.orEmpty().any { it.code == "NEXT_NEARBY_RESTROOM" })
        }
    }

    @Test
    fun aRestartKeepsAnAcceptedCityStop() = runTest {
        // 감사 13: 재시작하면 '들르기로 한 곳'을 경로 후보에서만 찾아, 도심에서 고른 공중화장실은 2분 뒤 잊혔다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, KID)), wallBase = wall)
        rig.savedTrip(
            TripHeartbeat(
                savedAt = wall - 5_000, tripStart = wall - 30 * min, lastRestroomAt = emptyMap(),
                restroomFallbackAt = wall - 60 * min, continuousDrivingMin = 30.0, park = null,
                accepted = AcceptedStop("gangnam-wc", wall - 30_000),
            ),
        )
        rig.drive(30f)
        rig.fix(37.4958, 127.0290, 0f)
        rig.start()
        rig.runUntil(30_000) { rig.state.next?.reasons.orEmpty().any { it.code == "NEXT_ACCEPTED" } } ?: error("들르기로 한 곳을 되살리지 못했다: ${rig.state.next}")
        assertEquals("gangnam-wc", rig.state.next?.stop?.id)
        rig.run(150_000)
        assertNotNull("2분 뒤에도 남는다", rig.state.next?.reasons?.firstOrNull { it.code == "NEXT_ACCEPTED" })
    }

    // ---------------------------------------------------------------- 2026-11-13 검증

    @Test
    fun withoutASpeedSignalTheCoachAndTheEngineAgreeAboutTheRestroomStop() = runTest {
        // 검증 3: 차속 권한이 없거나 Car 서비스가 다시 붙는 중(차속 null). 엔진은 D로 30초 지나면 움직인 것으로 봤지만 코치는 차속으로만
        // 봐서, 엔진은 화장실 정차를 배우고 기록·여정은 '타는 중'으로 문을 세지 않았다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER, KID)), wallBase = wall)
        rig.fix(37.40, 127.10, 0f)
        rig.hub.gear = dev.shimpyo.engine.Gear.DRIVE
        rig.hub.speedKph = null
        rig.start()
        rig.run(10 * min)
        rig.park()
        rig.hub.speedKph = null
        rig.run(20_000)
        rig.hub.markDoorOpened()
        rig.run(4 * min)
        val engineSaysRestroom = rig.state.restroom!!.minutes!! > 90
        val anchor = checkNotNull(rig.trips.stored)
        val anchorMoved = (anchor.lastRestroomAt.values + anchor.restroomFallbackAt).max() > wall
        assertTrue("엔진: 화장실 정차 (${rig.state.restroom?.minutes}분)", engineSaysRestroom)
        assertTrue("여정 기준점도 같은 정차로 옮겼다", anchorMoved)
    }

    @Test
    fun theHolidayDemoParkedWithADoorDoesNotAskTheDepartureQuestion() = runTest {
        // 검증 7: 시연 경로에는 목적지가 없어 남은 거리로 새 목적지를 가렸는데, 시연 차는 P에서도 움직여 남은 거리가 10초에 2km씩 바뀌었다
        val rig = CoreRig(this, wallBase = wall)
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.run(20_000)
        rig.park()
        rig.run(5_000)
        rig.hub.markDoorOpened()
        repeat(40) {
            rig.run(1_000)
            assertNotEquals("귀성길 시연 한가운데서 출발 전 질문: ${rig.state.prompt?.speech}", PromptKind.DEPARTURE, rig.state.prompt?.kind)
        }
    }
}
