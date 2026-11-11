package dev.shimpyo.app.coach

import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.WaypointInfo
import dev.hmg.pleoskit.NaviKit
import dev.shimpyo.engine.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** pleos-kit 내비 확인 규칙과 앱의 기어 변환 (안드로이드 없이 도는 순수 함수) */
class KitRulesTest {
    private fun wp(lat: Double, lon: Double, name: String = "wp") =
        WaypointInfo(name = name, address = "", latitude = lat, longitude = lon, remainDistance = 0, remainTime = 0, poiId = "", poiSubId = null)

    @Test
    fun simulationGuidingHasARouteToo() {
        // 모의 주행 중 길안내 시작을 실패로 알리던 것 (구조 리뷰 D12, B8)
        assertTrue(NaviKit.hasRoute(NaviMode.ROUTE_GUIDING))
        assertTrue(NaviKit.hasRoute(NaviMode.SIMULATION_GUIDING))
        assertFalse(NaviKit.hasRoute(NaviMode.SAFE_GUIDING))
        assertFalse(NaviKit.hasRoute(NaviMode.NOT_WORKING))
    }

    @Test
    fun aWaypointCountsAsAddedOnlyIfTheListGrewOrTheRequestedPlaceIsInIt() {
        val oksan = wp(36.657743, 127.369817, "옥산")
        val other = wp(36.0, 127.0, "다른 곳")
        assertTrue("개수가 늘었다", NaviKit.waypointAdded(listOf(other), listOf(other, oksan), oksan.latitude, oksan.longitude))
        assertFalse("개수가 그대로", NaviKit.waypointAdded(listOf(other), listOf(other), oksan.latitude, oksan.longitude))
        // 보내기 전 목록을 못 읽었다: 예전에는 0개로 보고 원래 있던 경유지만으로 성공이라 했다 (서비스 감사 P11)
        assertFalse("원래 있던 다른 경유지만 있다", NaviKit.waypointAdded(null, listOf(other), oksan.latitude, oksan.longitude))
        assertTrue("요청한 곳이 들어 있다 (도로 위로 옮겨 붙어도)", NaviKit.waypointAdded(null, listOf(other, wp(36.6585, 127.3700)), oksan.latitude, oksan.longitude))
    }

    @Test
    fun carGearValuesMapToEngineGears() {
        assertEquals(Gear.PARK, CarSignals.gearOf(4))
        assertEquals(Gear.DRIVE, CarSignals.gearOf(8))
        assertEquals(Gear.REVERSE, CarSignals.gearOf(2))
        assertEquals(Gear.NEUTRAL, CarSignals.gearOf(1))
        assertEquals("수동 기어 1단 이상은 주행", Gear.DRIVE, CarSignals.gearOf(32))
        assertEquals(Gear.UNKNOWN, CarSignals.gearOf(null))
        assertEquals(Gear.UNKNOWN, CarSignals.gearOf(0))
    }
}
