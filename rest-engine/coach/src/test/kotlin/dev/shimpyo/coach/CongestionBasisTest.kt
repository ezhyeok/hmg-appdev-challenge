package dev.shimpyo.coach

import dev.shimpyo.coach.CongestionBasis.Basis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 정체 offset 기준 판정 (데이터 감사 #4). 내비 남은 거리 [remain]과 주행 거리 [odo]를 함께 넣는다.
 * 남은 거리가 1km 줄고 주행 거리도 1km 늘면 '내비가 실제로 1km 나아갔다'.
 */
class CongestionBasisTest {

    private fun jam(offset: Double, start: String = "안성IC", length: Double = 2_000.0) =
        NaviCongestion(offsetM = offset, lengthM = length, durationS = 600.0, type = "JAM", roadName = "경부고속도로", startName = start, endName = "")

    /** 경로 시작 (전체 100km) */
    private fun basis(total: Double = 100_000.0, originKnown: Boolean = true) = CongestionBasis().apply { start(total, originKnown) }

    @Test
    fun offsetsThatShrinkWithTheCarAreFromTheCar() {
        val b = basis()
        b.update(listOf(jam(20_000.0)), remainM = 100_000.0, odometerM = 0.0)
        b.update(listOf(jam(19_000.0)), remainM = 99_000.0, odometerM = 1_000.0)
        assertEquals("한 표로는 정하지 않는다", Basis.UNKNOWN, b.basis)
        b.update(listOf(jam(18_000.0)), remainM = 98_000.0, odometerM = 2_000.0)
        assertEquals(Basis.FROM_CAR, b.basis)
        assertEquals(18_000.0, b.update(listOf(jam(18_000.0)), 98_000.0, 2_000.0).single().startDistanceM, 1e-9)
    }

    @Test
    fun offsetsThatStayPutAreFromTheOriginAndAreConvertedWithTheTotalFromTheStart() {
        val b = basis()
        b.update(listOf(jam(20_000.0)), 100_000.0, 0.0)
        b.update(listOf(jam(20_000.0)), 99_000.0, 1_000.0)
        b.update(listOf(jam(20_050.0)), 98_000.0, 2_000.0)
        assertEquals(Basis.FROM_ORIGIN, b.basis)
        // 출발지에서 20km, 내비가 2km 나아갔으니 차에서 18km
        assertEquals(18_000.0, b.startFromCar(20_000.0, 98_000.0)!!, 1e-9)
        assertEquals(18_000.0, b.update(listOf(jam(20_000.0)), 98_000.0, 2_000.0).single().startDistanceM, 1e-9)
    }

    @Test
    fun theRouteTotalIsTakenAtTheStartNotAtTheFirstJam() {
        // 예전: 전체 거리를 첫 정체를 본 때의 남은 거리로 잡아, 정체 없이 30km 달린 뒤 처음 본 정체가 30km 앞당겨졌다
        val b = basis(total = 100_000.0)
        b.update(emptyList(), 70_000.0, 30_000.0) // 정체 없이 30km
        b.update(listOf(jam(50_000.0)), 70_000.0, 30_000.0)
        b.update(listOf(jam(50_000.0)), 69_000.0, 31_000.0)
        b.update(listOf(jam(50_000.0)), 68_000.0, 32_000.0)
        assertEquals(Basis.FROM_ORIGIN, b.basis)
        assertEquals("출발지에서 50km, 32km 왔으니 18km 앞", 18_000.0, b.startFromCar(50_000.0, 68_000.0)!!, 1e-9)
    }

    @Test
    fun inconsistentChangesDecideNothingAndOffsetsStayAsGiven() {
        val b = basis()
        b.update(listOf(jam(20_000.0)), 100_000.0, 0.0)
        b.update(listOf(jam(19_500.0)), 99_000.0, 1_000.0) // 이동의 절반만 줄었다 (정체가 자랐다)
        b.update(listOf(jam(19_000.0)), 98_000.0, 2_000.0)
        assertEquals(Basis.UNKNOWN, b.basis)
        assertEquals(0 to 0, b.votes)
        assertEquals("판정 전에는 현재 위치 기준으로 본다", 19_000.0, b.update(listOf(jam(19_000.0)), 98_000.0, 2_000.0).single().startDistanceM, 1e-9)
    }

    @Test
    fun startingInsideAJamDoesNotLookLikeFromOrigin() {
        // 예전: 정체 안에서 출발하면 offset이 0 근처에 머물러 1km 뒤 '출발지 기준'으로 굳었고, 뒤의 정체가 모두 밀렸다
        val b = basis()
        b.update(listOf(jam(50.0)), 100_000.0, 0.0)
        b.update(listOf(jam(40.0)), 99_000.0, 1_000.0)
        b.update(listOf(jam(30.0)), 98_000.0, 2_000.0)
        b.update(listOf(jam(20.0)), 97_000.0, 3_000.0)
        assertEquals(Basis.UNKNOWN, b.basis)
        assertEquals(0 to 0, b.votes)
    }

    @Test
    fun movementCountsOnlyWhenTheOdometerAndTheNaviAgree() {
        // 내비가 서 있고(에뮬레이터) 차만 달린다: 내비 기준으로는 움직이지 않았다
        val parkedNavi = basis()
        parkedNavi.update(listOf(jam(20_000.0)), 100_000.0, 0.0)
        parkedNavi.update(listOf(jam(20_000.0)), 100_000.0, 5_000.0)
        assertEquals(0 to 0, parkedNavi.votes)
        // 남은 거리가 줄었는데 주행 거리는 그대로(차속 모름, 경로 재계산): 실제로 나아갔는지 모른다
        val noOdometer = basis()
        noOdometer.update(listOf(jam(20_000.0)), 100_000.0, 0.0)
        noOdometer.update(listOf(jam(20_000.0)), 99_000.0, 0.0)
        noOdometer.update(listOf(jam(20_000.0)), 98_000.0, 0.0)
        assertEquals(0 to 0, noOdometer.votes)
        assertEquals(Basis.UNKNOWN, noOdometer.basis)
    }

    @Test
    fun contradictingVotesNeedALeadOfTwo() {
        val b = basis()
        b.update(listOf(jam(20_000.0), jam(40_000.0, start = "천안IC")), 100_000.0, 0.0)
        // 안성은 줄고(차 기준), 천안은 그대로(출발지 기준): 한 표씩
        b.update(listOf(jam(19_000.0), jam(40_000.0, start = "천안IC")), 99_000.0, 1_000.0)
        assertEquals(1 to 1, b.votes)
        b.update(listOf(jam(18_000.0)), 98_000.0, 2_000.0)
        assertEquals("2 대 1은 아직", Basis.UNKNOWN, b.basis)
        b.update(listOf(jam(17_000.0)), 97_000.0, 3_000.0)
        assertEquals(Basis.FROM_CAR, b.basis)
    }

    @Test
    fun aNewRouteForgetsOldSightingsButKeepsTheDecidedBasis() {
        val b = basis()
        b.update(listOf(jam(20_000.0)), 100_000.0, 0.0)
        // 재탐색: 새 경로에서 같은 이름의 정체가 우연히 같은 offset → 옛 관측과 비교하면 '출발지 기준' 표가 된다
        b.start(99_000.0, originKnown = true)
        b.update(listOf(jam(20_000.0)), 99_000.0, 1_000.0)
        assertEquals("옛 경로의 관측은 지웠다", 0 to 0, b.votes)
        assertEquals("전체 거리는 재탐색 때 다시 잡는다", 99_000.0, b.routeTotalM!!, 0.0)

        // 기준은 내비 API의 성질이라 경로가 바뀌어도 남는다
        b.update(listOf(jam(19_000.0)), 98_000.0, 2_000.0)
        b.update(listOf(jam(18_000.0)), 97_000.0, 3_000.0)
        assertEquals(Basis.FROM_CAR, b.basis)
        b.start(50_000.0, originKnown = true)
        assertEquals(Basis.FROM_CAR, b.basis)
        b.clear()
        assertEquals(Basis.FROM_CAR, b.basis)
        assertNull(b.routeTotalM)
    }

    @Test
    fun fromOriginWithoutKnowingTheOriginCannotPlaceAJam() {
        val b = basis()
        b.update(listOf(jam(20_000.0)), 100_000.0, 0.0)
        b.update(listOf(jam(20_000.0)), 99_000.0, 1_000.0)
        b.update(listOf(jam(20_000.0)), 98_000.0, 2_000.0)
        assertEquals(Basis.FROM_ORIGIN, b.basis)
        // 앱이 길안내 중간에 켜졌다: 출발지에서 얼마나 왔는지 모른다
        b.start(60_000.0, originKnown = false)
        assertNull(b.startFromCar(30_000.0, 60_000.0))
        assertTrue("위치를 정할 수 없는 정체는 엔진에 넣지 않는다", b.update(listOf(jam(30_000.0)), 60_000.0, 3_000.0).isEmpty())
    }

    @Test
    fun aMissingStartRemainIsTakenFromTheFirstKnownRemainOfTheRoute() {
        val b = CongestionBasis()
        b.start(null, originKnown = true) // 길안내 시작 때 목적지 조회가 실패했다
        b.update(emptyList(), remainM = null, odometerM = 0.0)
        b.update(emptyList(), remainM = 80_000.0, odometerM = 100.0)
        assertEquals(80_000.0, b.routeTotalM!!, 0.0)
    }

    @Test
    fun brokenItemsAreDroppedAndPassedJamsFiltered() {
        val b = basis()
        val out = b.update(
            listOf(
                jam(Double.NaN),
                jam(5_000.0, length = Double.POSITIVE_INFINITY),
                jam(-3_000.0, length = 1_000.0), // 이미 지나갔다
                jam(-500.0, length = 1_000.0), // 안에 있다
                NaviCongestion(8_000.0, 1_000.0, Double.NaN, "JAM", "경부고속도로", "오산IC", ""),
            ),
            100_000.0,
            0.0,
        )
        assertEquals(listOf(-500.0, 8_000.0), out.map { it.startDistanceM })
        assertEquals("깨진 소요 시간은 0", 0.0, out.last().durationS, 0.0)
    }
}
