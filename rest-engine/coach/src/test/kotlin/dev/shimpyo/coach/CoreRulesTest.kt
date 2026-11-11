package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.PromptFakes
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.RankedStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** CoachCore의 작은 순수 규칙 (CoreRules.kt) */
class CoreRulesTest {

    // ------------------------------------------------------------ 환기 (서비스 감사 D16)

    @Test
    fun autoFreshAirWaitsTenMinutesAfterTheLastAttempt() {
        val g = FreshAirGate()
        assertTrue(g.autoAllowed(0L, recirculation = true))
        g.attempted(0L)
        assertFalse(g.autoAllowed(FreshAirGate.GAP_MS - 1, recirculation = true))
        assertTrue(g.autoAllowed(FreshAirGate.GAP_MS, recirculation = true))
        assertFalse("이미 외기면 할 일이 없다", g.autoAllowed(FreshAirGate.GAP_MS, recirculation = false))
        assertTrue("모르면 시도한다", FreshAirGate().autoAllowed(0L, recirculation = null))
    }

    @Test
    fun ourFreshAirIsRevertedOnceAfterNinetySecondsUnlessSomeoneAlreadyDid() {
        val g = FreshAirGate()
        assertFalse("바꾼 적이 없으면 되돌리지 않는다", g.takeRevert(1_000_000L, recirculation = false))
        g.switched(0L)
        assertFalse(g.takeRevert(FreshAirGate.REVERT_MS - 1, recirculation = false))
        assertTrue(g.takeRevert(FreshAirGate.REVERT_MS, recirculation = false))
        assertFalse("한 번만", g.takeRevert(FreshAirGate.REVERT_MS + 1_000, recirculation = false))
        g.switched(0L)
        assertFalse("사람이 이미 내기로 돌렸다", g.takeRevert(FreshAirGate.REVERT_MS, recirculation = true))
        assertNull(g.revertAtMs)
    }

    // ------------------------------------------------------------ 급해 (UI 감사 #7·#8·#9)

    @Test
    fun urgentWaitsForALocationThenGivesUpWithNoLocationButStillSearchesIfOneArrives() {
        val u = UrgentSession(1L, startedAtMs = 0L)
        assertEquals(UrgentSession.Step.NONE, u.next(1_000, hasPosition = false, dataLoaded = true))
        assertEquals(UrgentState.LOCATING, u.state)
        assertEquals(UrgentSession.Step.NO_LOCATION, u.next(UrgentSession.LOCATE_MS, hasPosition = false, dataLoaded = true))
        assertEquals(UrgentState.NO_LOCATION, u.state)
        assertEquals("한 번만 알린다", UrgentSession.Step.NONE, u.next(UrgentSession.LOCATE_MS + 1_000, hasPosition = false, dataLoaded = true))
        assertEquals("늦게라도 위치가 생기면 찾는다", UrgentSession.Step.SEARCH, u.next(20_000, hasPosition = true, dataLoaded = true))
    }

    @Test
    fun urgentWaitsForTheStopTableAndGivesUpIfItNeverLoads() {
        val u = UrgentSession(1L, startedAtMs = 0L)
        assertEquals(UrgentSession.Step.NONE, u.next(1_000, hasPosition = true, dataLoaded = false))
        assertEquals(UrgentState.SEARCHING, u.state)
        u.next(UrgentSession.SEARCH_MS, hasPosition = true, dataLoaded = false)
        assertEquals(UrgentState.NO_RESULT, u.state)
    }

    @Test
    fun urgentSearchesAgainEveryTenSecondsAndTellsTheEngineOnlyOnce() {
        val u = UrgentSession(1L, startedAtMs = 0L)
        val s = PromptFakes.stop("wc")
        assertEquals(UrgentSession.Step.SEARCH, u.next(0, true, true))
        assertTrue("처음이면 엔진에 알린다", u.found(0, listOf(s), listOf(RankedStop(s, 60.0, emptyList()))))
        assertEquals(UrgentState.READY, u.state)
        assertEquals(UrgentSession.Step.NONE, u.next(UrgentSession.REFRESH_MS - 1, true, true))
        assertEquals(UrgentSession.Step.SEARCH, u.next(UrgentSession.REFRESH_MS, true, true))
        assertFalse(u.found(UrgentSession.REFRESH_MS, emptyList(), emptyList()))
        assertEquals(UrgentState.NO_RESULT, u.state)
        u.searchAgain()
        assertEquals("다시 말하면 바로", UrgentSession.Step.SEARCH, u.next(UrgentSession.REFRESH_MS + 1, true, true))
    }

    // ------------------------------------------------------------ 검색 위치 (데이터 감사 #10)

    @Test
    fun aParkedOrSlowRealFixSearchesWithoutAHeading() {
        val real = Fix(37.0, 127.0, 0f)
        assertNull("P", CoreRules.searchFix(real, real, 0f, Gear.PARK)!!.heading)
        assertNull("느림", CoreRules.searchFix(real, real, 5f, Gear.DRIVE)!!.heading)
        assertEquals("달리면 방위를 쓴다", 0f, CoreRules.searchFix(real, real, 60f, Gear.DRIVE)!!.heading)
        assertEquals("차속을 모르고 D면 쓴다", 0f, CoreRules.searchFix(real, real, null, Gear.DRIVE)!!.heading)
        val demo = Fix(36.7, 127.2, 160f)
        assertSame("시연 위치는 시연 경로의 방위 그대로", demo, CoreRules.searchFix(demo, real, 0f, Gear.PARK))
        assertNull(CoreRules.searchFix(null, real, 60f, Gear.DRIVE))
    }

    @Test
    fun extrasJoinThePlannedCandidatesWithoutDuplicatesAndWithFreshDistances() {
        val planned = listOf(PromptFakes.stop("oksan", routeDistanceM = 3_000.0))
        assertSame("더할 것이 없으면 같은 목록", planned, CoreRules.withExtras(planned, emptyList(), null))
        val wc = PromptFakes.stop("wc", lat = 37.01, lon = 127.0, straightDistanceM = 99_999.0)
        val out = CoreRules.withExtras(planned, listOf(PromptFakes.stop("oksan"), wc, wc), Fix(37.0, 127.0, null))
        assertEquals(listOf("oksan", "wc"), out.map { it.id })
        assertEquals("경로 후보를 남긴다", 3_000.0, out[0].routeDistanceM!!, 0.0)
        assertEquals("지금 위치로 다시 잰 직선거리", 1_112.0, out[1].straightDistanceM!!, 5.0)
    }
}
