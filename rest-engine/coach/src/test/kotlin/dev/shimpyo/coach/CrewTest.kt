package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeInterpreter
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.FakeVoice
import dev.shimpyo.coach.fakes.InMemoryMemory
import dev.shimpyo.coach.fakes.InMemoryTripStore
import dev.shimpyo.coach.fakes.KID
import dev.shimpyo.coach.fakes.RecordingUi
import dev.shimpyo.coach.fakes.WorldClock
import dev.shimpyo.coach.fakes.seededEngines
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PersonalMemory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 탑승자 담당 (Crew)을 코어 없이: 세계 전환기·음성 책상·가짜 포트만으로. 규칙 자체는 HealthConsent·AsleepMarks·OccupantPhrase의
 * 테스트가 지키고, 여기는 그것들이 화면 상태와 세계에 제대로 이어지는지 본다.
 */
class CrewTest {
    /** 2026-11-12 22:00 KST */
    private val clock = WorldClock(1_762_909_200_000L + 12 * 3_600_000L)
    private val pregnant = Occupant("p", OccupantType.PREGNANT, label = "임신부")

    private class Kit(scope: TestScope, clock: WorldClock, val settings: FakeSettings, val voice: FakeVoice, interpreter: IntentInterpreter?) {
        val ui = RecordingUi()
        val worlds = WorldSwitch(seededEngines(), settings, InMemoryMemory(), InMemoryTripStore(), wall = clock, elapsed = clock)
            .also { it.open(null, PersonalMemory(), emptyList()) }
        val desk = VoiceDesk(voice, settings, ui, clock).also { it.scope = scope.backgroundScope }
        val crew = Crew(worlds, settings, voice, desk, interpreter, ui)
    }

    private fun TestScope.kit(settings: FakeSettings = FakeSettings(listOf(DRIVER, KID)), voice: FakeVoice = FakeVoice(), interpreter: IntentInterpreter? = null) =
        Kit(this, clock, settings, voice, interpreter)

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun settingOccupantsFiltersHealthTypesWithoutConsentAndPublishesTheWorldsList() = runTest {
        val k = kit()
        k.crew.set(listOf(DRIVER, pregnant))
        assertEquals(listOf("driver"), k.ui.state.occupants.map { it.id })
        assertEquals(listOf(DRIVER), k.settings.occupants)
        k.settings.healthConsent = true
        k.crew.set(listOf(DRIVER, pregnant))
        assertEquals(listOf("driver", "p"), k.ui.state.occupants.map { it.id })
        // 시연에서는 그 시연에만
        k.worlds.enterDemo("holiday")
        k.crew.set(listOf(DRIVER))
        assertEquals(listOf("driver"), k.crew.occupants.map { it.id })
        assertEquals("실제 설정은 그대로", listOf(DRIVER, pregnant), k.settings.occupants)
    }

    @Test
    fun consentIsEnforcedWhenItIsSeenOffAndOnlyThen() = runTest {
        val k = kit(FakeSettings(listOf(DRIVER, pregnant), healthConsent = true))
        k.crew.enforceConsent()
        assertEquals(0, k.settings.occupantWrites)
        k.settings.healthConsent = false
        k.crew.enforceConsent()
        assertEquals(listOf(DRIVER), k.settings.occupants)
        assertEquals(listOf("driver"), k.ui.state.occupants.map { it.id })
        val writes = k.settings.occupantWrites
        repeat(5) { k.crew.enforceConsent() }
        assertEquals("값이 바뀌지 않으면 다시 보지 않는다", writes, k.settings.occupantWrites)
    }

    @Test
    fun asleepMarksArePublishedAndFollowTheOccupantsAndTheWorld() = runTest {
        val k = kit()
        assertEquals(AsleepMarks.Result.MARKED, k.crew.setAsleep("kid", true))
        assertEquals(setOf("kid"), k.ui.state.asleepOccupantIds)
        assertEquals(setOf("kid"), k.crew.asleepIds)
        assertEquals(AsleepMarks.Result.NOT_A_CHILD, k.crew.setAsleep("driver", true))
        k.crew.set(listOf(DRIVER))
        assertTrue("내린 아이의 표시는 남지 않는다", k.ui.state.asleepOccupantIds.isEmpty())

        k.crew.set(listOf(DRIVER, KID))
        k.crew.setAsleep(null, true)
        val since = clock.now()
        clock.advance(3 * 60_000L)
        k.crew.onTick(clock.now(), ParkState(since, doorSeen = true))
        assertTrue("화장실 정차", k.ui.state.asleepOccupantIds.isEmpty())

        k.crew.setAsleep("kid", true)
        k.crew.onRestroomStop()
        assertTrue(k.crew.asleepIds.isEmpty())
        k.crew.setAsleep("kid", true)
        k.crew.worldChanged()
        assertTrue("세계가 바뀌면 표시는 지난 세계의 것이다", k.crew.asleepIds.isEmpty())
    }

    @Test
    fun describingAsksAndTheHeardWordsBecomeOccupants() = runTest {
        val k = kit(FakeSettings(listOf(DRIVER)), FakeVoice(answer = { "일곱 살 아이 하나" }))
        var heard: String? = null
        k.crew.describe { heard = it }
        k.crew.describe { error("두 번 눌러도 한 번만 묻는다") }
        pass(2_000)
        assertEquals("일곱 살 아이 하나", heard)
        k.crew.heard(heard) { error("규칙으로 읽었으니 해석기는 쓰지 않는다") }
        assertEquals(listOf(OccupantType.ADULT, OccupantType.CHILD), k.ui.state.occupants.map { it.type })
        pass(2_000)
        assertTrue(k.voice.said.any { "7살 아이로 맞췄어요" in it })
    }

    @Test
    fun withoutSpeechOrRecognitionTheScreenSaysWhatToDo() = runTest {
        val off = kit(FakeSettings(listOf(DRIVER), voiceEnabled = false), FakeVoice().also { it.listens += "강아지" })
        var heard: String? = null
        off.crew.describe { heard = it }
        assertTrue(off.ui.state.notice.orEmpty().contains("누가 함께 타나요"))
        pass(2_000)
        assertEquals("묻지 않고 듣기만 한다", "강아지", heard)
        assertTrue(off.voice.asked.isEmpty())

        val deaf = kit(voice = FakeVoice().also { it.sttReady = false })
        deaf.crew.describe { error("들을 수 없다") }
        assertTrue(deaf.ui.state.notice.orEmpty().contains("음성 인식을 쓸 수 없어요"))
    }

    @Test
    fun wordsTheRulesCannotReadGoToTheInterpreter() = runTest {
        val llm = FakeInterpreter(people = listOf(DRIVER, KID))
        val k = kit(FakeSettings(listOf(DRIVER)), interpreter = llm)
        var result: List<Occupant>? = null
        var called = false
        k.crew.heard("우리 집 막둥이") { called = true; result = it }
        pass(100)
        assertTrue(called)
        k.crew.interpreted(result)
        assertEquals(listOf("driver", "kid"), k.ui.state.occupants.map { it.id })

        k.crew.interpreted(null)
        pass(2_000)
        assertTrue("못 읽었으면 그렇다고 답한다: ${k.voice.said}", k.voice.said.any { "못 알아들었어요" in it })
        assertEquals("탑승자는 그대로", listOf("driver", "kid"), k.ui.state.occupants.map { it.id })
        k.crew.heard("  ") { error("빈 말은 해석하지 않는다") }
        assertNull(k.ui.state.notice)
    }
}
