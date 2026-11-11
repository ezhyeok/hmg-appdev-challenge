package dev.shimpyo.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 내비 정체 항목 → 엔진의 '정체 구간을 지나는 시간'. NaviHelper의 trafficCongestionTime은 통과 시간이 아니라 분 단위로 반올림한
 * 늘어난 시간이다(최소 1분). 2026-11-12 에뮬레이터 실측(KitNaviPort의 congestion 로그):
 * - 항목: 30~100m 구간이 모두 60초, 평균 속도로 지나는 시간은 1~16초
 * - 요약: 3,200m·45.0km/h → 120초, 3,300m·46.1km/h → 120초, 5,900m·35.2km/h → 300초 (평균 속도로 지나는 시간 256·258·603초의 절반쯤)
 */
class NaviCongestionTest {

    @Test
    fun theCrossingTimeComesFromTheAverageSpeedOfTheMeasuredSummaries() {
        assertEquals(256.0, NaviCongestion.crossingS(3_200.0, 45.0, 120.0)!!, 0.5)
        assertEquals(257.7, NaviCongestion.crossingS(3_300.0, 46.1, 120.0)!!, 0.5)
        assertEquals(606.9, NaviCongestion.crossingS(5_900.0, 35.0, 300.0)!!, 0.5)
    }

    @Test
    fun aShortItemWithTheOneMinuteFloorIsNotASixtySecondCrossing() {
        // 예전에는 60m 구간을 60초 걸리는 정체로 넣었다 (엔진이 정체 뒤 기회의 도착 시간을 늘렸다)
        val s = NaviCongestion.crossingS(60.0, 45.0, 60.0)!!
        assertEquals(4.8, s, 0.1)
    }

    @Test
    fun withoutAUsableSpeedItIsTheFreeFlowTimePlusTheDelay() {
        val free = 60.0 / (NaviCongestion.FREE_FLOW_KPH / 3.6)
        assertEquals(free + 60.0, NaviCongestion.crossingS(60.0, 0.0, 60.0)!!, 1e-9)
        assertEquals(free + 60.0, NaviCongestion.crossingS(60.0, Double.NaN, 60.0)!!, 1e-9)
        assertEquals("1km/h 미만은 쓸 수 없는 속도", free + 60.0, NaviCongestion.crossingS(60.0, 0.5, 60.0)!!, 1e-9)
        assertEquals("늘어난 시간도 모르면 자유 흐름 시간", free, NaviCongestion.crossingS(60.0, null, Double.NaN)!!, 1e-9)
        assertEquals("음수 늘어난 시간은 0", free, NaviCongestion.crossingS(60.0, null, -30.0)!!, 1e-9)
    }

    @Test
    fun aTwentyKilometreJamAtTwentyKmPerHourTakesAnHour() {
        assertEquals(3_600.0, NaviCongestion.crossingS(20_000.0, 20.0, 1_800.0)!!, 1e-6)
    }

    @Test
    fun brokenLengthsAreDroppedAndAbsurdTimesCapped() {
        assertNull(NaviCongestion.crossingS(Double.NaN, 40.0, 60.0))
        assertNull(NaviCongestion.crossingS(0.0, 40.0, 60.0))
        assertNull(NaviCongestion.crossingS(-100.0, 40.0, 60.0))
        assertNull(NaviCongestion.crossingS(Double.POSITIVE_INFINITY, 40.0, 60.0))
        assertEquals(NaviCongestion.MAX_CROSSING_S, NaviCongestion.crossingS(200_000.0, 1.0, 60.0)!!, 0.0)
    }

    @Test
    fun theFactoryKeepsTheRawDelayAndSpeedAndDropsBrokenItems() {
        val c = checkNotNull(NaviCongestion.fromNavi(400.0, 100.0, delayS = 60.0, avrSpeedKph = 38.3, type = "HEAVY", roadName = "경부고속도로", startName = "판교JC", endName = "신갈JC"))
        assertEquals(9.4, c.durationS, 0.1)
        assertEquals(60.0, c.delayS!!, 0.0)
        assertEquals(38.3, c.avrSpeedKph!!, 0.0)
        assertEquals(400.0, c.offsetM, 0.0)
        assertNull(NaviCongestion.fromNavi(400.0, 0.0, 60.0, 38.3, "HEAVY", "", "", ""))
        assertNull("위치를 모르는 항목", NaviCongestion.fromNavi(Double.NaN, 100.0, 60.0, 38.3, "HEAVY", "", "", ""))
        assertTrue(NaviCongestion.FREE_FLOW_KPH in 70.0..90.0)
    }
}
