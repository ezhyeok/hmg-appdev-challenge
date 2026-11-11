package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.PromptFakes
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 정차 기록 (stops.json + 휴식 기록 화면). 지키는 것: 좌표를 남기지 않는다(개인정보), 300m 안 알려진 곳 이름만, 저장은 세계가
 * 허락할 때만(시연 정차가 실제 기록에 미래 시각으로 들어가던 것, 서비스 감사 D3·UI 감사 #15), 시계 전환 때 가짜 정차를 만들지 않는다.
 */
class StopRecorderTest {
    private val min = 60_000L
    private val t0 = 1_000_000_000_000L

    // 옥산휴게소 근처 (위도 0.001도 ≈ 111m)
    private val oksan = PromptFakes.stop("oksan", "옥산휴게소", lat = 36.7000, lon = 127.4000)
    private val atRestArea = Fix(36.7020, 127.4000, null)      // 약 222m
    private val outside = Fix(36.7030, 127.4000, null)         // 약 333m
    private val stops = PromptFakes.Stops(listOf(oksan))

    private fun recorder(history: PromptFakes.History = PromptFakes.History()) =
        StopRecorder(StopDetector(), history, stops)

    private fun StopRecorder.stopFor(minutes: Int, door: Boolean, pos: Fix?, persist: Boolean, at: Long = t0): StopRecorder.Recorded? {
        onTick(at, Gear.PARK, 0f, doorOpened = false, pos = pos, persist = persist)
        onTick(at + min, Gear.PARK, 0f, doorOpened = door, pos = pos, persist = persist)
        return onTick(at + minutes * min, Gear.DRIVE, 5f, doorOpened = false, pos = pos, persist = persist)
    }

    @Test
    fun recordsThroughTheHistoryPortAndReturnsWhatToShow() {
        val h = PromptFakes.History(listOf(StopLog(t0 - 300 * min, t0 - 290 * min, null, null, restroom = false)))
        val r = recorder(h)
        r.load(h.load())
        val rec = r.stopFor(5, door = true, pos = atRestArea, persist = true)!!
        val expected = StopLog(start = t0, end = t0 + 5 * min, lat = null, lon = null, restroom = true, name = "옥산휴게소")
        assertEquals(expected, rec.log)
        assertTrue(rec.persisted)
        assertEquals(listOf(expected), h.appended)
        assertEquals(2, rec.history.size)
        assertEquals(rec.history, r.shown)
    }

    @Test
    fun neverStoresCoordinates() {
        val h = PromptFakes.History()
        val rec = recorder(h).stopFor(5, door = false, pos = outside, persist = true)!!
        assertNull(rec.log.lat)
        assertNull(rec.log.lon)
        assertNull("300m 밖이면 이름도 남기지 않는다", rec.log.name)
        assertEquals("반경 300m로 찾는다", listOf(300.0), stops.nearRadii.distinct())
    }

    @Test
    fun noPositionMeansNoName() {
        assertNull(recorder().stopFor(5, door = true, pos = null, persist = true)!!.log.name)
    }

    @Test
    fun namesThePlaceWhereTheCarStoodNotWhereItDeparted() {
        val r = recorder()
        r.onTick(t0, Gear.PARK, 0f, false, pos = atRestArea, persist = true)
        r.onTick(t0 + min, Gear.PARK, 0f, false, pos = null, persist = true)
        // 출발 틱의 위치는 이미 멀어졌거나 없을 수 있다
        val rec = r.onTick(t0 + 4 * min, Gear.DRIVE, 30f, false, pos = Fix(37.0, 127.0, null), persist = true)!!
        assertEquals("옥산휴게소", rec.log.name)
    }

    @Test
    fun aWorldThatDoesNotPersistKeepsStopsInMemoryOnly() {
        val h = PromptFakes.History(listOf(StopLog(t0 - 300 * min, t0 - 290 * min, null, null, restroom = false)))
        val r = recorder(h)
        r.load(h.load())
        val demoNow = t0 + 4 * 60 * min
        val rec = r.stopFor(20, door = true, pos = atRestArea, persist = false, at = demoNow)!!
        assertFalse(rec.persisted)
        assertTrue("stops.json에 시연 시각이 들어가지 않는다", h.appended.isEmpty())
        assertEquals("화면에는 보인다", 2, rec.history.size)
        assertEquals(rec.log, rec.history.last())
    }

    @Test
    fun switchingWorldsDropsTheUnsavedStopsAndRebasesAParkedCar() {
        val h = PromptFakes.History()
        val r = recorder(h)
        val demoNow = t0 + 4 * 60 * min
        r.stopFor(20, door = true, pos = atRestArea, persist = false, at = demoNow)
        // 시연 중 P로 세운 채 시연을 끈다
        r.onTick(demoNow + 30 * min, Gear.PARK, 0f, doorOpened = true, pos = atRestArea, persist = false)
        r.onWorldChanged(now = t0)
        assertTrue("시연 정차는 실제 기록 화면에서 사라진다", r.shown.isEmpty())
        assertEquals(ParkState(t0, doorAtMs = null), r.parkState)
        assertNull(
            "시연 시각에서 실제 시각을 빼 정차를 만들지 않는다 (새 시계로 1분)",
            r.onTick(t0 + min, Gear.DRIVE, 5f, false, pos = atRestArea, persist = true),
        )
        assertTrue(h.appended.isEmpty())
    }

    @Test
    fun aFailedSaveStillShowsTheStop() {
        val h = PromptFakes.History().apply { failAppend = true }
        val r = recorder(h)
        val rec = r.stopFor(5, door = true, pos = atRestArea, persist = true)!!
        assertFalse(rec.persisted)
        assertEquals(listOf(rec.log), r.shown)
    }

    @Test
    fun clearEmptiesStoreAndScreenButKeepsTheStopInProgress() {
        val h = PromptFakes.History(listOf(StopLog(t0 - 300 * min, t0 - 290 * min, null, null, restroom = true)))
        val r = recorder(h)
        r.load(h.load())
        r.onTick(t0, Gear.PARK, 0f, doorOpened = true, pos = atRestArea, persist = true)
        r.clear()
        assertEquals(1, h.clears)
        assertTrue(r.shown.isEmpty())
        assertEquals("지금 서 있는 것은 기록이 아니라 차의 상태다", ParkState(t0, t0), r.parkState)
    }

    @Test
    fun restartWhileParkedRecordsTheWholeStop() {
        val h = PromptFakes.History()
        val r = recorder(h)
        r.resume(ParkState(t0, doorAtMs = t0 + min), now = t0 + 4 * min)
        val rec = r.onTick(t0 + 6 * min, Gear.DRIVE, 5f, false, pos = atRestArea, persist = true)!!
        assertEquals(StopLog(t0, t0 + 6 * min, null, null, restroom = true, name = "옥산휴게소"), rec.log)
    }

    @Test
    fun placeKindsAreTheStoppableOnes() {
        val shelter = PromptFakes.stop("s", "졸음쉼터", lat = 36.7, lon = 127.4, kind = StopKind.DROWSY_SHELTER)
        val other = PromptFakes.stop("o", "아무개", lat = 36.7, lon = 127.4, kind = StopKind.OTHER)
        val r = StopRecorder(StopDetector(), PromptFakes.History(), PromptFakes.Stops(listOf(other, shelter)))
        assertEquals("졸음쉼터", r.stopFor(5, door = false, pos = atRestArea, persist = true)!!.log.name)
    }
}
