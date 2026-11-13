package dev.shimpyo.app.ui.drive

import dev.shimpyo.coach.CandidatesState
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.GoToFailure
import dev.shimpyo.coach.GoToOutcome
import dev.shimpyo.coach.GoToResult
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gauge
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PitStopPlan
import dev.shimpyo.engine.PlannedStop
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.RankedStop
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.Recommendation
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 주행 화면 모델(stopCard, driveActions, 게이지). 감사 번호는 2026-11-11 UI 감사 보고서의 결함 번호다.
 * 상태별 표시 규칙의 표: docs/design/coach-layer/ui.md
 */
class DriveModelsTest {

    private fun stop(id: String, name: String, km: Double, eta: Double? = null) = StopCandidate(
        id = id, name = name, kind = StopKind.HIGHWAY_REST_AREA, lat = 36.4, lon = 127.4,
        facilities = Facilities(), parkable = true, routeDistanceM = km * 1_000.0, etaS = eta, directionOk = true,
    )

    private fun rec(s: StopCandidate, vararg reasons: Reason) = Recommendation(s, setOf(Purpose.RESTROOM), reasons.toList())

    private val running = CoachUiState(serviceRunning = true, candidatesState = CandidatesState.READY)

    // ---------------------------------------------------------------- 빈 상태·추천 없음 (#18)

    @Test
    fun withoutRecommendationTheNearestRestroomAheadIsShown() {
        // 회귀: 마지막 기회를 지나 정체에 들어가자 '다음 화장실을 고르는 중이에요'에 오래 머물렀다 (2026-11-11 에뮬레이터)
        val state = running.copy(
            guiding = true,
            ahead = listOf(stop("sintanjin", "신탄진졸음쉼터", 31.0), stop("jukam", "죽암휴게소(부산방향)", 24.0)),
            congestion = listOf(CongestionSegment(startDistanceM = 0.0, lengthM = 18_000.0, durationS = 3_000.0)),
        )
        val card = stopCard(state, driving = true)
        assertEquals("다음 화장실", card.context)
        assertEquals("죽암휴게소(부산방향)", card.title)
        assertEquals(listOf("정체 뒤"), card.chips)
    }

    @Test
    fun emptyCardFollowsCandidatesStateAndNeverClaimsNoRestroomBeforeReady() {
        fun title(s: CandidatesState) = stopCard(CoachUiState(serviceRunning = true, candidatesState = s), driving = true).title
        assertEquals("정차 데이터를 읽는 중이에요", title(CandidatesState.LOADING_DATA))
        assertEquals("위치를 확인하는 중이에요", title(CandidatesState.NO_LOCATION))
        assertEquals("앞쪽에 화장실 정보가 없어요", title(CandidatesState.READY))
        // 기본값(시작 직후)은 LOADING_DATA다
        assertNotEquals("앞쪽에 화장실 정보가 없어요", stopCard(CoachUiState(serviceRunning = true), driving = true).title)
    }

    @Test
    fun nearestAheadIgnoresStopsBeyondTheDestination() {
        val s = running.copy(guiding = true, remainDistanceM = 10_000.0, ahead = listOf(stop("far", "목적지 뒤 휴게소", 30.0)))
        assertEquals("앞쪽에 화장실 정보가 없어요", stopCard(s, driving = true).title)
    }

    @Test
    fun stoppedServiceDoesNotLeaveALiveLookingCardOrButtons() {
        val p = Prompt("p1", PromptKind.LAST_CHANCE, "들를까요?", stop("a", "안성휴게소", 3.0), Long.MAX_VALUE)
        val dead = running.copy(
            serviceRunning = false, prompt = p,
            restroom = Gauge(40, Level.WATCH), fatigue = Gauge(80, Level.OK),
        )
        assertEquals("코치가 멈췄어요", stopCard(dead, driving = true).title)
        assertEquals(DriveActions.None, driveActions(dead, driving = true))
        assertNull(restroomGauge(dead).minutes)
        assertEquals("코치 멈춤", restroomGauge(dead).reason)
        assertNull(fatigueGauge(dead).fraction)
        // 시작 전(아직 한 번도 계산하지 않음)은 '준비하고 있어요'
        assertEquals("준비하고 있어요", stopCard(CoachUiState(), driving = true).title)
    }

    // ---------------------------------------------------------------- 질문 카드 (#1, #6, #11)

    @Test
    fun questionCardShowsTheLiveDistanceNotTheOneAtAskTime() {
        val asked = stop("oksan", "옥산휴게소", 3.9)
        val prompt = Prompt("q1", PromptKind.LAST_CHANCE, "앞에 1시간 정체가 있어요. 들를까요?", asked, expiresAt = Long.MAX_VALUE)
        val state = running.copy(
            guiding = true, prompt = prompt,
            next = rec(stop("oksan", "옥산휴게소", 1.2), Reason("LAST_CHANCE_CONGESTION", "정체 전 마지막")),
        )
        val card = stopCard(state, driving = true)
        assertEquals("옥산휴게소", card.title)
        assertTrue(card.meta!!, card.meta!!.startsWith("1.2km"))

        // 추천이 다음 곳으로 넘어갔어도 질문 장소의 지금 위치(promptStop)를 알면 그 거리를 쓴다 (2026-11-11: 2.1km에 멈춰 있었다)
        val moving = state.copy(next = rec(stop("jukam", "죽암휴게소", 20.0)), promptStop = stop("oksan", "옥산휴게소", 0.8))
        assertTrue(stopCard(moving, driving = true).meta!!.startsWith("800m"))
    }

    @Test
    fun passedPromptStopDoesNotShowAskTimeDistance() {
        // #1 방어선: 질문 장소가 후보 목록에서 빠졌다(promptStop == null, 추천은 다른 곳). 물은 때의 '3.9km'를 지금 거리처럼 보이지 않는다
        val p = Prompt("p1", PromptKind.LAST_CHANCE, "앞에 1시간 정체가 있어요. 들를까요?", stop("oksan", "옥산휴게소", 3.9), Long.MAX_VALUE)
        val state = running.copy(guiding = true, prompt = p, promptStop = null, next = rec(stop("jukam", "죽암휴게소", 20.0)))
        val meta = stopCard(state, driving = true).meta.orEmpty()
        assertFalse("ask-time distance for a passed stop: $meta", meta.contains("km"))
        assertEquals("경로 위", meta)
    }

    @Test
    fun gapPromptKeepsContextWhenRecommendationMovesOn() {
        // #6: 질문을 띄운 때의 이유(promptReasons)로 맥락과 칩을 정한다
        val oksan = stop("oksan", "옥산휴게소", 3.0)
        val gap = Reason("LAST_CHANCE_GAP", "이곳을 지나면 다음 화장실까지 50분 걸려요")
        val p = Prompt("p1", PromptKind.LAST_CHANCE, "옥산휴게소를 지나면 50분 동안 화장실이 없어요. 들를까요?", oksan, Long.MAX_VALUE)
        val asked = running.copy(guiding = true, prompt = p, promptReasons = listOf(gap), next = rec(oksan, gap))
        assertEquals("화장실 공백 전 마지막 기회", stopCard(asked, driving = true).context)

        val moved = asked.copy(next = rec(stop("jukam", "죽암휴게소", 40.0)), promptStop = stop("oksan", "옥산휴게소", 1.8))
        val card = stopCard(moved, driving = true)
        assertEquals("화장실 공백 전 마지막 기회", card.context)
        assertTrue(card.chips.toString(), card.chips.contains("공백 50분"))
    }

    @Test
    fun lastChanceWithoutReasonsIsNeverLabelledNearestRestroom() {
        // 이유를 모르는 코치(promptReasons 비어 있음)에서도 마지막 기회를 '가까운 화장실'로 부르지 않는다
        val p = Prompt("p1", PromptKind.LAST_CHANCE, "옥산휴게소를 지나면 50분 동안 화장실이 없어요. 들를까요?", stop("oksan", "옥산휴게소", 3.0), Long.MAX_VALUE)
        assertEquals("화장실 공백 전 마지막 기회", stopCard(running.copy(prompt = p), driving = true).context)
        val jam = p.copy(speech = "앞에 40분 정체가 있어요. 다음 옥산휴게소가 정체 전 마지막 화장실이에요. 들를까요?")
        assertEquals("정체 전 마지막 화장실", stopCard(running.copy(prompt = jam), driving = true).context)
    }

    @Test
    fun stoplessPromptTitleIsCondensed() {
        // #11: 40sp·3줄에는 약 51자. 화면은 TTS 문장을 줄여 질문만 보인다
        val speech = "졸릴 땐 쉬기만 하기보다 커피 한 잔 뒤 15분 이내로 눈을 붙이는 게 좋아요. 가까운 쉼터를 찾아 들를까요?"
        val card = stopCard(running.copy(prompt = Prompt("p", PromptKind.REST_SUGGESTION, speech, null, Long.MAX_VALUE)), driving = true)
        assertEquals("가까운 쉼터를 찾아 들를까요?", card.title)
        assertTrue(card.sentence)
    }

    @Test
    fun shortQuestionAndLead() {
        val checkIn = Prompt("c", PromptKind.CHECK_IN, "1시간 35분째 운전 중이에요. 혹시 졸리세요?", null, Long.MAX_VALUE)
        assertEquals("혹시 졸리세요?", shortQuestion(checkIn))
        assertEquals("1시간 35분째 운전 중이에요", promptLead(checkIn))
        // 숫자 안의 점(3.5km)에서 문장을 자르지 않는다
        val info = Prompt("i", PromptKind.INFO, "다음 화장실은 3.5km 앞 옥산휴게소예요. 쉬지 않고 운전할 수 있는 시간은 약 40분이에요.", null, Long.MAX_VALUE)
        assertEquals("다음 화장실은 3.5km 앞 옥산휴게소예요.", shortQuestion(info))
        // 질문 문장이 길면 종류별 짧은 질문
        val longQ = checkIn.copy(speech = "지금은 졸음이 오기 쉬운 시간이에요. 오늘 많이 피곤하시면 잠깐 쉬었다 가시는 게 어떨까요, 혹시 졸리세요?")
        assertEquals("혹시 졸리세요?", shortQuestion(longQ))
    }

    @Test
    fun theDepartureQuestionSaysWhyAndOffersToGoFirst() {
        // 출발 전 한마디 (엔진 DepartureCheckTest): 서 있고 목적지가 있을 때. 정차지가 없는 카드이고 버튼은 '다녀올게요'
        val p = Prompt(
            "d", PromptKind.DEPARTURE,
            "도착까지 약 1시간 10분이에요. 7살 아이는 45분쯤 뒤 화장실을 찾을 것 같아요. 출발 전에 다녀오세요", null, Long.MAX_VALUE,
        )
        val state = running.copy(prompt = p, promptReasons = listOf(Reason("DEPARTURE_TRIP", "도착까지 1시간 10분")))
        val card = stopCard(state, driving = false)
        assertEquals("출발 전 화장실", card.context)
        assertEquals("출발 전에 화장실 다녀오세요", card.title)
        assertEquals("도착까지 약 1시간 10분이에요", card.meta)
        assertTrue("도착 시간 칩: ${card.chips}", card.chips.contains("도착까지 1시간 10분"))
        assertEquals("다녀올게요", acceptLabel(p))
    }

    @Test
    fun checkInLabelFollowsItsReason() {
        // 연속 운전 시간으로 묻는 상태 확인에 '운전 패턴 변화'라고 붙었다 (2026-11-11 에뮬레이터)
        fun label(speech: String) = stopCard(running.copy(prompt = Prompt("c", PromptKind.CHECK_IN, speech, expiresAt = Long.MAX_VALUE)), driving = true).context
        assertEquals("졸음 확인", label("1시간 35분째 운전 중이에요. 혹시 졸리세요?"))
        assertEquals("운전 패턴 변화", label("운전 패턴이 이번 주행 초반과 조금 달라요. 혹시 졸리세요?"))
        assertEquals("졸음 확인", label("지금은 졸음이 오기 쉬운 시간이에요. 혹시 졸리세요?"))
    }

    @Test
    fun cardTextRespectsRestrictedStringLength() {
        // #26: LIMIT_STRING_LENGTH의 최대 글자 수를 넘는 문구는 자른다
        val p = Prompt("i", PromptKind.INFO, "가까운 곳에서 들를 수 있는 화장실을 찾지 못했어요.", null, Long.MAX_VALUE)
        val card = stopCard(running.copy(prompt = p), driving = true, maxChars = 10)
        assertEquals(10, card.title.length)
        assertTrue(card.title.endsWith("…"))
    }

    // ---------------------------------------------------------------- 들르기 결과 (#3)

    @Test
    fun acceptedStopIsNotClaimedAddedUntilTheNaviConfirms() {
        val a = stop("a", "안성휴게소", 4.0)
        val accepted = running.copy(next = rec(a, Reason("NEXT_ACCEPTED", "경유지로 넣기로 한 곳이에요")))
        assertEquals("들르기로 한 곳", stopCard(accepted, driving = true).context)
        assertFalse(stopCard(accepted, driving = true).chips.contains("들르기로 함"))
        assertEquals("경유지로 넣었어요", stopCard(accepted.copy(goToResult = GoToResult(a, GoToOutcome.WAYPOINT_ADDED, 1L)), driving = true).context)
        assertEquals("경로 위 · 진입로로 들어가세요", stopCard(accepted.copy(goToResult = GoToResult(a, GoToOutcome.ON_ROUTE, 1L)), driving = true).context)
        // 다른 곳의 결과는 이 카드의 맥락을 바꾸지 않는다
        val other = GoToResult(stop("b", "천안휴게소", 30.0), GoToOutcome.WAYPOINT_ADDED, 1L)
        assertEquals("들르기로 한 곳", stopCard(accepted.copy(goToResult = other), driving = true).context)
    }

    @Test
    fun failedWaypointShowsRetryCardNotAdded() {
        val a = stop("a", "안성휴게소", 4.0)
        val s = running.copy(next = rec(a, Reason("NEXT_ACCEPTED", "…")), goToResult = GoToResult(a, GoToOutcome.FAILED, 1L))
        val card = stopCard(s, driving = true)
        assertEquals("경유지를 넣지 못했어요", card.context)
        assertEquals("안성휴게소", card.title)
        assertEquals(DriveActions.RetryGoTo(a), driveActions(s, driving = true))
        // 질문이 오면 질문이 먼저다 (시간이 급한 결정)
        val p = Prompt("p", PromptKind.LAST_CHANCE, "들를까요?", stop("o", "옥산휴게소", 3.0), Long.MAX_VALUE)
        assertTrue(driveActions(s.copy(prompt = p), driving = true) is DriveActions.Answer)
    }

    @Test
    fun tbtOnlyStopAcceptAnswersOnRouteNotWaypoint() {
        // 좌표 없는 경로 위 시설(TBT)도 '들르기'로 받는다: 코치가 ON_ROUTE로 답하고 카드는 '경유지로 넣었어요'라고 하지 않는다
        val tbt = stop("tbt:신탄진휴게소", "신탄진휴게소", 4.0)
        assertEquals("들르기", acceptLabel(Prompt("p", PromptKind.LAST_CHANCE, "들를까요?", tbt, Long.MAX_VALUE)))
        val s = running.copy(next = rec(tbt, Reason("NEXT_ACCEPTED", "…")), goToResult = GoToResult(tbt, GoToOutcome.ON_ROUTE, 1L))
        assertNotEquals("경유지로 넣었어요", stopCard(s, driving = true).context)
    }

    @Test
    fun freshAirCannotBeAcceptedWhenCabinUnsupported() {
        // #16
        val p = Prompt("f", PromptKind.FRESH_AIR, "내기순환이 30분째예요. 잠깐 환기할까요?", null, Long.MAX_VALUE)
        assertEquals("외기로 전환", acceptLabel(p, cabinSupported = true))
        assertNull(acceptLabel(p, cabinSupported = false))
        assertEquals(DriveActions.Answer(p, null), driveActions(running.copy(prompt = p, cabinSupported = false), driving = true))
    }

    @Test
    fun parkedWithoutQuestionShowsPlanAndOccupants() {
        assertEquals(DriveActions.Parked, driveActions(running, driving = false))
        assertEquals(DriveActions.None, driveActions(running, driving = true))
    }

    // ---------------------------------------------------------------- 정차 중 다음 계획 (#4)

    @Test
    fun parkedPlanCardUsesLiveDistanceAndSkipsPassedStops() {
        val first = PlannedStop(stop("anseong", "안성휴게소", 52.0), setOf(Purpose.RESTROOM), 10, arriveInS = 2_700.0)
        val second = PlannedStop(stop("oksan", "옥산휴게소", 118.0), setOf(Purpose.REST), 10, arriveInS = 5_400.0)
        val s = running.copy(
            parked = true, guiding = true, plan = PitStopPlan(listOf(first, second), 20),
            ahead = listOf(stop("anseong", "안성휴게소", 2.0), stop("oksan", "옥산휴게소", 68.0)),
        )
        val card = stopCard(s, driving = false)
        assertEquals("다음 계획", card.context)
        assertTrue(card.meta!!, card.meta!!.startsWith("2km"))
        assertFalse(card.meta!!, card.meta!!.contains("45분 뒤"))

        // 안성을 지나침(목록에서 빠짐, 뒤의 옥산은 있음) → 다음 계획은 옥산
        val passed = s.copy(ahead = listOf(stop("oksan", "옥산휴게소", 60.0)))
        assertEquals("옥산휴게소", stopCard(passed, driving = false).title)
    }

    // ---------------------------------------------------------------- 게이지·이유 (#17)

    @Test
    fun bodyClockReasonOutranksDrivingMinutes() {
        val r = listOf(
            Reason("FATIGUE_DRIVING", "20분째 쉬지 않고 운전 중이에요"),
            Reason("FATIGUE_BODY_CLOCK", "일어난 지 9시간째, 하루 중 졸음이 오기 쉬운 때예요"),
        )
        assertEquals("졸음 오는 시간대", fatigueReason(r))
    }

    @Test
    fun strongAndMonotonyReasonsAreNotSilentlyDropped() {
        assertEquals("커피·낮잠 권장", fatigueReason(listOf(Reason("FATIGUE_STRONG", "졸릴 땐 쉬기만 해서는 부족해요. 커피 뒤 15분 이내 낮잠이 도움이 돼요"))))
        assertEquals("단조로운 주행", fatigueReason(listOf(Reason("FATIGUE_MONOTONY", "크루즈로 단조롭게 달리는 중이에요"))))
    }

    @Test
    fun restroomCapExplainsTheNumberWhenItBinds() {
        val r = listOf(
            Reason("RESTROOM_ELAPSED", "아기 기준, 마지막 화장실 후 1시간 지났어요"),
            Reason("RESTROOM_CAP", "아기는 카시트에 1시간 30분 넘게 있지 않는 게 좋아요"),
        )
        assertEquals("카시트 1:30 상한", restroomReason(r, "아기"))
        assertEquals("아기 요청", restroomReason(r + Reason("RESTROOM_VOICED", "아기가 화장실이 필요하다고 했어요"), "아기"))
    }

    @Test
    fun gaugeBarUsesPersonalIntervalWhenSet() {
        val me = Occupant("me", OccupantType.ADULT, intervalOverrideMin = 60.0)
        val s = CoachUiState(serviceRunning = true, occupants = listOf(me), restroom = Gauge(50, Level.OK, drivenBy = "me"))
        assertEquals(50f / 60f, restroomGauge(s).fraction!!, 0.01f)
    }

    @Test
    fun gaugeWithoutMinutesHasNoLevelAndNoFraction() {
        val g = restroomGauge(CoachUiState(serviceRunning = true, restroom = Gauge(null, Level.OK, listOf(Reason("RESTROOM_NONE", "…")))))
        assertEquals(null, g.level)
        assertEquals(null, g.fraction)
        assertEquals("탑승자 설정 필요", g.reason)
    }

    @Test
    fun sensitiveOccupantIsNeverNamedOnDriveCard() {
        val oab = Occupant("x", OccupantType.OAB, label = "과민성 방광")
        val s = running.copy(
            guiding = true,
            occupants = listOf(Occupant("driver", OccupantType.ADULT, label = "운전자"), oab),
            restroom = Gauge(30, Level.WATCH, listOf(Reason("RESTROOM_ELAPSED", "과민성 방광 기준, 마지막 화장실 후 1시간 지났어요")), drivenBy = "x"),
            next = rec(stop("a", "안성휴게소", 5.0)),
        )
        assertFalse(stopCard(s, driving = true).chips.any { "방광" in it })
        assertEquals("민감 탑승자 기준", restroomGauge(s).reason)
    }

    @Test
    fun drivingCardHasAtMostTwoChips() {
        val s = running.copy(
            guiding = true,
            next = rec(stop("a", "안성휴게소", 5.0), Reason("LAST_CHANCE_CONGESTION", "이곳을 지나면 1시간 정체 구간에 화장실이 없어요"), Reason("NEXT_REST", "…"), Reason("URGENT_OPEN", "…")),
        )
        assertTrue(stopCard(s, driving = true).chips.size <= 2)
    }

    // ---------------------------------------------------------------- 급해 질문의 시간 (엔진 닫힘 검증 E1)

    @Test
    fun theUrgentQuestionCardPrintsTheTotalTimeItsSpeechAndTheListSay() {
        // 급해 질문의 문장("…까지 약 18분이에요")과 급해 목록은 주행 + 주차·걷기 + 유턴을 합친 시간(RankedStop.totalTimeS)을 말한다.
        // 카드는 주행 시간(etaS)만 보여서 "약 18분"이라고 묻는 카드에 17분이 찍혔다
        val wc = stop("wc", "역삼공원 화장실", 6.0, eta = 17 * 60.0)
        val gas = stop("gas", "역삼주유소", 7.5, eta = 20 * 60.0)
        val asked = Prompt("u1", PromptKind.URGENT_RESULT, "역삼공원 화장실까지 약 18분이에요. 바로 안내할까요?", wc, expiresAt = Long.MAX_VALUE)
        val list = listOf(RankedStop(wc, 18 * 60.0 + 10.0, emptyList()), RankedStop(gas, 23 * 60.0, emptyList()))
        val state = running.copy(prompt = asked, promptStop = wc, urgent = list)
        assertEquals("6km · 18분 · 경로 위", stopCard(state, driving = true).meta)
        // 엔진 추천(next)이 같은 곳일 때도 같다
        assertEquals("6km · 18분 · 경로 위", stopCard(state.copy(next = rec(wc)), driving = true).meta)
        // 목록에 그곳이 없으면(급해 화면을 닫았다, 다시 찾은 목록에서 빠졌다) 아는 것은 주행 시간뿐이다
        assertEquals("6km · 17분 · 경로 위", stopCard(state.copy(urgent = null), driving = true).meta)
        assertEquals("6km · 17분 · 경로 위", stopCard(state.copy(urgent = list.drop(1)), driving = true).meta)
        // 다른 질문은 급해 목록에 같은 곳이 있어도 주행 시간이다 (그 질문의 문장은 합친 시간을 말하지 않는다)
        val lastChance = asked.copy(kind = PromptKind.LAST_CHANCE, speech = "다음 화장실은 6km 앞 역삼공원 화장실이에요. 들를까요?")
        assertEquals("6km · 17분 · 경로 위", stopCard(state.copy(prompt = lastChance), driving = true).meta)
        // 질문 장소의 지금 위치를 모르면(후보에서 빠졌다) 전처럼 위치만 보인다: 물은 때의 거리·시간을 지금 값처럼 쓰지 않는다
        assertEquals("경로 위", stopCard(state.copy(promptStop = null), driving = true).meta)
    }

    // ---------------------------------------------------------------- 내비에 넣는 중 (닫힘 검증 UI-N2)

    @Test
    fun aGoToInFlightShowsProgressAndNoRetryButtons() {
        // 들르기를 받은 뒤 내비가 확인하는 동안(2026-11-12 에뮬레이터에서 19.5초): '들르기로 한 곳'이 아니라 진행을 보인다
        val a = stop("a", "안성휴게소", 4.0)
        val pending = running.copy(next = rec(a, Reason("NEXT_ACCEPTED", "…")), goToPending = a)
        val card = stopCard(pending, driving = true)
        assertEquals("내비에 넣는 중…", card.context)
        assertEquals("안성휴게소", card.title)
        assertTrue(card.meta!!, card.meta!!.startsWith("4km"))
        assertEquals(DriveActions.None, driveActions(pending, driving = true))
        assertEquals(DriveActions.Parked, driveActions(pending, driving = false))
        // 다시 시도 뒤: 앞 실패 결과가 아직 남아 있어도 넣는 동안은 [다시 시도]를 두지 않는다 (또 누르면 경유지가 둘 들어간다)
        val retrying = pending.copy(goToResult = GoToResult(a, GoToOutcome.FAILED, 1L), ahead = listOf(a))
        assertEquals("내비에 넣는 중…", stopCard(retrying, driving = true).context)
        assertFalse(driveActions(retrying, driving = true) is DriveActions.RetryGoTo)
        // 질문이 오면 질문이 먼저다 (시간이 급한 결정)
        val p = Prompt("p", PromptKind.LAST_CHANCE, "들를까요?", stop("o", "옥산휴게소", 3.0), Long.MAX_VALUE)
        assertEquals("옥산휴게소", stopCard(pending.copy(prompt = p), driving = true).title)
        assertTrue(driveActions(pending.copy(prompt = p), driving = true) is DriveActions.Answer)
    }

    @Test
    fun aPendingStopOtherThanTheRecommendationShowsNoStaleDistance() {
        // 계획 화면에서 넣은 곳(추천과 다른 곳): 지금 후보에 있으면 그 거리, 없으면 넣을 때의 거리를 지금 거리처럼 보이지 않는다
        val asked = stop("a", "안성휴게소", 52.0)
        val b = stop("b", "천안휴게소", 30.0)
        val s = running.copy(next = rec(b), goToPending = asked, ahead = listOf(stop("a", "안성휴게소", 50.0), b))
        assertEquals("안성휴게소", stopCard(s, driving = true).title)
        assertTrue(stopCard(s, driving = true).meta!!, stopCard(s, driving = true).meta!!.startsWith("50km"))
        assertEquals("경로 위", stopCard(s.copy(ahead = listOf(b)), driving = true).meta)
        // 정차 중 계획 카드보다도 먼저다
        val plan = PitStopPlan(listOf(PlannedStop(b, setOf(Purpose.RESTROOM), 10, 1_200.0)), 10)
        assertEquals("내비에 넣는 중…", stopCard(s.copy(parked = true, plan = plan), driving = false).context)
        // 코치가 멈췄으면 넣는 중이라고 하지 않는다
        assertEquals("코치가 멈췄어요", stopCard(s.copy(serviceRunning = false, restroom = Gauge(40, Level.WATCH)), driving = true).title)
    }

    @Test
    fun aResultThatArrivesBeforeThePendingMarkClearsIsNotAnnouncedTwice() {
        // 코치가 결과를 올린 순간 '넣는 중' 표시가 아직 남아 있어도, 곧 카드가 그 결과를 보인다: 토스트로 한 번 더 알리지 않는다
        val a = stop("a", "안성휴게소", 4.0)
        val added = GoToResult(a, GoToOutcome.WAYPOINT_ADDED, 2L)
        val s = running.copy(next = rec(a, Reason("NEXT_ACCEPTED", "…")), goToPending = a, goToResult = added)
        assertTrue(cardShowsGoTo(s, driving = true, r = added))
        // 다른 곳을 넣는 중이면 카드는 그 진행을 보인다: 이 결과는 토스트로 알린다
        val other = stop("b", "천안휴게소", 30.0)
        assertFalse(cardShowsGoTo(s.copy(goToPending = other), driving = true, r = added))
    }

    // ---------------------------------------------------------------- 맥락 요인 (FIXLIST U4·U5)

    @Test
    fun aSleepingChildAndAMealExplainTheRestroomGauge() {
        val elapsed = Reason("RESTROOM_ELAPSED", "7살 아이 기준, 마지막 화장실 후 1시간 지났어요")
        val texts = dev.shimpyo.engine.CoachTexts()
        val meal = Reason("RESTROOM_AFTER_MEAL", texts.reasonRestroomAfterMeal())
        val asleep = Reason("RESTROOM_ASLEEP", texts.reasonRestroomAsleep("7살 아이"))
        // 늘 붙는 '누구 기준'보다 숫자가 평소와 다른 이유가 먼저다
        assertEquals("식사 뒤", restroomReason(listOf(elapsed, meal), "7살 아이"))
        assertEquals("7살 아이 자는 중", restroomReason(listOf(elapsed, meal, asleep), "7살 아이"))
        // 직접 요청이 가장 먼저
        assertEquals("7살 아이 요청", restroomReason(listOf(elapsed, asleep, Reason("RESTROOM_VOICED", "7살 아이가 화장실이 필요하다고 했어요")), "7살 아이"))
        val kid = Occupant("kid", OccupantType.CHILD, ageYears = 7, label = "7살 아이")
        val s = CoachUiState(serviceRunning = true, occupants = listOf(kid), restroom = Gauge(70, Level.OK, listOf(elapsed, asleep), drivenBy = "kid"))
        assertEquals("7살 아이 자는 중", restroomGauge(s).reason)
    }

    // ---------------------------------------------------------------- CCP 기본 포커스 (UI 감사 #13)

    @Test
    fun defaultFocusGoesToTheAnswerButtonAndBackToUrgentWhenTheQuestionEnds() {
        val p = Prompt("q7", PromptKind.LAST_CHANCE, "들를까요?", stop("o", "옥산휴게소", 3.0), Long.MAX_VALUE)
        val asking = driveFocus(DriveActions.Answer(p, "들르기"))
        assertTrue(asking.primary)
        val retry = driveFocus(DriveActions.RetryGoTo(stop("a", "안성휴게소", 4.0)))
        assertTrue(retry.primary)
        // 질문이 끝나 버튼이 사라지면 급해로 옮긴다: 키가 달라야 포커스를 다시 준다
        val idle = driveFocus(DriveActions.None)
        assertFalse(idle.primary)
        assertFalse(driveFocus(DriveActions.Parked).primary)
        assertEquals(4, setOf(asking.key, retry.key, idle.key, driveFocus(DriveActions.Parked).key).size)
        // 다른 질문이면 다시 준다
        assertNotEquals(asking.key, driveFocus(DriveActions.Answer(p.copy(id = "q8"), "들르기")).key)
    }

    // ---------------------------------------------------------------- 재검토(독립 감사) 결과

    @Test
    fun failedWaypointForAStopBehindTheCarIsNotOfferedAgain() {
        // 재검토 #4: 실패한 곳을 지나쳤다(후보 목록 준비됨, 어디에도 없음) → 다시 시도 카드를 두지 않는다
        val a = stop("a", "안성휴게소", 4.0)
        val b = stop("b", "천안휴게소", 30.0)
        val passed = running.copy(goToResult = GoToResult(a, GoToOutcome.FAILED, 1L), ahead = listOf(b), next = rec(b))
        assertFalse(driveActions(passed, driving = true) is DriveActions.RetryGoTo)
        assertEquals("천안휴게소", stopCard(passed, driving = true).title)
        // 목록을 아직 모르면(위치 확인 중) 지나쳤다고 단정하지 않는다
        val unknown = passed.copy(candidatesState = CandidatesState.NO_LOCATION, ahead = emptyList(), next = null)
        assertTrue(driveActions(unknown, driving = true) is DriveActions.RetryGoTo)
    }

    @Test
    fun nearestAheadSkipsUTurnStopsAndJamChipCoversTheJamWeAreIn() {
        val uturn = stop("u", "반대편 휴게소", 2.0).copy(directionOk = false)
        val ahead = stop("a", "옥산휴게소", 8.0)
        val s = running.copy(guiding = true, ahead = listOf(uturn, ahead))
        assertEquals("옥산휴게소", stopCard(s, driving = true).title)
        // 차가 이미 정체 안(시작 -2km)이고 정체가 6km에서 끝난다 → 8km 휴게소는 '정체 뒤'
        val inJam = s.copy(congestion = listOf(CongestionSegment(-2_000.0, 8_000.0, 1_200.0)))
        assertEquals(listOf("정체 뒤"), stopCard(inJam, driving = true).chips)
        // 정체가 휴게소 너머까지 이어지면 '정체 구간 안'
        val through = s.copy(congestion = listOf(CongestionSegment(-2_000.0, 20_000.0, 2_400.0)))
        assertEquals(listOf("정체 구간 안"), stopCard(through, driving = true).chips)
    }

    @Test
    fun parkedPlanCardForStopWithoutLiveAnchorShowsNoCurrentEta() {
        // 재검토 #5: 후보 목록에 없는 계획 정차는 계획 때 값임을 밝히고 'N분 뒤 도착'을 지금 값처럼 보이지 않는다
        val a = PlannedStop(stop("anseong", "안성휴게소", 52.0), setOf(Purpose.RESTROOM), 10, arriveInS = 2_700.0)
        val s = running.copy(parked = true, plan = PitStopPlan(listOf(a), 10), ahead = emptyList(), candidatesState = CandidatesState.NO_LOCATION)
        val meta = stopCard(s, driving = false).meta!!
        assertTrue(meta, meta.startsWith("계획 때 52km"))
        assertFalse(meta, meta.contains("뒤 도착"))
    }

    @Test
    fun aFullWaypointListSaysWhyOnTheCardAndIsNotRepeatedAsAToast() {
        // 2026-11-12 에뮬레이터: 내비는 경유지를 둘까지만 받는다. 셋째를 '다시 시도'해도 또 실패하므로 까닭과 할 일을 보인다
        val a = stop("a", "안성휴게소", 4.0)
        val full = GoToResult(a, GoToOutcome.FAILED, 1L, GoToFailure.WAYPOINTS_FULL)
        val s = running.copy(goToResult = full, ahead = listOf(a))
        val card = stopCard(s, driving = true)
        assertEquals("내비 경유지가 가득 찼어요", card.context)
        assertTrue(card.meta.orEmpty(), card.meta.orEmpty().contains("정차한 뒤"))
        assertTrue("카드가 까닭을 보이면 토스트로 되풀이하지 않는다", cardShowsGoTo(s, driving = true, r = full))
        val ahead = full.copy(failure = GoToFailure.AHEAD_OF_OTHER_WAYPOINT)
        assertEquals("경유지 순서를 맞추지 못했어요", stopCard(s.copy(goToResult = ahead), driving = true).context)
        assertEquals("까닭을 모르면 예전 문구", "경유지를 넣지 못했어요", stopCard(s.copy(goToResult = full.copy(failure = null)), driving = true).context)
    }

    @Test
    fun cardShowsGoToMatchesWhatIsActuallyOnTheCard() {
        // 재검토 #10: 질문이 떠 있으면 실패 카드가 가려진다 → 토스트로 알려야 한다
        val a = stop("a", "안성휴게소", 4.0)
        val failed = GoToResult(a, GoToOutcome.FAILED, 1L)
        assertTrue(cardShowsGoTo(running.copy(goToResult = failed, ahead = listOf(a)), driving = true, r = failed))
        val p = Prompt("p", PromptKind.LAST_CHANCE, "들를까요?", stop("o", "옥산휴게소", 3.0), Long.MAX_VALUE)
        assertFalse(cardShowsGoTo(running.copy(goToResult = failed, ahead = listOf(a), prompt = p), driving = true, r = failed))
        val added = GoToResult(a, GoToOutcome.WAYPOINT_ADDED, 1L)
        assertTrue(cardShowsGoTo(running.copy(goToResult = added, next = rec(a, Reason("NEXT_ACCEPTED", "…"))), driving = true, r = added))
    }
}
