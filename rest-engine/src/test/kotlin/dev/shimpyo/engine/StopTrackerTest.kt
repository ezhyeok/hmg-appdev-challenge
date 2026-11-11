package dev.shimpyo.engine

import dev.shimpyo.engine.EngineMath.NONE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopTrackerTest {
    private val cfg = CoachConfig()

    @Test
    fun restroomStopNeedsDoorAndThreeMinutesAndFiresOnce() {
        val st = StopTracker(cfg)
        st.update(0, Gear.DRIVE, 50f, false)
        st.update(1_000, Gear.PARK, 0f, false) // 정차 시작
        var ev = st.update(61_000, Gear.PARK, 0f, true) // 1분 뒤 문 열림
        assertEquals(NONE, ev.restroomStopAt)
        ev = st.update(1_000 + 3 * MIN - 1, Gear.PARK, 0f, false)
        assertEquals(NONE, ev.restroomStopAt, "3분 전에는 아니다")
        ev = st.update(1_000 + 3 * MIN, Gear.PARK, 0f, false)
        assertEquals(1_000 + 3 * MIN, ev.restroomStopAt)
        ev = st.update(1_000 + 4 * MIN, Gear.PARK, 0f, false)
        assertEquals(NONE, ev.restroomStopAt, "한 정차에 한 번만")
        assertTrue(st.parked)
    }

    @Test
    fun anchorIsDoorTimeWhenDoorOpensLate() {
        val st = StopTracker(cfg)
        st.update(0, Gear.PARK, 0f, false)
        val ev = st.update(5 * MIN, Gear.PARK, 0f, true)
        assertEquals(5 * MIN, ev.restroomStopAt, "문이 5분에 열렸으면 그 시각")
    }

    @Test
    fun parkWithoutDoorIsRestAfterFifteenMinutesButNotRestroomStop() {
        assertEquals(15, cfg.restMinParkedMin, "휴식 완료 = P 15분 (ai-science.md 9-2)")
        val st = StopTracker(cfg)
        st.update(0, Gear.DRIVE, 80f, false)
        st.update(SEC, Gear.PARK, 0f, false)
        var rest = NONE
        var restroom = NONE
        var t = SEC
        while (t <= SEC + 16 * MIN) {
            val ev = st.update(t, Gear.PARK, 0f, false)
            if (ev.restAt != NONE) rest = ev.restAt
            if (ev.restroomStopAt != NONE) restroom = ev.restroomStopAt
            t += 10 * SEC
        }
        assertEquals(SEC + 15 * MIN, rest)
        assertEquals(NONE, restroom)
    }

    @Test
    fun shortStopWithDoorDoesNothing() {
        val st = StopTracker(cfg)
        st.update(0, Gear.PARK, 0f, true)
        st.update(MIN, Gear.PARK, 0f, false)
        val ev = st.update(2 * MIN, Gear.DRIVE, 5f, false)
        assertEquals(NONE, ev.restroomStopAt)
        assertEquals(NONE, ev.restAt)
        assertFalse(st.parked)
    }

    @Test
    fun missedUpdatesStillFireOnDeparture() {
        val st = StopTracker(cfg)
        st.update(0, Gear.DRIVE, 80f, false)
        st.update(SEC, Gear.PARK, 0f, false)
        // 앱이 16분 동안 update를 못 하다가 D로 출발하는 update에 문 열림이 함께 왔다
        val ev = st.update(SEC + 16 * MIN, Gear.DRIVE, 10f, true)
        assertEquals(SEC + 15 * MIN, ev.restAt)
        assertEquals(SEC + 16 * MIN, ev.restroomStopAt)
        assertFalse(st.parked)
    }

    @Test
    fun unknownGearHoldsLastStateUnlessMoving() {
        val st = StopTracker(cfg)
        st.update(0, Gear.UNKNOWN, null, false)
        assertFalse(st.parked, "처음부터 모르면 정차로 단정하지 않는다")
        st.update(SEC, Gear.PARK, 0f, false)
        st.update(2 * SEC, Gear.UNKNOWN, 0f, true)
        assertTrue(st.parked, "P 다음 UNKNOWN + 정지 → P 유지")
        val ev = st.update(SEC + 3 * MIN, Gear.UNKNOWN, null, false)
        assertTrue(ev.restroomStopAt != NONE, "UNKNOWN 동안에도 정차 시간이 이어진다")
        st.update(SEC + 4 * MIN, Gear.UNKNOWN, 40f, false)
        assertFalse(st.parked, "차속이 있으면 주행")
        st.update(SEC + 5 * MIN, Gear.UNKNOWN, 0f, false)
        assertFalse(st.parked, "주행 다음 UNKNOWN + 정지 → 주행 유지 (신호 대기)")
    }

    @Test
    fun parkedForMsReportsDuration() {
        val st = StopTracker(cfg)
        st.update(10 * SEC, Gear.PARK, 0f, false)
        st.update(70 * SEC, Gear.PARK, 0f, false)
        assertEquals(60 * SEC, st.parkedForMs(70 * SEC))
        st.update(80 * SEC, Gear.DRIVE, 10f, false)
        assertEquals(0L, st.parkedForMs(80 * SEC))
    }
}
