package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.KID
import dev.shimpyo.coach.fakes.PromptFakes
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PitStopPlan
import dev.shimpyo.engine.PlannedStop
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.Recommendation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** CoachCore의 작은 순수 규칙 (CoreRules.kt). 급해는 UrgentFlowTest, 계획은 PlanKeeperTest, 음성 작업은 VoiceDeskTest */
class CoreRulesTest {

    // ------------------------------------------------------------ 환기 (서비스 감사 D16)

    @Test
    fun autoFreshAirWaitsTenMinutesAfterTheLastAttempt() {
        val g = FreshAirGate()
        assertTrue(g.autoAllowed(0L, recirculation = true))
        assertTrue(g.begin(0L))
        g.finished(1_000L, switched = false)
        assertFalse(g.autoAllowed(FreshAirGate.GAP_MS - 1, recirculation = true))
        assertTrue(g.autoAllowed(FreshAirGate.GAP_MS, recirculation = true))
        assertFalse("이미 외기면 할 일이 없다", g.autoAllowed(FreshAirGate.GAP_MS, recirculation = false))
        assertTrue("모르면 시도한다", FreshAirGate().autoAllowed(0L, recirculation = null))
    }

    @Test
    fun ourFreshAirIsRevertedOnceAfterNinetySecondsUnlessSomeoneAlreadyDid() {
        val g = FreshAirGate()
        assertFalse("바꾼 적이 없으면 되돌리지 않는다", g.takeRevert(1_000_000L, recirculation = false))
        g.begin(0L)
        g.finished(0L, switched = true)
        assertFalse(g.takeRevert(FreshAirGate.REVERT_MS - 1, recirculation = false))
        assertTrue(g.takeRevert(FreshAirGate.REVERT_MS, recirculation = false))
        assertFalse("한 번만", g.takeRevert(FreshAirGate.REVERT_MS + 1_000, recirculation = false))
        g.begin(0L)
        g.finished(0L, switched = true)
        assertFalse("사람이 이미 내기로 돌렸다", g.takeRevert(FreshAirGate.REVERT_MS, recirculation = true))
        assertNull(g.revertAtMs)
    }

    @Test
    fun oneFreshAirSwitchAtATime() {
        val g = FreshAirGate()
        assertTrue(g.begin(0L))
        assertTrue(g.busy)
        assertFalse("앞의 전환이 끝나기 전에는 또 보내지 않는다", g.begin(1_000L))
        g.finished(2_000L, switched = false)
        assertFalse(g.busy)
        assertNull("바뀌지 않았으면 되돌릴 것도 없다", g.revertAtMs)
        assertTrue(g.begin(3_000L))
    }

    // ------------------------------------------------------------ 학습 저장

    @Test
    fun learningIsSavedWhenChangedOneAtATimeAndAtMostOnceAMinute() {
        val loaded = PersonalMemory()
        val changed = PersonalMemory(restroomIntervalsMin = mapOf("kid" to listOf(80.0)))
        val more = PersonalMemory(restroomIntervalsMin = mapOf("kid" to listOf(80.0, 90.0)))
        val s = MemorySaver()
        s.loaded(loaded)
        assertNull("읽은 그대로면 쓰지 않는다", s.due(0L) { MemorySave(loaded, 0L) })
        assertNull("저장하지 않는 세계(시연)", s.due(0L) { null })
        val first = s.due(1_000L) { MemorySave(changed, 0L) }
        assertEquals("바뀌면 바로", changed, first?.memory)
        s.started(1_000L)
        assertNull("쓰는 중에는 또 쓰지 않는다", s.due(2_000L) { MemorySave(more, 0L) })
        s.finished(changed, ok = true)
        var asked = false
        assertNull("1분 안에는 다시 쓰지 않는다 (지금 상태를 묻지도 않는다)", s.due(1_000L + MemorySaver.EVERY_MS - 1) { asked = true; MemorySave(more, 0L) })
        assertFalse(asked)
        assertEquals(more, s.due(1_000L + MemorySaver.EVERY_MS) { MemorySave(more, 0L) }?.memory)
    }

    @Test
    fun aFailedOrClearedSaveIsWrittenAgainAndShutdownWritesWhatIsLeft() {
        val m = PersonalMemory(restroomIntervalsMin = mapOf("kid" to listOf(80.0)))
        val s = MemorySaver()
        s.loaded(PersonalMemory())
        s.started(0L)
        s.finished(m, ok = false)
        assertEquals("실패한 저장은 다음 주기에 다시", m, s.due(MemorySaver.EVERY_MS) { MemorySave(m, 0L) }?.memory)
        assertEquals("끝날 때 바뀐 것이 남아 있으면 쓴다", m, s.atShutdown(MemorySave(m, 0L))?.memory)
        s.started(MemorySaver.EVERY_MS)
        s.finished(m, ok = true)
        assertNull("이미 쓴 것은 끝날 때 다시 쓰지 않는다", s.atShutdown(MemorySave(m, 0L)))
        assertNull(s.atShutdown(null))
        s.cleared()
        assertEquals("기록을 지운 뒤에는 같은 내용도 다시 쓴다 (파일이 비었다)", m, s.atShutdown(MemorySave(m, 1L))?.memory)
    }

    // ------------------------------------------------------------ 건강 정보 동의

    @Test
    fun healthTypeOccupantsNeedConsent() {
        val pregnant = Occupant("p", OccupantType.PREGNANT)
        val oab = Occupant("o", OccupantType.OAB)
        val ibs = Occupant("i", OccupantType.IBS)
        val all = listOf(DRIVER, KID, pregnant, oab, ibs)
        assertEquals(listOf(DRIVER, KID), HealthConsent.allowed(all, consent = false))
        assertSame("동의했으면 그대로", all, HealthConsent.allowed(all, consent = true))
        assertEquals(setOf(OccupantType.PREGNANT, OccupantType.OAB, OccupantType.IBS), OccupantType.entries.filter(HealthConsent::isHealth).toSet())
    }

    @Test
    fun spokenOccupantsKeepThePassengersWhenConsentLeavesNobodyNew() {
        val pregnant = Occupant("p0", OccupantType.PREGNANT, label = "임신부")
        val elder = Occupant("p1", OccupantType.ELDERLY, label = "어르신")
        // 리뷰 R-F9: 동의 없이 "임산부 한 명" → 걸러 내면 운전자뿐이다. 그대로 적용하면 타고 있던 사람이 지워진다
        assertEquals(HealthConsent.Spoken(apply = null, consentNeeded = true), HealthConsent.spoken(listOf(DRIVER, pregnant), consent = false))
        assertEquals("남는 사람이 있으면 그 사람들로 맞춘다", HealthConsent.Spoken(listOf(DRIVER, elder), consentNeeded = true), HealthConsent.spoken(listOf(DRIVER, pregnant, elder), consent = false))
        assertEquals("동의했으면 그대로", HealthConsent.Spoken(listOf(DRIVER, pregnant), consentNeeded = false), HealthConsent.spoken(listOf(DRIVER, pregnant), consent = true))
        assertEquals("건강 유형이 없으면 동의와 무관하다", HealthConsent.Spoken(listOf(DRIVER, KID), consentNeeded = false), HealthConsent.spoken(listOf(DRIVER, KID), consent = false))
    }

    // ------------------------------------------------------------ 고른 정차의 목적 (서비스 닫힘 검증 D7)

    @Test
    fun theAcceptedStopsPurposesAreWhatIsKnownAboutThePick() {
        val oksan = PromptFakes.stop("oksan")
        val lot = PromptFakes.stop("lot").let { it.copy(facilities = it.facilities.copy(restroom = false)) }
        val next = Recommendation(oksan, setOf(Purpose.RESTROOM, Purpose.MEAL), emptyList())
        val plan = PitStopPlan(listOf(PlannedStop(oksan, setOf(Purpose.REST, Purpose.FUEL), dwellMin = 15, arriveInS = 600.0)), totalStopMin = 15)
        assertEquals("급해 목록에서 골랐으면 화장실", setOf(Purpose.RESTROOM), CoreRules.acceptedPurposes(oksan, urgentPick = true, next, plan))
        assertEquals("엔진이 지금 추천하는 곳이면 그 목적", setOf(Purpose.RESTROOM, Purpose.MEAL), CoreRules.acceptedPurposes(oksan, false, next, plan))
        assertEquals("계획에 있는 정차면 계획의 목적", setOf(Purpose.REST, Purpose.FUEL), CoreRules.acceptedPurposes(oksan, false, null, plan))
        assertEquals("추천이 다른 곳이면 쓰지 않는다", setOf(Purpose.REST, Purpose.FUEL), CoreRules.acceptedPurposes(oksan, false, next.copy(stop = lot), plan))
        assertEquals("모르면 화장실이 있는 곳은 화장실", setOf(Purpose.RESTROOM), CoreRules.acceptedPurposes(oksan, false, null, null))
        assertEquals("화장실이 없는 곳은 휴식", setOf(Purpose.REST), CoreRules.acceptedPurposes(lot, false, null, null))
    }

    // ------------------------------------------------------------ 검색 위치 (데이터 감사 #10)

    @Test
    fun aRealFixSearchesWithTheTravelHeadingAndADemoFixWithItsOwn() {
        val real = Fix(37.0, 127.0, 0f) // 서 있는 차의 위치 방위 0.0은 믿을 수 없다
        assertEquals("진행 방향으로 찾는다", 161f, CoreRules.searchFix(real, real, travelHeading = 161f)!!.heading)
        assertNull("움직인 적이 없으면 방위 없이", CoreRules.searchFix(real, real, travelHeading = null)!!.heading)
        val moving = Fix(37.0, 127.0, 161f)
        assertSame("방위가 같으면 같은 위치 그대로", moving, CoreRules.searchFix(moving, moving, travelHeading = 161f))
        val demo = Fix(36.7, 127.2, 160f)
        assertSame("시연 위치는 시연 경로의 방위 그대로", demo, CoreRules.searchFix(demo, real, travelHeading = 20f))
        assertNull(CoreRules.searchFix(null, real, travelHeading = 161f))
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
