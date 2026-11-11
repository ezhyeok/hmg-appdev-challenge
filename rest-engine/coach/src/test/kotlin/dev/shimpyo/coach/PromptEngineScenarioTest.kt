package dev.shimpyo.coach

import dev.shimpyo.coach.PromptEffect.Ask
import dev.shimpyo.coach.PromptEffect.CancelVoice
import dev.shimpyo.coach.PromptEffect.Report
import dev.shimpyo.coach.PromptEffect.Show
import dev.shimpyo.coach.PromptEffect.Speak
import dev.shimpyo.coach.PromptEffect.StartGoTo
import dev.shimpyo.coach.voice.YesNo
import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.TripProfile
import dev.shimpyo.engine.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 실제 엔진(귀성길 시연)과 질문 기계를 함께 돌린다. 기계 단위 테스트의 엔진 출력은 손으로 만든 것이라, 엔진이 실제로 내는
 * 순서(같은 id 반복, 사용자 요청이 질문을 밀어냄, 늦은 보고)와 맞는지 여기서 확인한다. 코어처럼 Report 효과는 엔진에 넣는다.
 */
class PromptEngineScenarioTest {

    private fun restArea(id: String, name: String, lat: Double, lon: Double, kind: StopKind = StopKind.HIGHWAY_REST_AREA) =
        StopCandidate(id = id, name = name, kind = kind, lat = lat, lon = lon, facilities = Facilities(food = kind == StopKind.HIGHWAY_REST_AREA), parkable = true)

    private val oksan = restArea("oksan", "옥산휴게소(부산방향)", 36.657743, 127.369817)
    private val jukam = restArea("jukam", "죽암휴게소(부산방향)", 36.486810, 127.429261)
    private val sintanjin = restArea("sintanjin", "신탄진졸음쉼터", 36.425354, 127.416697, StopKind.DROWSY_SHELTER)
    private val candidates = listOf(oksan, jukam, sintanjin)

    /** 귀성길 시연 하나: 실제 시각 1초마다 엔진 → 기계. Report는 엔진에 바로 알린다 */
    private class Drive(ttlScaled: Boolean, val stops: List<StopCandidate>) {
        var real = 1_700_000_000_000L
        val realStart = real
        val director = DemoDirector { real }
        val start = checkNotNull(director.start("holiday"))
        // 시연 엔진은 질문 수명을 배속만큼 늘려야 실제 45초가 된다 (CoachWorld 담당, prompt.md 2.5)
        val config = if (ttlScaled) CoachConfig(promptTtlS = (CoachConfig().promptTtlS * 7.2).toInt()) else CoachConfig()
        val engine: CoachEngine = director.now().let { t0 ->
            val restroomAt = t0 - start.lastRestroomMin * 60_000L
            CoachEngine(
                config = config,
                profile = TripProfile(
                    start.occupants, tripStart = t0 - start.drivenMin * 60_000L, lastRestroomAt = start.occupants.associate { it.id to restroomAt },
                    continuousDrivingMin = start.drivenMin.toDouble(),
                ),
                memory = PersonalMemory(),
                random = Random(42),
            )
        }
        val m = PromptMachine()
        var lastOffered: dev.shimpyo.engine.Prompt? = null
        val elapsed get() = real - realStart
        private fun ctx() = PromptContext.of(director.now(), elapsed, voiceEnabled = true, ttsReady = true, sttReady = true)

        fun on(e: PromptEvent): List<PromptEffect> = m.on(e, ctx()).also { apply(it) }

        private fun apply(effects: List<PromptEffect>) {
            for (e in effects) if (e is Report) engine.onPromptResult(e.promptId, e.accept, director.now())
        }

        /** 1초 진행하고 틱 하나. 돌려준 효과는 기계가 낸 것 */
        fun tick(intent: VoiceIntent? = null): List<PromptEffect> {
            real += 1_000L
            val out = engine.update(
                director.apply(CoachInput(now = director.now(), gear = Gear.DRIVE, speedKph = 100f, candidates = director.candidates(stops), intent = intent)),
            )
            lastOffered = out.prompt
            return on(PromptEvent.EngineTick(out.prompt, out.next, out.parked))
        }

        /** 마지막 기회 카드가 뜰 때까지 달린다 */
        fun untilLastChanceCard(): PromptCard {
            repeat(600) {
                tick()
                m.current?.takeIf { it.prompt.kind == PromptKind.LAST_CHANCE }?.let { return it }
            }
            error("마지막 기회가 뜨지 않았다")
        }
    }

    @Test
    fun acceptedLastChanceIsReportedAfterTheNaviAndNotAskedAgain() {
        val d = Drive(ttlScaled = true, stops = candidates)
        val card = d.untilLastChanceCard()
        assertEquals("oksan", card.prompt.stop?.id)
        assertTrue("카드에 정체 전 마지막 이유가 붙는다", card.reasons.any { it.code.startsWith("LAST_CHANCE") })
        val accepted = d.on(PromptEvent.Heard(card.key, YesNo.YES))
        val go = accepted.filterIsInstance<StartGoTo>().single()
        // 내비가 경유지를 넣는 동안(몇 초) 엔진은 같은 질문을 계속 낸다: 다시 띄우지 않는다
        repeat(5) { assertTrue(d.tick().none { it is Show && it.prompt != null }) }
        assertEquals(card.prompt.id, d.lastOffered?.id)
        val done = d.on(PromptEvent.GoToFinished(go.requestId, GoToOutcome.WAYPOINT_ADDED))
        assertTrue(done.contains(Report(card.prompt.id, true)))
        // 엔진이 수락을 받았으니 옥산을 다시 묻지 않는다
        repeat(60) {
            d.tick()
            assertNull("수락한 곳을 다시 묻지 않는다", d.m.current?.takeIf { it.prompt.stop?.id == "oksan" })
        }
    }

    @Test
    fun declinedLastChanceIsNotShownAgain() {
        val d = Drive(ttlScaled = true, stops = candidates)
        val card = d.untilLastChanceCard()
        assertEquals(listOf(CancelVoice, Show(null), Report(card.prompt.id, false)), d.on(PromptEvent.Tapped(card.key, false)))
        repeat(60) { assertTrue(d.tick().none { it is Ask }) }
    }

    @Test
    fun aPassedStopTakesTheCardAwayAndALateAcceptDoesNothing() {
        val d = Drive(ttlScaled = true, stops = candidates)
        val card = d.untilLastChanceCard()
        // 대답하지 않고 달린다: 엔진이 대상 500m 안에서(또는 수명으로) 질문을 거두면 카드도 내려간다
        var withdrawn: List<PromptEffect>? = null
        for (i in 0 until 120) {
            val fx = d.tick()
            if (d.m.current == null) {
                withdrawn = fx
                break
            }
        }
        assertEquals(listOf(CancelVoice, Show(null)), withdrawn)
        assertNull("엔진도 질문을 거뒀다", d.lastOffered)
        assertTrue("지나친 곳으로 내비 명령을 보내지 않는다", d.on(PromptEvent.Tapped(card.key, true)).isEmpty())
    }

    @Test
    fun aSpokenRequestDuringTheCardIsAnsweredWithoutBeingCutOff() {
        // "얼마나 남았어?": 엔진은 안내(INFO)로 답한다. 기계는 묻던 음성을 멈추고 안내를 말하며, 그 뒤 카드를 내릴 때
        // 그 안내를 끊지 않는다 (엔진이 질문을 버리든 지키든 — 엔진 감사 P1은 엔진 담당)
        val d = Drive(ttlScaled = true, stops = candidates)
        d.untilLastChanceCard()
        val answered = d.tick(intent = VoiceIntent.HowLong)
        val info = answered.filterIsInstance<Speak>().single()
        assertTrue(info.text.isNotBlank())
        assertEquals(CancelVoice, answered.first())
        val afterInfo = (1..10).flatMap { d.tick() }
        assertTrue("안내를 말한 뒤 CancelVoice가 없다: $afterInfo", afterInfo.none { it is CancelVoice })
    }

    @Test
    fun aDemoEngineWithTheDefaultTtlDropsTheCardInSecondsOfRealTime() {
        // 시연 7.2배속에서 엔진 수명 45초(코치 시각)는 실제 6초다. 엔진이 진실이므로 기계도 카드를 내린다.
        // CoachWorld가 시연 엔진의 promptTtlS를 배속만큼 늘려야 하는 이유 (prompt.md 2.5)
        val short = Drive(ttlScaled = false, stops = candidates)
        short.untilLastChanceCard()
        val shownAt = short.elapsed
        while (short.m.current != null && short.elapsed - shownAt < 60_000) short.tick()
        assertTrue("기본 수명이면 10초 안에 사라진다 (${short.elapsed - shownAt}ms)", short.elapsed - shownAt <= 10_000)

        val scaled = Drive(ttlScaled = true, stops = candidates)
        val card = scaled.untilLastChanceCard()
        scaled.on(PromptEvent.Heard(card.key, YesNo.UNKNOWN))
        // 시연 차는 실제 1초에 200m 간다. 옥산 500m 앞에서는 수명과 상관없이 엔진이 거둔다 (약 17초 뒤)
        repeat(12) { scaled.tick() }
        assertNotNull("늘린 수명이면 기본 수명(약 6초)이 지나도 버튼을 누를 수 있다", scaled.m.current)
    }
}
