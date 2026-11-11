package dev.shimpyo.coach

import dev.shimpyo.coach.PromptEffect.Ask
import dev.shimpyo.coach.PromptEffect.CancelVoice
import dev.shimpyo.coach.PromptEffect.Forward
import dev.shimpyo.coach.PromptEffect.FreshAir
import dev.shimpyo.coach.PromptEffect.PublishGoToResult
import dev.shimpyo.coach.PromptEffect.Report
import dev.shimpyo.coach.PromptEffect.ScheduleExpiry
import dev.shimpyo.coach.PromptEffect.Show
import dev.shimpyo.coach.PromptEffect.Speak
import dev.shimpyo.coach.PromptEffect.StartGoTo
import dev.shimpyo.coach.PromptEvent.EngineTick
import dev.shimpyo.coach.PromptEvent.Expired
import dev.shimpyo.coach.PromptEvent.GoTo
import dev.shimpyo.coach.PromptEvent.GoToFinished
import dev.shimpyo.coach.PromptEvent.Heard
import dev.shimpyo.coach.PromptEvent.Reset
import dev.shimpyo.coach.PromptEvent.Tapped
import dev.shimpyo.coach.fakes.PromptFakes
import dev.shimpyo.coach.voice.YesNo
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.Recommendation
import dev.shimpyo.engine.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/**
 * 화면·음성 질문 상태 기계. 앞부분은 전이표(상태 × 사건 → 상태 + 효과), 뒷부분은 여러 사건을 잇는 시나리오.
 * 지키는 것 (서비스 감사 D1·D2·D9·D16·P7, UI 감사 #1·#3·#6·#12, 구조 리뷰 D1·D2·D7·B1·B5):
 * 엔진이 질문의 진실이다(급해 결과도 엔진의 질문이다), 대답은 지금 질문에만, 수락 보고는 내비 성공 뒤, 수명은 실제 시간,
 * 안내(INFO)는 말만 한다, 음성 끄기면 말하지도 듣지도 않는다.
 */
class PromptMachineTest {

    // ---------------------------------------------------------------- 재료

    private val oksan = PromptFakes.stop("kex:oksan", "옥산휴게소", routeDistanceM = 3_900.0)
    private val jukam = PromptFakes.stop("kex:jukam", "죽암휴게소", routeDistanceM = 21_000.0)
    private val mangHyang = PromptFakes.stop("kex:manghyang", "망향휴게소", routeDistanceM = 8_000.0)
    private val gasStation = PromptFakes.stop("osm:gs1", "강남주유소")

    private val jamReason = Reason("LAST_CHANCE_CONGESTION", "정체 전 마지막")
    private fun nextFor(stop: dev.shimpyo.engine.StopCandidate, vararg r: Reason) = Recommendation(stop, setOf(Purpose.RESTROOM), r.toList())

    private val p1 = Prompt("p1", PromptKind.LAST_CHANCE, "옥산휴게소가 정체 전 마지막 화장실이에요. 들를까요?", oksan, expiresAt = 0)
    private val p2 = Prompt("p2", PromptKind.LAST_CHANCE, "죽암휴게소 들를까요?", jukam, expiresAt = 0)
    private val rest = Prompt("p3", PromptKind.REST_SUGGESTION, "망향휴게소에서 쉬어 갈까요?", mangHyang, expiresAt = 0)
    private val restNoStop = Prompt("p4", PromptKind.REST_SUGGESTION, "100분째 운전 중이에요. 쉬어 갈까요?", null, expiresAt = 0)
    private val checkIn = Prompt("p5", PromptKind.CHECK_IN, "혹시 졸리세요?", null, expiresAt = 0)
    private val freshAir = Prompt("p6", PromptKind.FRESH_AIR, "환기할까요?", null, expiresAt = 0)
    private val info = Prompt("p7", PromptKind.INFO, "화장실까지 약 40분 남았어요.", null, expiresAt = 0)
    private val urgent = Prompt("urgent-1", PromptKind.URGENT_RESULT, "가장 빠른 곳은 강남주유소, 약 3분이에요. 안내할까요?", gasStation, expiresAt = 0)

    private val engineMs = PromptMachine.ENGINE_CARD_MS
    private val t0 = 1_000_000_000_000L

    /** 카드 key = 질문 id + 띄운 순번 (기계가 띄울 때마다 1씩 오른다). 첫 카드는 1 */
    private fun key(p: Prompt, seq: Int = 1) = PromptMachine.keyOf(p.id, seq)
    private fun shown(p: Prompt, seq: Int = 1) = p.copy(id = key(p, seq))

    /** 기계 하나와 두 시계. voice=false면 설정에서 음성을 끈 것 */
    private class Rig(var voice: Boolean = true, var stt: Boolean = true) {
        val m = PromptMachine()
        var elapsed = 0L
        var coach = 1_000_000_000_000L
        fun on(e: PromptEvent): List<PromptEffect> = m.on(e, PromptContext.of(coach, elapsed, voiceEnabled = voice, ttsReady = true, sttReady = stt))
        fun tick(offered: Prompt?, parked: Boolean = false, next: Recommendation? = null) = on(EngineTick(offered, next, parked))
    }

    private fun showEffects(p: Prompt, at: Long = 0, life: Long = engineMs, reasons: List<Reason> = emptyList(), listen: Boolean = true, seq: Int = 1) =
        listOf(Show(shown(p, seq), reasons), ScheduleExpiry(key(p, seq), at + life), Ask(key(p, seq), p.speech, listen))

    // ---------------------------------------------------------------- 전이표

    private class Row(
        val name: String,
        val given: Rig.() -> Unit,
        val event: PromptEvent,
        val state: KClass<out PromptState>,
        val effects: List<PromptEffect>,
        val elapsed: Long? = null,
        val voice: Boolean = true,
        val stt: Boolean = true,
    )

    private val idle: Rig.() -> Unit = {}
    private val askingP1: Rig.() -> Unit = { tick(p1, next = nextFor(oksan, jamReason)) }
    private val awaitingP1: Rig.() -> Unit = { askingP1(); on(Heard(key(p1), YesNo.UNKNOWN)) }
    private val askingUrgent: Rig.() -> Unit = { tick(urgent) }
    private val askingFreshAir: Rig.() -> Unit = { tick(freshAir) }
    private val askingCheckIn: Rig.() -> Unit = { tick(checkIn) }
    private val askingRestNoStop: Rig.() -> Unit = { tick(restNoStop) }
    private val askingRest: Rig.() -> Unit = { tick(rest) }

    private val goToOksan = listOf(PublishGoToResult(null), StartGoTo(1, oksan, parked = false))

    private val rows = listOf(
        // ----- Idle
        Row("Idle × 엔진 질문 없음", idle, EngineTick(null, null, false), PromptState.Idle::class, emptyList()),
        Row("Idle × 엔진 질문 → 띄우고 묻는다 (이유는 띄울 때 잡는다)", idle, EngineTick(p1, nextFor(oksan, jamReason), false),
            PromptState.Asking::class, showEffects(p1, reasons = listOf(jamReason))),
        Row("Idle × 엔진 질문, 추천이 다른 곳이면 이유 없음", idle, EngineTick(p1, nextFor(jukam, jamReason), false),
            PromptState.Asking::class, showEffects(p1)),
        Row("Idle × 엔진 질문, 음성 끔 → 카드만", idle, EngineTick(p1, null, false),
            PromptState.AwaitingTap::class, listOf(Show(shown(p1)), ScheduleExpiry(key(p1), engineMs)), voice = false),
        Row("Idle × 엔진 질문, STT 없음 → 말만 하고 마이크는 열지 않는다", idle, EngineTick(p1, null, false),
            PromptState.Asking::class, showEffects(p1, listen = false), stt = false),
        Row("Idle × 정차 중 주행 질문 → 띄우지 않는다", idle, EngineTick(checkIn, null, true), PromptState.Idle::class, emptyList()),
        Row("Idle × 정차 중 환기 → 띄운다", idle, EngineTick(freshAir, null, true), PromptState.Asking::class, showEffects(freshAir)),
        Row("Idle × 안내(INFO) → 말하고 엔진에 전달됐다고 알린다 (카드·마이크 없음)", idle, EngineTick(info, null, false),
            PromptState.Idle::class, listOf(Speak(info.speech), Report(info.id, true))),
        Row("Idle × 안내, 음성 끔 → 말하지 않고 엔진 자리만 비운다", idle, EngineTick(info, null, false),
            PromptState.Idle::class, listOf(Report(info.id, true)), voice = false),
        Row("Idle × 정차 중 엔진의 급해 결과 → 띄운다 (서 있어도 쓸모 있다)", idle, EngineTick(urgent, null, true), PromptState.Asking::class, showEffects(urgent)),
        Row("Idle × 버튼 → 무시", idle, Tapped(key(p1), true), PromptState.Idle::class, emptyList()),
        Row("Idle × 늦은 음성 대답 → 답은 버리고 다른 요청만 전한다", idle, Heard(key(p1), YesNo.YES, VoiceIntent.UrgentRestroom),
            PromptState.Idle::class, listOf(Forward(VoiceIntent.UrgentRestroom))),
        Row("Idle × 만료 → 무시", idle, Expired(key(p1)), PromptState.Idle::class, emptyList()),
        Row("Idle × 초기화", idle, Reset, PromptState.Idle::class, listOf(CancelVoice, Show(null), PublishGoToResult(null))),
        Row("Idle × 들르기 명령", idle, GoTo(oksan), PromptState.Idle::class, goToOksan),

        // ----- Asking(엔진 마지막 기회 p1)
        Row("묻는 중 × 같은 질문 → 그대로", askingP1, EngineTick(p1, null, false), PromptState.Asking::class, emptyList()),
        Row("묻는 중 × 엔진이 더 급한 질문으로 바꿈 → 같은 틱에 새 카드 (D1)", askingP1, EngineTick(p2, null, false),
            PromptState.Asking::class, listOf(CancelVoice) + showEffects(p2, seq = 2)),
        Row("묻는 중 × 엔진이 거둠(지나침·만료) → 카드와 음성을 내린다 (D2)", askingP1, EngineTick(null, null, false),
            PromptState.Idle::class, listOf(CancelVoice, Show(null))),
        Row("묻는 중 × P로 세움 → 주행 질문은 거둔다", askingP1, EngineTick(p1, null, true),
            PromptState.Idle::class, listOf(CancelVoice, Show(null))),
        Row("묻는 중 × 안내 → 묻던 음성을 멈추고 대답을 말한다, 카드는 그대로 (버튼 대기)", askingP1, EngineTick(info, null, false),
            PromptState.AwaitingTap::class, listOf(CancelVoice, Speak(info.speech), Report(info.id, true))),
        Row("묻는 중 × 실제 수명이 지난 틱 (타이머를 잃어도 틱이 거둔다)", askingP1, EngineTick(p1, null, false),
            PromptState.Idle::class, listOf(CancelVoice, Show(null)), elapsed = engineMs),
        Row("묻는 중 × '응' → 카드를 내리고 들르기 (보고는 성공 뒤)", askingP1, Heard(key(p1), YesNo.YES),
            PromptState.Idle::class, listOf(Show(null)) + goToOksan),
        Row("묻는 중 × '아니' → 거절 보고", askingP1, Heard(key(p1), YesNo.NO),
            PromptState.Idle::class, listOf(Show(null), Report("p1", false))),
        Row("묻는 중 × 대답 없음 → 버튼 대기", askingP1, Heard(key(p1), YesNo.UNKNOWN), PromptState.AwaitingTap::class, emptyList()),
        Row("묻는 중 × '아니 화장실 급해' → 거절하고 급해를 전한다", askingP1, Heard(key(p1), YesNo.NO, VoiceIntent.UrgentRestroom),
            PromptState.Idle::class, listOf(Show(null), Report("p1", false), Forward(VoiceIntent.UrgentRestroom))),
        Row("묻는 중 × '배고파'(답 아님) → 버튼 대기 + 전달", askingP1, Heard(key(p1), YesNo.UNKNOWN, VoiceIntent.Hungry),
            PromptState.AwaitingTap::class, listOf(Forward(VoiceIntent.Hungry))),
        Row("묻는 중 × 대답 안의 수락 의도는 전하지 않는다 (답은 yes/no로 이미 받았다)", askingP1, Heard(key(p1), YesNo.NO, VoiceIntent.Accept(key(p1))),
            PromptState.Idle::class, listOf(Show(null), Report("p1", false))),
        Row("묻는 중 × 다른 질문의 음성 대답 → 무시", askingP1, Heard(key(p2), YesNo.YES), PromptState.Asking::class, emptyList()),
        Row("묻는 중 × 수락 버튼 → 음성을 멈추고 들르기", askingP1, Tapped(key(p1), true),
            PromptState.Idle::class, listOf(CancelVoice, Show(null)) + goToOksan),
        Row("묻는 중 × 거절 버튼", askingP1, Tapped(key(p1), false),
            PromptState.Idle::class, listOf(CancelVoice, Show(null), Report("p1", false))),
        Row("묻는 중 × 엔진 id 그대로 온 버튼(세대 없음) → 무시", askingP1, Tapped("p1", true), PromptState.Asking::class, emptyList()),
        Row("묻는 중 × 수명 전 만료 신호 → 무시", askingP1, Expired(key(p1)), PromptState.Asking::class, emptyList(), elapsed = engineMs - 1),
        Row("묻는 중 × 수명 만료", askingP1, Expired(key(p1)), PromptState.Idle::class, listOf(CancelVoice, Show(null)), elapsed = engineMs),
        Row("묻는 중 × 엔진이 급해 결과로 바꿈 → 같은 틱에 급해 카드", askingP1, EngineTick(urgent, null, false),
            PromptState.Asking::class, listOf(CancelVoice) + showEffects(urgent, seq = 2)),
        Row("묻는 중 × 초기화", askingP1, Reset, PromptState.Idle::class, listOf(CancelVoice, Show(null), PublishGoToResult(null))),
        Row("묻는 중 × 다른 곳 들르기 명령 → 카드는 그대로", askingP1, GoTo(jukam),
            PromptState.Asking::class, listOf(PublishGoToResult(null), StartGoTo(1, jukam, parked = false))),

        // ----- AwaitingTap(엔진 p1): 음성이 끝났으니 멈출 음성이 없다
        Row("버튼 대기 × 엔진이 거둠", awaitingP1, EngineTick(null, null, false), PromptState.Idle::class, listOf(Show(null))),
        Row("버튼 대기 × 엔진이 바꿈", awaitingP1, EngineTick(p2, null, false), PromptState.Asking::class, showEffects(p2, seq = 2)),
        Row("버튼 대기 × 수락 버튼", awaitingP1, Tapped(key(p1), true), PromptState.Idle::class, listOf(Show(null)) + goToOksan),
        Row("버튼 대기 × 같은 카드 음성 작업의 늦은 '응'", awaitingP1, Heard(key(p1), YesNo.YES), PromptState.Idle::class, listOf(Show(null)) + goToOksan),
        Row("버튼 대기 × 만료", awaitingP1, Expired(key(p1)), PromptState.Idle::class, listOf(Show(null)), elapsed = engineMs),
        Row("버튼 대기 × P로 세움", awaitingP1, EngineTick(p1, null, true), PromptState.Idle::class, listOf(Show(null))),

        // ----- Asking(엔진의 급해 결과)
        Row("급해 결과 × P로 세움 → 그대로 (서 있어도 쓸모 있다)", askingUrgent, EngineTick(urgent, null, true), PromptState.Asking::class, emptyList()),
        Row("급해 결과 × 수락 → 들르기 (보고는 성공 뒤)", askingUrgent, Tapped(key(urgent), true),
            PromptState.Idle::class, listOf(CancelVoice, Show(null), PublishGoToResult(null), StartGoTo(1, gasStation, parked = false))),
        Row("급해 결과 × 거절 → 거절 보고", askingUrgent, Tapped(key(urgent), false),
            PromptState.Idle::class, listOf(CancelVoice, Show(null), Report(urgent.id, false))),

        // ----- 질문 종류별 수락
        Row("환기 × 수락 → 공조 효과 + 바로 보고", askingFreshAir, Tapped(key(freshAir), true),
            PromptState.Idle::class, listOf(CancelVoice, Show(null), FreshAir(announce = true), Report(freshAir.id, true))),
        Row("환기 × 정차 중에도 그대로", askingFreshAir, EngineTick(freshAir, null, true), PromptState.Asking::class, emptyList()),
        Row("상태 확인 × '응' → 보고만 (엔진이 다음 틱에 휴식을 권한다)", askingCheckIn, Heard(key(checkIn), YesNo.YES),
            PromptState.Idle::class, listOf(Show(null), Report(checkIn.id, true))),
        Row("장소 없는 휴식 권유 × 수락 → 보고만", askingRestNoStop, Tapped(key(restNoStop), true),
            PromptState.Idle::class, listOf(CancelVoice, Show(null), Report(restNoStop.id, true))),
        Row("휴식 권유 × 엔진이 마지막 기회로 밀어냄 (REST → LAST_CHANCE)", askingRest, EngineTick(p1, nextFor(oksan, jamReason), false),
            PromptState.Asking::class, listOf(CancelVoice) + showEffects(p1, reasons = listOf(jamReason), seq = 2)),
        Row("Idle × 결과 닫기", idle, PromptEvent.DismissGoToResult, PromptState.Idle::class, listOf(PublishGoToResult(null))),
    )

    @Test
    fun transitionTable() {
        val failures = mutableListOf<String>()
        for (r in rows) {
            val rig = Rig(voice = r.voice, stt = r.stt)
            r.given(rig)
            r.elapsed?.let { rig.elapsed = it }
            val effects = rig.on(r.event)
            if (!r.state.isInstance(rig.m.state)) failures += "${r.name}: state ${rig.m.state::class.simpleName}, expected ${r.state.simpleName}"
            if (effects != r.effects) failures += "${r.name}:\n   got      $effects\n   expected ${r.effects}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ---------------------------------------------------------------- 수락 흐름: 정직한 결과 (UI 감사 #3, 서비스 감사 D9)

    @Test
    fun acceptIsReportedOnlyAfterTheNaviSucceeded() {
        val r = Rig()
        r.tick(p1)
        val accepted = r.on(Tapped(key(p1), true))
        assertTrue("수락만으로는 엔진에 알리지 않는다", accepted.none { it is Report })
        assertEquals("엔진이 아직 p1을 내도 다시 띄우지 않는다", emptyList<PromptEffect>(), r.tick(p1))
        r.coach = t0 + 5_000
        val done = r.on(GoToFinished(1, GoToOutcome.WAYPOINT_ADDED))
        assertEquals(
            listOf(
                PublishGoToResult(GoToResult(oksan, GoToOutcome.WAYPOINT_ADDED, t0 + 5_000)),
                Speak("옥산휴게소를 경유지로 넣었어요"),
                Report("p1", true),
            ),
            done,
        )
    }

    @Test
    fun aFailedGoToShowsRetryAndTheRetryStillReportsTheAccept() {
        val r = Rig()
        r.tick(p1)
        r.on(Tapped(key(p1), true))
        val failed = r.on(GoToFinished(1, GoToOutcome.FAILED))
        assertEquals(
            listOf(PublishGoToResult(GoToResult(oksan, GoToOutcome.FAILED, t0)), Speak("내비에 넣지 못했어요. 화면에서 다시 눌러 주세요")),
            failed,
        )
        // 화면의 다시 시도 버튼 = 같은 곳 GoTo
        assertEquals(listOf(PublishGoToResult(null), StartGoTo(2, oksan, parked = false)), r.on(GoTo(oksan)))
        val ok = r.on(GoToFinished(2, GoToOutcome.ROUTE_STARTED))
        assertEquals("다시 시도가 성공하면 그 질문의 수락으로 알린다", Report("p1", true), ok.last())
    }

    @Test
    fun liveStopFromTheCoreIsUsedForTheResultAndSpeech() {
        val r = Rig()
        val tbt = PromptFakes.stop("tbt:죽암휴게소", "죽암휴게소", routeDistanceM = 4_400.0)
        r.tick(Prompt("p9", PromptKind.LAST_CHANCE, "죽암휴게소 들를까요?", tbt, expiresAt = 0))
        r.on(Tapped(r.m.current!!.key, true))
        val live = tbt.copy(routeDistanceM = 2_600.0)
        val done = r.on(GoToFinished(1, GoToOutcome.ON_ROUTE, stop = live))
        assertEquals(PublishGoToResult(GoToResult(live, GoToOutcome.ON_ROUTE, t0)), done[0])
        assertEquals(Speak("죽암휴게소는 경로 위에 있어요, 2.6km 앞 진입로로 들어가세요"), done[1])
        assertEquals("경로 위 안내도 수락이다 (엔진이 그곳을 수락한 정차로 추적한다)", Report("p9", true), done[2])
    }

    @Test
    fun aResultForAnotherRequestIsIgnored() {
        val r = Rig()
        r.on(GoTo(oksan))
        assertEquals(emptyList<PromptEffect>(), r.on(GoToFinished(99, GoToOutcome.WAYPOINT_ADDED)))
        assertEquals(oksan, r.m.goToInFlight)
    }

    @Test
    fun oneNaviCommandAtATimeAndTheLatestQueuedWins() {
        val r = Rig()
        r.on(GoTo(oksan))
        assertEquals("실행 중이면 줄을 선다", emptyList<PromptEffect>(), r.on(GoTo(jukam)))
        assertEquals(emptyList<PromptEffect>(), r.on(GoTo(mangHyang)))
        assertEquals("같은 곳 두 번(두 번 누름)은 무시", emptyList<PromptEffect>(), r.on(GoTo(oksan)))
        assertEquals(emptyList<PromptEffect>(), r.on(GoTo(mangHyang)))
        val done = r.on(GoToFinished(1, GoToOutcome.WAYPOINT_ADDED))
        assertEquals("끝나면 마지막에 줄 선 곳을 시작한다 (죽암은 망향에 밀렸다)", StartGoTo(3, mangHyang, parked = false), done.last())
        assertTrue("방금 결과를 같은 묶음에서 지우지 않는다", done.none { it == PublishGoToResult(null) })
        assertNull(r.m.goToQueued)
    }

    @Test
    fun acceptingAPlaceAlreadyOnItsWayReportsThatPrompt() {
        // 급해 목록에서 옥산을 눌러 실행 중인데 엔진 질문도 옥산이다: 명령은 하나, 수락은 그 질문으로 알린다
        val r = Rig()
        r.on(GoTo(oksan))
        r.tick(p1)
        assertEquals(listOf(CancelVoice, Show(null)), r.on(Tapped(key(p1), true)))
        assertEquals(Report("p1", true), r.on(GoToFinished(1, GoToOutcome.WAYPOINT_ADDED)).last())
    }

    @Test
    fun aCardForThePlaceJustAddedIsAnswered() {
        // 카드가 옥산을 묻는 동안 계획 화면에서 옥산을 눌렀다. 성공하면 카드는 이미 답한 것이다
        val r = Rig()
        r.tick(p1)
        r.on(GoTo(oksan))
        val done = r.on(GoToFinished(1, GoToOutcome.WAYPOINT_ADDED))
        assertEquals(
            "카드를 먼저 내리고(음성 멈춤) 결과를 말한다. 반대 순서면 CancelVoice가 방금 시작한 안내를 끊는다",
            listOf(
                PublishGoToResult(GoToResult(oksan, GoToOutcome.WAYPOINT_ADDED, t0)),
                CancelVoice, Show(null),
                Speak("옥산휴게소를 경유지로 넣었어요"),
                Report("p1", true),
            ),
            done,
        )
        assertNull(r.m.current)
        assertEquals("닫은 질문은 다시 띄우지 않는다", emptyList<PromptEffect>(), r.tick(p1))
        // 실패하면 카드는 남아 있다
        val r2 = Rig()
        r2.tick(p1)
        r2.on(GoTo(oksan))
        r2.on(GoToFinished(1, GoToOutcome.FAILED))
        assertEquals(key(p1), r2.m.current?.key)
    }

    @Test
    fun aFailedResultStaysVisibleWhenTheQueuedGoToStarts() {
        val r = Rig()
        r.on(GoTo(oksan))
        r.on(GoTo(jukam))
        val done = r.on(GoToFinished(1, GoToOutcome.FAILED))
        assertEquals(
            "실패 카드가 보여야 '다시 눌러 주세요'가 맞다",
            listOf(
                PublishGoToResult(GoToResult(oksan, GoToOutcome.FAILED, t0)),
                Speak("내비에 넣지 못했어요. 화면에서 다시 눌러 주세요"),
                StartGoTo(2, jukam, parked = false),
            ),
            done,
        )
    }

    @Test
    fun aGoToThatNeverFinishesIsFailedByTheWatchdog() {
        // 결과가 끝내 오지 않으면(실행 취소, 내비 스레드가 막힘, Error) 뒤의 모든 들르기가 영영 줄만 선다
        val r = Rig()
        r.on(GoTo(oksan))
        r.on(GoTo(jukam))
        r.elapsed = PromptMachine.GO_TO_WATCHDOG_MS - 1
        assertEquals(emptyList<PromptEffect>(), r.tick(null))
        r.elapsed = PromptMachine.GO_TO_WATCHDOG_MS
        assertEquals(
            listOf(
                PublishGoToResult(GoToResult(oksan, GoToOutcome.FAILED, t0)),
                Speak("내비에 넣지 못했어요. 화면에서 다시 눌러 주세요"),
                StartGoTo(2, jukam, parked = false),
            ),
            r.tick(null),
        )
        assertEquals("늦게 온 결과는 버린다", emptyList<PromptEffect>(), r.on(GoToFinished(1, GoToOutcome.WAYPOINT_ADDED)))
        assertEquals(jukam, r.m.goToInFlight)
    }

    @Test
    fun theWatchdogAlsoFreesAGoToCancelledByAWorldSwitch() {
        val r = Rig()
        r.tick(p1)
        r.on(Tapped(key(p1), true))
        r.on(Reset) // 코어가 실행을 취소해 결과가 오지 않는다
        r.elapsed = PromptMachine.GO_TO_WATCHDOG_MS
        val freed = r.tick(null)
        assertTrue("옛 세계 질문은 보고하지 않는다", freed.none { it is Report })
        assertNull(r.m.goToInFlight)
        assertEquals(StartGoTo(2, jukam, parked = false), r.on(GoTo(jukam)).last())
    }

    @Test
    fun twoAcceptsForTheSamePlaceAreBothReported() {
        val restAtOksan = Prompt("p8", PromptKind.REST_SUGGESTION, "옥산휴게소에서 쉬어 갈까요?", oksan, expiresAt = 0)
        val r = Rig()
        r.tick(p1)
        r.on(Tapped(key(p1), true))
        r.tick(restAtOksan)
        r.on(Tapped(key(restAtOksan, 2), true))
        val done = r.on(GoToFinished(1, GoToOutcome.WAYPOINT_ADDED))
        assertEquals(listOf(Report("p1", true), Report("p8", true)), done.filterIsInstance<Report>())
    }

    @Test
    fun theRetryLinkIsShortLivedAndDismissable() {
        val r = Rig()
        r.tick(p1)
        r.on(Tapped(key(p1), true))
        r.on(GoToFinished(1, GoToOutcome.FAILED))
        // 30분 뒤 계획 화면에서 같은 곳을 누른 것은 그 질문의 다시 시도가 아니다
        r.elapsed = PromptMachine.RETRY_LINK_MS + 1
        r.on(GoTo(oksan))
        assertTrue(r.on(GoToFinished(2, GoToOutcome.WAYPOINT_ADDED)).none { it is Report })

        val r2 = Rig()
        r2.tick(p1)
        r2.on(Tapped(key(p1), true))
        r2.on(GoToFinished(1, GoToOutcome.FAILED))
        assertEquals(listOf(PublishGoToResult(null)), r2.on(PromptEvent.DismissGoToResult))
        r2.on(GoTo(oksan))
        assertTrue("실패 카드를 닫았으면 다시 시도가 아니다", r2.on(GoToFinished(2, GoToOutcome.WAYPOINT_ADDED)).none { it is Report })
    }

    @Test
    fun aGoToFromBeforeResetDoesNotAnswerTheNewEnginesCard() {
        val r = Rig()
        r.tick(p1)
        r.on(Tapped(key(p1), true))
        r.on(Reset)
        val newP1 = p1.copy(id = "e2-p1")
        r.tick(newP1)
        val done = r.on(GoToFinished(1, GoToOutcome.WAYPOINT_ADDED))
        assertTrue(done.none { it is Report || it == Show(null) })
        assertEquals("e2-p1", r.m.current?.prompt?.id)
    }

    @Test
    fun goToUsesTheLastKnownParkedState() {
        val r = Rig()
        r.tick(null, parked = true)
        assertEquals(StartGoTo(1, oksan, parked = true), r.on(GoTo(oksan)).last())
    }

    @Test
    fun aDoubleAcceptRunsOneNaviCommand() {
        // P7, 구조 리뷰 D7: 음성 '응'과 버튼이 함께 와도 두 번째는 지금 질문이 아니다
        val r = Rig()
        r.tick(p1)
        val first = r.on(Heard(key(p1), YesNo.YES))
        val second = r.on(Tapped(key(p1), true))
        assertEquals(1, first.count { it is StartGoTo })
        assertEquals(emptyList<PromptEffect>(), second)
    }

    @Test
    fun resultsCarryRisingTimestampsEvenWhenTheCoachClockStandsStillOrGoesBack() {
        // 화면은 at으로 새 결과를 가린다. 같은 순간에 끝난 두 들르기나, 시연을 꺼서 코치 시각이 뒤로 간 뒤의 결과도 at이 겹치지 않는다
        val r = Rig()
        r.on(GoTo(oksan))
        r.on(GoTo(jukam))
        val first = r.on(GoToFinished(1, GoToOutcome.WAYPOINT_ADDED)).filterIsInstance<PublishGoToResult>().single().result!!
        val second = r.on(GoToFinished(2, GoToOutcome.WAYPOINT_ADDED)).filterIsInstance<PublishGoToResult>().single().result!!
        assertEquals(t0, first.at)
        assertEquals("같은 코치 시각이면 1 키운다", t0 + 1, second.at)
        r.on(Reset)
        r.coach = t0 - 4 * 3_600_000L // 시연(14시)을 끄고 실제(10시)로
        r.on(GoTo(mangHyang))
        val third = r.on(GoToFinished(3, GoToOutcome.ROUTE_STARTED)).filterIsInstance<PublishGoToResult>().single().result!!
        assertEquals("초기화를 넘어서도 앞 결과보다 뒤", t0 + 2, third.at)
    }

    @Test
    fun anOldResultIsWithdrawnAfterTenMinutesLikeADismiss() {
        val r = Rig()
        r.tick(p1)
        r.on(Tapped(key(p1), true))
        r.elapsed = 5_000
        r.on(GoToFinished(1, GoToOutcome.FAILED))
        r.elapsed = 5_000 + PromptMachine.GO_TO_RESULT_TTL_MS - 1
        assertEquals(emptyList<PromptEffect>(), r.tick(null))
        r.elapsed = 5_000 + PromptMachine.GO_TO_RESULT_TTL_MS
        assertEquals("지나간 실패 카드·다시 시도가 남지 않는다", listOf(PublishGoToResult(null)), r.tick(null))
        assertEquals("한 번만", emptyList<PromptEffect>(), r.tick(null))
    }

    @Test
    fun aNewResultRestartsTheTenMinutesAndAClearedResultHasNoneLeft() {
        val r = Rig()
        r.on(GoTo(oksan))
        r.on(GoToFinished(1, GoToOutcome.FAILED))
        r.elapsed = 9 * 60_000L
        r.on(GoTo(jukam)) // 새 들르기를 시작하면 지난 결과를 내린다
        r.elapsed = 9 * 60_000L + 30_000
        r.on(GoToFinished(2, GoToOutcome.WAYPOINT_ADDED))
        r.elapsed = 11 * 60_000L
        assertEquals("앞 결과의 10분으로 새 결과를 거두지 않는다", emptyList<PromptEffect>(), r.tick(null))
        r.on(PromptEvent.DismissGoToResult)
        r.elapsed = 30 * 60_000L
        assertEquals("닫은 결과는 다시 거둘 것이 없다", emptyList<PromptEffect>(), r.tick(null))
    }

    // ---------------------------------------------------------------- 엔진이 진실 (D1, D2, UI #1)

    @Test
    fun targetPassedMeansTheCardIsGoneAndAcceptingDoesNothing() {
        val r = Rig()
        r.tick(p1)
        r.tick(null) // 엔진: 500m 안으로 들어와 질문을 내렸다
        assertNull(r.m.current)
        assertEquals("지나친 곳으로 경유지를 넣지 않는다 (유턴)", emptyList<PromptEffect>(), r.on(Tapped(key(p1), true)))
        assertNull(r.m.goToInFlight)
    }

    @Test
    fun anAnsweredPromptIsNotAskedAgainWhileTheEngineCatchesUp() {
        val r = Rig()
        r.tick(p1)
        r.on(Heard(key(p1), YesNo.NO))
        assertEquals(emptyList<PromptEffect>(), r.tick(p1))
        assertNull(r.m.current)
    }

    @Test
    fun anExpiredEngineCardIsNotShownAgainEvenIfStillOffered() {
        val r = Rig()
        r.tick(p1)
        r.elapsed = engineMs
        r.on(Expired(key(p1)))
        r.elapsed = engineMs + 1_000
        assertEquals(emptyList<PromptEffect>(), r.tick(p1))
    }

    @Test
    fun aParkedWithdrawalIsFinalButAnUnshownOfferWaitsForDriving() {
        val r = Rig()
        r.tick(checkIn)
        r.tick(checkIn, parked = true)
        assertEquals("P에서 거둔 질문은 출발해도 다시 띄우지 않는다", emptyList<PromptEffect>(), r.tick(checkIn, parked = false))
        // 정차 중에 처음 온 주행 질문은 띄우지 않았을 뿐이니 출발하면 띄운다
        val r2 = Rig()
        r2.tick(rest, parked = true)
        assertEquals(showEffects(rest), r2.tick(rest, parked = false))
    }

    @Test
    fun aDrivingPromptDisplacedByAnotherCardDoesNotReturnAfterParking() {
        // 엔진이 급해 결과로 주행 질문을 밀어냈고 그 사이 차를 세웠다. 급해 카드가 끝난 뒤 엔진이 그 주행 질문을 다시 내도 P에서는 닫는다
        val r = Rig()
        r.tick(p1)
        r.tick(urgent)
        r.tick(urgent, parked = true)
        r.on(Tapped(key(urgent, 2), false))
        assertEquals(emptyList<PromptEffect>(), r.tick(p1, parked = true))
        assertEquals("띄웠던 주행 질문은 P에서 닫는다", emptyList<PromptEffect>(), r.tick(p1, parked = false))
    }

    @Test
    fun aStaleHeardFromACancelledAskDoesNotTouchTheReshownCard() {
        // 취소된 음성 작업이 늦게 보낸 대답이 다시 띄운 같은 질문(새 음성 작업 중)에 닿으면 상태가 어긋난다
        val r = Rig()
        r.tick(p1)
        r.tick(urgent) // 엔진이 급해 결과로 밀어냈다: p1의 묻기가 취소된다
        r.on(Tapped(key(urgent, 2), false))
        r.tick(p1)
        assertEquals(key(p1, 3), r.m.current!!.key)
        assertEquals(emptyList<PromptEffect>(), r.on(Heard(key(p1, 1), YesNo.UNKNOWN)))
        assertTrue(r.m.state is PromptState.Asking)
        assertEquals("새 음성 작업을 멈춘다", listOf(CancelVoice, Show(null)), r.tick(null))
    }

    @Test
    fun anInfoTickDoesNotHideTheCardAndTheCardIsNotReasked() {
        // 엔진 출력은 질문 하나뿐이다. 안내가 나온 틱에는 카드 질문이 살아 있는지 모르니 카드를 그대로 두고 다음 틱에 판단한다
        val r = Rig()
        r.tick(p1)
        r.tick(info)
        assertEquals("엔진이 다시 p1을 내면 그대로 (다시 묻지 않는다)", emptyList<PromptEffect>(), r.tick(p1))
        assertEquals(key(p1), r.m.current!!.key)
        assertEquals("엔진이 안내 뒤 p1을 버렸으면 다음 틱에 내린다", listOf(Show(null)), r.tick(null).filterNot { it is CancelVoice })
    }

    @Test
    fun anInfoAnswerIsNotCutOffWhenTheCardGoesAway() {
        // 엔진은 사용자 요청(얼마나 남았어)에 답하며 떠 있던 질문을 버린다(엔진 감사 P1). 다음 틱에 카드를 내릴 때
        // 멈출 음성이 없어야 한다 (CancelVoice가 1초 전에 시작한 대답을 끊지 않게)
        val r = Rig()
        r.tick(p1)
        assertEquals(listOf(CancelVoice, Speak(info.speech), Report(info.id, true)), r.tick(info))
        assertEquals(listOf(Show(null)), r.tick(null))
    }

    @Test
    fun anInfoIsSpokenOnce() {
        val r = Rig()
        r.tick(info)
        assertEquals(emptyList<PromptEffect>(), r.tick(info))
    }

    @Test
    fun aDisplacedEngineCardReturnsIfTheEngineStillOffersIt() {
        // 엔진이 다른 질문(또는 사용자 응답)으로 잠깐 밀어냈다가 다시 내면 그것이 엔진의 지금 질문이다
        val r = Rig()
        r.tick(rest)
        r.tick(p1)
        assertEquals(listOf(CancelVoice) + showEffects(rest, seq = 3), r.tick(rest))
    }

    // ---------------------------------------------------------------- 엔진의 급해 결과 (서비스 감사 D2·D7)

    @Test
    fun theEnginesUrgentResultDisplacesTheCardAndTheCardReturnsAfterTheAnswer() {
        // 급해 결과도 엔진의 질문이다(코어가 급해를 엔진에 알리면 엔진이 1등 장소로 묻는다). 답한 뒤 엔진이 아직 내는 질문은 다시 띄운다
        val r = Rig()
        r.tick(p1)
        assertEquals(listOf(CancelVoice) + showEffects(urgent, seq = 2), r.tick(urgent))
        r.elapsed = 20_000
        assertEquals(listOf(CancelVoice, Show(null), Report(urgent.id, false)), r.on(Tapped(key(urgent, 2), false)))
        assertEquals("급해 카드가 끝나면 엔진이 아직 내는 질문을 다시 띄우고 묻는다 (새 key)", showEffects(p1, at = 20_000, seq = 3), r.tick(p1))
    }

    // ---------------------------------------------------------------- 초기화 (세계·엔진 교체, 기록 지우기)

    @Test
    fun aStaleTapAfterResetDoesNothingEvenIfTheNewEngineReusesTheId() {
        val r = Rig()
        r.tick(checkIn.copy(id = "p1"))
        val oldKey = r.m.current!!.key
        r.on(Reset)
        assertEquals(emptyList<PromptEffect>(), r.on(Tapped(oldKey, true)))
        // 새 엔진이 id를 다시 p1부터 셌다 (엔진 감사 P3). 새 질문은 띄우고, 옛 카드의 대답은 새 질문에 닿지 않는다
        assertEquals(showEffects(p1, seq = 2), r.tick(p1))
        assertEquals(emptyList<PromptEffect>(), r.on(Tapped(oldKey, true)))
        assertEquals(emptyList<PromptEffect>(), r.on(Heard(oldKey, YesNo.YES)))
        assertEquals(PromptKind.LAST_CHANCE, r.m.current!!.prompt.kind)
    }

    @Test
    fun resetForgetsAnsweredIdsSoARebuiltEngineIsAsked() {
        val r = Rig()
        r.tick(p1)
        r.on(Heard(key(p1), YesNo.NO))
        r.on(Reset)
        assertEquals(showEffects(p1, seq = 2), r.tick(p1))
    }

    @Test
    fun resetUnlinksTheGoToInFlightAndDropsTheQueuedOne() {
        val r = Rig()
        r.tick(p1)
        r.on(Tapped(key(p1), true))
        r.on(GoTo(jukam))
        r.on(Reset)
        val done = r.on(GoToFinished(1, GoToOutcome.WAYPOINT_ADDED))
        assertTrue("이미 보낸 명령의 결과는 사실이라 알린다", done.any { it is PublishGoToResult && it.result != null })
        assertTrue("옛 엔진의 질문을 새 엔진에 알리지 않는다", done.none { it is Report })
        assertTrue("줄 선 명령은 버렸다", done.none { it is StartGoTo })
    }

    // ---------------------------------------------------------------- 수명은 실제 시간 (구조 리뷰 B5)

    @Test
    fun theCardLifetimeIgnoresTheCoachClock() {
        val r = Rig()
        r.tick(p1)
        r.coach += 4 * 3_600_000L // 시연 배속으로 코치 시각이 몇 시간 흘러도
        r.elapsed = 10_000
        assertEquals(emptyList<PromptEffect>(), r.tick(p1))
        r.elapsed = engineMs
        assertEquals(listOf(CancelVoice, Show(null)), r.tick(p1))
    }

    @Test
    fun aSlowVoiceLeavesATapWindowButTheLifetimeStaysBounded() {
        val r = Rig()
        r.tick(p1)
        r.elapsed = 40_000
        assertEquals("음성이 늦게 끝나도 10초는 누를 수 있다", listOf(ScheduleExpiry(key(p1), 50_000)), r.on(Heard(key(p1), YesNo.UNKNOWN)))
        r.elapsed = 52_000
        assertEquals("상한: 수명 + 10초", listOf(ScheduleExpiry(key(p1), engineMs + PromptMachine.MIN_TAP_MS)), r.on(Heard(key(p1), YesNo.UNKNOWN)))
        r.elapsed = engineMs + PromptMachine.MIN_TAP_MS
        assertEquals(listOf(Show(null)), r.on(Expired(key(p1))))
    }

    // ---------------------------------------------------------------- 음성 끄기 (UI 감사 #12)

    @Test
    fun voiceOffNeverSpeaksOrListens() {
        val r = Rig(voice = false)
        val all = mutableListOf<PromptEffect>()
        all += r.tick(info)
        all += r.tick(freshAir)
        all += r.on(Tapped(key(freshAir), true))
        all += r.tick(p1)
        all += r.on(Tapped(key(p1, 2), true))
        all += r.on(GoToFinished(1, GoToOutcome.WAYPOINT_ADDED))
        all += r.tick(urgent)
        assertTrue(all.toString(), all.none { it is Ask || it is Speak })
        assertTrue(all.contains(FreshAir(announce = false)))
        assertTrue("카드는 보인다", all.contains(Show(shown(urgent, 3))))
    }

    // ---------------------------------------------------------------- 화면이 쓰는 값

    @Test
    fun currentCardExposesWhatTheCoreNeeds() {
        val r = Rig()
        r.tick(p1, next = nextFor(oksan, jamReason))
        val c = r.m.current!!
        assertEquals("IntentParser의 activePromptId로 쓴다", key(p1), c.key)
        assertEquals("promptStop을 찾는 데 쓴다", oksan, c.prompt.stop)
        assertEquals(listOf(jamReason), c.reasons)
        r.tick(p1, next = nextFor(jukam)) // 추천이 다음 곳으로 넘어가도
        assertEquals("카드 이유는 띄울 때의 것 (UI 감사 #6)", listOf(jamReason), r.m.current!!.reasons)
    }
}
