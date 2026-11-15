package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.CoreRig
import dev.shimpyo.coach.fakes.Gyeongbu
import dev.shimpyo.engine.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChargeDemoTest {
    @Test fun chargePinsOneCombinedQuestionAndAcceptAddsOksanWaypoint() = runTest {
        // 실제 표의 옥산·죽암에는 충전기가 있다. 기존 holiday fixture는 그대로 두고 이 장면에만 시설을 채운다.
        val rows = Gyeongbu.all.map { c -> if (c.kind == StopKind.HIGHWAY_REST_AREA)
            c.copy(facilities = c.facilities.copy(evCharger = true, fuel = true)) else c }
        val rig = CoreRig(this, stopRows = rows)
        rig.fakeNavi.mode = { NaviMode.ROUTE_GUIDING }
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("charge"))
        assertEquals("charge", rig.state.demo)
        rig.run(3_000)
        assertEquals(EnergyKind.ELECTRIC, rig.state.energy)
        assertTrue("대본의 정체 기준은 명시적으로 알려져 있다", rig.state.congestionBasisKnown)
        val next = checkNotNull(rig.state.next)
        assertEquals("oksan", next.stop.id)
        assertTrue(next.reasons.any { it.code == "RANGE_LAST_CHANCE" })
        rig.runUntil(150_000) { rig.state.prompt?.kind == PromptKind.LAST_CHANCE } ?: error("질문 없음")
        val p = checkNotNull(rig.state.prompt)
        assertEquals("oksan", p.stop?.id)
        assertTrue(p.speech, "화장실도 충전도 되는 마지막 곳" in p.speech)
        assertEquals(listOf("LAST_CHANCE_CONGESTION", "RANGE_LAST_CHANCE"), rig.state.promptReasons.map { it.code })
        assertEquals(setOf(Purpose.RESTROOM, Purpose.FUEL), rig.state.next!!.purposes)
        assertTrue(rig.state.promptStop!!.routeDistanceM!! in 2_000.0..5_000.0)
        assertTrue(rig.state.rangeKm!! in 30.0..40.0)
        val rangeReason = rig.state.promptReasons.single { it.code == "RANGE_LAST_CHANCE" }.text
        val decisionReasons = rig.state.promptReasons
        assertEquals("여기를 지나면 다음 충전할 곳에서 주행가능거리가 11km 남아요 (예비 20km 미만)", rangeReason)
        val leftKm = Regex("주행가능거리가 (\\d+)km 남아요").find(rangeReason)!!.groupValues[1].toInt()
        assertTrue(leftKm < CoachConfig().electricReserveKm)
        rig.command(UiCommand.Answer(p.id, true))
        rig.runUntil(20_000) { rig.state.goToResult != null } ?: error("경유지 결과 없음")
        assertEquals(GoToOutcome.WAYPOINT_ADDED, rig.state.goToResult!!.outcome)
        assertEquals("addWaypoint", rig.navi.commands.single().name)
        assertEquals(Gyeongbu.oksan.lat, rig.navi.commands.single().lat, 1e-9)
        assertEquals(1, rig.voice.asked.size)
        rig.run(2_000)
        val accepted = checkNotNull(rig.state.next)
        assertEquals("oksan", accepted.stop.id)
        assertEquals(setOf(Purpose.RESTROOM, Purpose.FUEL), accepted.purposes)
        assertEquals(listOf("NEXT_ACCEPTED") + decisionReasons.map { it.code }, accepted.reasons.map { it.code })
        assertEquals(decisionReasons, accepted.reasons.drop(1))
    }
}
