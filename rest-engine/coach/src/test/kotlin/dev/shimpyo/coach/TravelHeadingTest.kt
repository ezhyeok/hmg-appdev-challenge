package dev.shimpyo.coach

import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.stops.StopTable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 진행 방향 (TravelHeading, 닫힘 검증 C1): 움직이는 동안 마지막으로 본 방위를 느리거나 서 있어도 쓴다.
 * 마지막 테스트는 실제 정차 후보 표(stops.bin)와 경부선 재생 위치로 본다.
 */
class TravelHeadingTest {
    private val at = Fix(37.0, 127.0, 161f)

    @Test
    fun unknownUntilTheCarHasMoved() {
        val t = TravelHeading()
        assertNull(t.heading)
        assertNull("P에서 본 방위는 진행 방향이 아니다", t.update(at.copy(heading = 20f), 0f, Gear.PARK))
        assertNull("느리게 움직일 때의 방위도 믿지 않는다", t.update(at.copy(heading = 20f), 5f, Gear.DRIVE))
        assertNull("위치를 모른다", t.update(null, 80f, Gear.DRIVE))
        assertNull("위치에 방위가 없다", t.update(at.copy(heading = null), 80f, Gear.DRIVE))
        assertEquals(161f, t.update(at, 80f, Gear.DRIVE))
    }

    @Test
    fun theLastMovingHeadingIsKeptWhileSlowOrParked() {
        val t = TravelHeading()
        t.update(at, 100f, Gear.DRIVE)
        assertEquals("정체에 들어가 느려져도 진행 방향은 그대로", 161f, t.update(at.copy(heading = 0f), 5f, Gear.DRIVE))
        assertEquals("섰다 (위치 SDK가 0.0을 준다)", 161f, t.update(at.copy(heading = 0f), 0f, Gear.DRIVE))
        assertEquals("P", 161f, t.update(at.copy(heading = 300f), 0f, Gear.PARK))
        assertEquals("위치가 잠깐 끊겨도", 161f, t.update(null, 0f, Gear.PARK))
        assertEquals("다시 움직이면 새 방위", 175f, t.update(at.copy(heading = 175f), 30f, Gear.DRIVE))
    }

    @Test
    fun theBoundaryIsTenKilometresAnHourAndAnUnknownSpeedCountsAsMovingUnlessParked() {
        val t = TravelHeading()
        assertNull(t.update(at, CoreRules.SLOW_KPH - 0.1f, Gear.DRIVE))
        assertEquals(161f, t.update(at, CoreRules.SLOW_KPH, Gear.DRIVE))
        val unknown = TravelHeading()
        assertEquals("차속을 모르고 P가 아니면 위치의 방위를 쓴다 (신호가 오기 전에도 앞쪽을 찾는다)", 161f, unknown.update(at, null, Gear.UNKNOWN))
        val parked = TravelHeading()
        assertNull(parked.update(at, null, Gear.PARK))
        val reverse = TravelHeading()
        assertEquals("후진·중립도 달리는 속도면 방위를 쓴다", 161f, reverse.update(at, 20f, Gear.NEUTRAL))
    }

    // ------------------------------------------------------------ 실제 표로

    private val table: StopTable by lazy { StopTable.decode(File("../../app/src/main/assets/data/stops.bin").readBytes()) }
    private val repo: StopRepository by lazy { StopRepository { table }.also { runBlocking { it.ensureLoaded() } } }
    private val highway = setOf(StopKind.HIGHWAY_REST_AREA, StopKind.DROWSY_SHELTER)

    @Test
    fun aCarCrawlingInAJamIsNotHandedStopsBehindIt() {
        // 예전에는 P이거나 시속 10km 미만이면 방위를 null로 넘겼고, 앞쪽 후보 검색은 방위가 null이면 방향을 하나도 거르지 않았다.
        // 길안내 없이 정체 속을 D로 기어가는 차에게 사방 60km의 가까운 순 6곳이 갔다: 경부선 부산방향 실제 차로 위 39곳에서 앞 6곳 중
        // '같은 길 앞쪽'이 205곳(방위 있음) → 43곳(방위 없음)으로 줄고 191곳이 뒤·반대 차로·다른 길이었다. 내비 보정이 답하지 못하면
        // 그대로 엔진 후보에 남았다 (닫힘 검증 C1, 데이터 감사 #10을 닫은 방식의 부작용)
        val line = TravelHeadingTest::class.java.getResource("/stops/replay_positions.csv")!!.readText().lines().first { it.startsWith("gyeongbu-05,") }.split(',')
        val moving = Fix(line[1].toDouble(), line[2].toDouble(), line[3].toFloat()) // 안성휴게소 7.8km 전, 진행 방위 161도
        val travel = TravelHeading()
        travel.update(moving, 90f, Gear.DRIVE)
        // 정체에 들어섰다: 시속 5km, 위치 SDK의 방위는 믿을 수 없다
        val crawling = moving.copy(heading = 0f)
        val fix = CoreRules.searchFix(crawling, crawling, travel.update(crawling, 5f, Gear.DRIVE))!!
        assertEquals("달리던 방위로 찾는다", moving.heading, fix.heading)
        val top = repo.ahead(fix.lat, fix.lon, fix.heading, 60_000.0, highway).filter { it.facilities.restroom }.take(6)
        val behind = top.filter { Geo.angleDiff(Geo.bearingDeg(moving.lat, moving.lon, it.lat, it.lon), moving.heading!!.toDouble()) > 90.0 }
        assertTrue("전제: 앞쪽 후보가 있다", top.isNotEmpty())
        assertTrue("시속 5km로 가는 차의 앞 6곳 중 뒤에 있는 곳: ${behind.map { it.name }}", behind.isEmpty())
    }
}
