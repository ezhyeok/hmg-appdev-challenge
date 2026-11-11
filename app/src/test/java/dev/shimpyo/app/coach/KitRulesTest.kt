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

    /** Pleos 내비가 돌려주는 경유지: 좌표는 0.0이고 이름은 보낸 poiName이다 (2026-11-12 에뮬레이터) */
    private fun navi(name: String, remainM: Int = 47_000) =
        WaypointInfo(name = name, address = "안성시 원곡면", latitude = 0.0, longitude = 0.0, remainDistance = remainM, remainTime = 2_220, poiId = "", poiSubId = null)

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
    fun aPlaceAlreadyInTheWaypointsIsRecognisedSoItIsNotSentAgain() {
        // 같은 곳을 또 보내면 목록이 늘지 않아 확인 실패(FAILED)가 됐다 (2026-11-12 에뮬레이터, 급해 결과를 두 번 고름)
        val sk = wp(37.3990, 127.1100, "주유소")
        assertTrue(NaviKit.isWaypoint(listOf(sk), 37.3991, 127.1101))
        assertFalse(NaviKit.isWaypoint(listOf(sk), 37.40, 127.12))
        assertFalse(NaviKit.isWaypoint(emptyList(), 37.3990, 127.1100))
    }

    @Test
    fun theRealNaviReturnsNoCoordinatesSoAWaypointIsRecognisedByItsName() {
        // 기기에서는 경유지 좌표가 0.0으로 온다. 좌표만 보던 '이미 있는 곳' 확인은 한 번도 맞지 않았다 (가짜 좌표로만 시험했다)
        val anseong = navi("안성휴게소(부산방향)")
        assertFalse("좌표로는 알 수 없다", NaviKit.isWaypoint(listOf(anseong), 37.01354, 127.14516))
        assertTrue("보낸 이름으로 안다", NaviKit.isWaypoint(listOf(anseong), 37.01354, 127.14516, "안성휴게소(부산방향)"))
        assertFalse(NaviKit.isWaypoint(listOf(anseong), 36.657743, 127.369817, "옥산휴게소(부산방향)"))
        assertFalse("0,0 좌표는 좌표로 치지 않는다", NaviKit.isWaypoint(listOf(anseong), 0.0, 0.0))
        assertFalse("빈 이름은 이름으로 치지 않는다", NaviKit.isWaypoint(listOf(navi("")), 37.0, 127.0, ""))
        // 들어갔는지 확인: 넣는 동안 목록이 잠깐 비었다가 채워진다
        val oksan = navi("옥산휴게소(부산방향)", 101_000)
        assertFalse(NaviKit.waypointAdded(listOf(anseong), emptyList(), 36.657743, 127.369817, "옥산휴게소(부산방향)"))
        assertTrue(NaviKit.waypointAdded(listOf(anseong), listOf(anseong, oksan), 36.657743, 127.369817, "옥산휴게소(부산방향)"))
        assertTrue("보내기 전 목록을 못 읽어도 이름으로 확인한다", NaviKit.waypointAdded(null, listOf(anseong, oksan), 36.657743, 127.369817, "옥산휴게소(부산방향)"))
        assertFalse(NaviKit.waypointAdded(null, listOf(anseong), 36.657743, 127.369817, "옥산휴게소(부산방향)"))
        assertEquals(2, NaviKit.MAX_WAYPOINTS)
    }

    @Test
    fun aStopBeforeTheExistingWaypointIsNotAppendedBehindIt() {
        // Pleos 내비는 새 경유지를 늘 뒤에 붙인다: 101km 앞 옥산 뒤에 47km 앞 안성을 넣으면 안성이 177km가 된다 (갔다가 돌아온다)
        assertEquals("있던 곳보다 뒤면 붙인다", WaypointOrder.Plan.APPEND, WaypointOrder.plan(47_000.0, 101_000.0, existingKnown = true))
        assertEquals(WaypointOrder.Plan.APPEND, WaypointOrder.plan(47_000.0, 101_000.0, existingKnown = false))
        assertEquals("앞이고 있던 곳의 좌표를 알면 순서를 다시 정한다", WaypointOrder.Plan.INSERT_BEFORE, WaypointOrder.plan(101_000.0, 47_000.0, existingKnown = true))
        assertEquals("앞인데 운전자가 내비에서 넣은 경유지면 넣지 않는다", WaypointOrder.Plan.REFUSE, WaypointOrder.plan(101_000.0, 47_000.0, existingKnown = false))
        assertEquals("거의 같은 자리는 뒤로 본다", WaypointOrder.Plan.APPEND, WaypointOrder.plan(47_000.0, 46_500.0, existingKnown = false))
        assertEquals("재지 못했으면 예전처럼 붙인다", WaypointOrder.Plan.APPEND, WaypointOrder.plan(47_000.0, null, existingKnown = false))
        assertEquals(WaypointOrder.Plan.APPEND, WaypointOrder.plan(47_000.0, Double.NaN, existingKnown = true))
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
