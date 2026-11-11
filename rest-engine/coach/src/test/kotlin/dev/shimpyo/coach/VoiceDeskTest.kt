package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.FakeVoice
import dev.shimpyo.coach.fakes.RecordingLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 음성 작업 책상 (VoiceDesk): 무엇이 돌고 있는지, 무엇을 취소하는지, 결과를 몇 번 돌려주는지. 코어 없이 가짜 음성으로 돌린다.
 * 닫힘 검증(2026-11-12)의 리뷰 R-F8(세계 전환)·R-F11(실패해도 결과 한 번)을 여기서 규칙으로 지킨다.
 */
class VoiceDeskTest {
    private val log = RecordingLog()

    private fun TestScope.desk(voice: VoicePort) = VoiceDesk(voice, log).also { it.scope = backgroundScope }

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** 말하기·듣기가 실패하는 음성 (TTS/STT 서비스 오류) */
    private class BrokenVoice : VoicePort {
        override val ttsReady = true
        override val sttReady = true
        override suspend fun connect() = Unit
        override suspend fun say(text: String): Boolean = error("tts down")
        override suspend fun ask(text: String, listenMs: Long): String? = error("tts down")
        override suspend fun listen(listenMs: Long): String? = error("stt down")
        override fun stop() = Unit
    }

    // ------------------------------------------------------------ 카드의 묻기

    @Test
    fun anAskSpeaksListensAndReportsTheAnswerOnce() = runTest {
        val voice = FakeVoice(answer = { "응" })
        val d = desk(voice)
        val heard = mutableListOf<String?>()
        d.ask("p1#1", "들를까요?", listen = true) { heard += it }
        assertTrue(d.busy)
        pass(2_000)
        assertEquals(listOf<String?>("응"), heard)
        assertEquals(listOf("들를까요?"), voice.asked)
        assertFalse(d.busy)
    }

    @Test
    fun anAskThatMayNotListenOnlySpeaksAndReportsNoAnswer() = runTest {
        val voice = FakeVoice(answer = { "응" })
        val d = desk(voice)
        val heard = mutableListOf<String?>()
        d.ask("p1#1", "들를까요?", listen = false) { heard += it }
        pass(2_000)
        assertEquals("마이크를 열지 않는다 (STT 없음)", listOf("들를까요?"), voice.said)
        assertTrue(voice.asked.isEmpty())
        assertEquals(listOf<String?>(null), heard)
    }

    @Test
    fun aNewAskCancelsThePreviousOneWhichReportsNothing() = runTest {
        val d = desk(FakeVoice(answer = { "응" }, speakMs = 3_000))
        val heard = mutableListOf<String>()
        d.ask("p1#1", "옥산휴게소 들를까요?", listen = true) { heard += "p1:$it" }
        pass(1_000)
        d.ask("p2#2", "죽암휴게소 들를까요?", listen = true) { heard += "p2:$it" }
        pass(5_000)
        assertEquals("취소된 묻기는 결과를 돌려주지 않는다", listOf("p2:응"), heard)
    }

    @Test
    fun aFailedAskStillReportsNoAnswerSoTheCardGetsItsButtonWindow() = runTest {
        val d = desk(BrokenVoice())
        val heard = mutableListOf<String?>()
        d.ask("p1#1", "들를까요?", listen = true) { heard += it }
        pass(100)
        assertEquals(listOf<String?>(null), heard)
        assertTrue(log.lines.any { "ask failed" in it })
    }

    // ------------------------------------------------------------ 멈추기

    @Test
    fun cancelCardStopsTheAskAndTheMicButNotTheOtherWork() = runTest {
        val voice = FakeVoice(answer = { "할머니랑 강아지" }, speakMs = 3_000)
        val d = desk(voice)
        val results = mutableListOf<String>()
        d.ask("p1#1", "들를까요?", listen = true) { results += "ask:$it" }
        d.listen { results += "listen:$it" }
        d.describe("누가 함께 타나요?") { results += "describe:$it" }
        d.interpret("interpret", failed = null, done = { results += "interpret:$it" }) {
            delay(2_000)
            "졸려"
        }
        pass(500)
        val stops = voice.stops
        d.cancelCard()
        assertEquals("말하기·듣기를 멈춘다", stops + 1, voice.stops)
        pass(5_000)
        assertEquals("탑승자 말하기와 해석은 카드와 상관없다", listOf("interpret:졸려", "describe:할머니랑 강아지"), results)
    }

    @Test
    fun stopAllCancelsEverythingAndNamesTheCardThatWasAsking() = runTest {
        val voice = FakeVoice(speakMs = 3_000)
        val d = desk(voice)
        val results = mutableListOf<String>()
        d.ask("p1#1", "들를까요?", listen = true) { results += "ask" }
        d.listen { results += "listen" }
        d.describe("누가 함께 타나요?") { results += "describe" }
        d.interpret("interpret", failed = null, done = { results += "interpret" }) { delay(2_000) }
        d.say("옥산휴게소로 안내할게요")
        pass(500)
        assertTrue(d.busy)
        assertEquals("묻던 카드: 코어가 '대답 없음'으로 알려 버튼 창을 준다", "p1#1", d.stopAll())
        assertFalse(d.busy)
        pass(10_000)
        assertTrue("멈춘 작업은 결과를 돌려주지 않는다: $results", results.isEmpty())
    }

    @Test
    fun stopAllNamesNoCardWhenTheAskHadAlreadyFinished() = runTest {
        val d = desk(FakeVoice())
        d.ask("p1#1", "들를까요?", listen = true) {}
        pass(2_000)
        assertNull("끝난 묻기는 이미 결과를 돌려줬다", d.stopAll())
        d.ask("p2#2", "들를까요?", listen = true) {}
        pass(2_000)
        d.askEnded("p2#2")
        assertNull(d.stopAll())
    }

    // ------------------------------------------------------------ 바쁨 (마이크 토글, UI 감사 #12)

    @Test
    fun everyKindOfVoiceWorkMakesTheDeskBusyUntilItEnds() = runTest {
        val works: List<Pair<String, (VoiceDesk) -> Unit>> = listOf(
            "ask" to { it.ask("p1#1", "들를까요?", listen = true) {} },
            "say" to { it.say("안내할게요") },
            "listen" to { it.listen {} },
            "describe" to { it.describe("누가 함께 타나요?") {} },
            "interpret" to { it.interpret("interpret", failed = null, done = {}) { delay(1_000) } },
        )
        for ((name, start) in works) {
            val d = desk(FakeVoice())
            assertFalse(d.busy)
            start(d)
            assertTrue("$name 중에는 바쁘다", d.busy)
            pass(3_000)
            assertFalse("$name 이 끝나면 한가하다", d.busy)
        }
    }

    @Test
    fun describingIsKnownSoASecondPressDoesNotStartAnother() = runTest {
        val d = desk(FakeVoice(speakMs = 3_000))
        assertFalse(d.describing)
        d.describe("누가 함께 타나요?") {}
        assertTrue(d.describing)
        pass(4_000)
        assertFalse(d.describing)
    }

    // ------------------------------------------------------------ 결과는 정확히 한 번 (리뷰 R-F11)

    @Test
    fun aFailedListenDescribeOrInterpretStillReportsOnce() = runTest {
        // 예전에는 실패한 작업이 아무 결과도 돌려주지 않았다: 해석기가 예외를 던지면 마이크 표시가 '생각하는 중'에 남았다
        val d = desk(BrokenVoice())
        val results = mutableListOf<String>()
        d.listen { results += "listen:$it" }
        d.describe("누가 함께 타나요?") { results += "describe:$it" }
        d.interpret<String?>("interpret", failed = null, done = { results += "interpret:$it" }) { error("llm crashed") }
        pass(1_000)
        assertEquals(listOf("describe:null", "interpret:null", "listen:null"), results.sorted())
        assertTrue(log.lines.any { "interpret failed" in it })
        pass(10_000)
        assertEquals("한 번만", 3, results.size)
    }

    @Test
    fun aSuccessfulWorkReportsItsResultExactlyOnce() = runTest {
        val d = desk(FakeVoice(answer = { "아이 하나" }).also { it.listens += "졸려" })
        val results = mutableListOf<String>()
        d.listen { results += "listen:$it" }
        d.describe("누가 함께 타나요?") { results += "describe:$it" }
        d.interpret("interpret", failed = "실패", done = { results += "interpret:$it" }) { "해석" }
        pass(10_000)
        assertEquals(listOf("describe:아이 하나", "interpret:해석", "listen:졸려"), results.sorted())
    }

    // ------------------------------------------------------------ 탑승자 말하기 (UI 닫힘 검증 #12)

    @Test
    fun describingWithoutAQuestionOnlyListens() = runTest {
        // 음성 안내를 껐으면 묻는 말 없이 듣기만 한다
        val voice = FakeVoice().also { it.listens += "일곱 살 아이 하나" }
        val d = desk(voice)
        val heard = mutableListOf<String?>()
        d.describe(null) { heard += it }
        assertTrue(d.describing)
        pass(2_000)
        assertEquals(listOf<String?>("일곱 살 아이 하나"), heard)
        assertTrue("말하지 않는다", voice.asked.isEmpty() && voice.said.isEmpty())
    }

    // ------------------------------------------------------------ 세계 전환 (리뷰 R-F8)

    @Test
    fun aWorldChangeCancelsEveryVoiceWorkSoNoResultReachesTheNewWorld() = runTest {
        // 예전에는 세계 전환이 카드의 묻기·듣기만 취소해서, 시연 중에 시작한 탑승자 말하기·해석의 결과가 시연을 끈 뒤 실제 세계에 적용됐다
        val voice = FakeVoice(answer = { "할머니랑 강아지" }, speakMs = 3_000)
        val d = desk(voice)
        val results = mutableListOf<String>()
        d.ask("p1#1", "들를까요?", listen = true) { results += "ask" }
        d.listen { results += "listen" }
        d.describe("누가 함께 타나요?") { results += "describe" }
        d.interpret("interpret", failed = null, done = { results += "interpret" }) { delay(2_000) }
        d.say("옥산휴게소로 안내할게요")
        pass(500)
        val stops = voice.stops
        d.worldChanged()
        assertEquals(stops + 1, voice.stops)
        assertFalse(d.busy)
        pass(10_000)
        assertTrue("지난 세계의 작업은 결과를 돌려주지 않는다: $results", results.isEmpty())
    }
}
