package dev.shimpyo.engine

import dev.shimpyo.engine.EngineMath.NONE
import java.time.Instant
import java.time.ZonedDateTime
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 쉼표의 판단 엔진 진입점. 상태를 가진 객체이고, 앱의 서비스가 1초 안팎마다 [update]를 부른다.
 * 안드로이드에 의존하지 않으므로 JVM 단위 테스트로 시나리오를 검증한다. 근거 문서: docs/research/ai-science.md 8장.
 *
 * 스레드: 한 스레드에서만 부른다 (서비스의 단일 코루틴).
 *
 * 흐름 (update 한 번):
 * 1. 단조 시계를 진행하고 표본을 운전 패턴 분석기·조용한 때 추적기에 넣는다.
 * 2. [StopTracker]가 기어·문으로 정차를 판정 → 화장실 시계(P 3분 + 문)·휴식 리듬(P 15분)을 0으로, 관측 간격을 기억에 남긴다.
 *    P 20분이 넘으면 [MealStopRule]이 식사 정차인지 보고, 맞으면 그 정차에서 시작한 화장실 구간에 식사 배수를 건다.
 *    [CoachInput.asleepOccupantIds](밤에 잠든 아이)를 화장실 시계에 반영한다 (화면 게이지에만, need-covariates.md 8장).
 * 3. 음성 의도 처리 (수락·거절, "화장실 급해", "아이가 화장실", "졸려", "쉬고 싶어", "배고파", "주유", "얼마나 남았어").
 *    사용자가 말한 것에는 바로 답한다(조용한 때·30초·쉼을 받지 않는다). 답이 둘이면 하나씩 차례로.
 * 4. 화장실 게이지([RestroomNeedModel]), 운전 패턴([DrivingStyleAnalyzer]), 피로 게이지([FatigueModel]).
 * 5. 경로 앞 화장실 기회와 도착 시간([RouteContext]) → 마지막 기회([GapAnalyzer]), 급해 순위([UrgentRanker]).
 * 6. 다음 추천과 질문 후보 → [PromptPolicy]가 지금 물을 하나를 고른다. 공조 제안은 따로 낸다.
 *
 * 질문: [CoachOutput.prompt]는 대답이 오거나 만료될 때까지 같은 id로 계속 나온다. 앱은 새 id일 때만 말하고,
 * 대답은 [VoiceIntent.Accept]/[VoiceIntent.Decline]을 다음 update에 넣거나 [onPromptResult]로 알린다.
 * 피로 질문 사다리: SOON(또는 운전 패턴 변화 지속) → CHECK_IN "혹시 졸리세요?" → "응"이면 졸림으로 보고 강한 휴식 권유,
 * "아니"면 운전 패턴 편차를 20분 동안 피로에 넣지 않는다. NOW(2시간 리듬·졸림 곡선) → REST_SUGGESTION.
 * 후보([CoachInput.candidates])는 매 update마다 지금 위치 기준 거리로 넘긴다.
 * 입력은 들어오는 곳에서 한 번 정리한다([InputSanitizer]: NaN·무한대 → 모름). 앱에 내보내는 추천·질문의 정차 후보에는
 * 한 경로([withEta])로 엔진이 계산한 도착 시간을 채운다(내비 ETA가 있으면 그대로).
 * 운전자가 화면에서 정차지를 직접 골랐으면(급해 목록, 계획, 다시 시도) [noteAccepted]로 알린다. 엔진이 물은 곳이 아니어도 된다.
 * 추천([CoachOutput.next]): 들르기로 한 곳 > 급해 결과 > 화장실 여유 없음 > 마지막 기회 > 휴식 > 식사. 단 화장실 질문
 * (PromptKind.LAST_CHANCE)이 떠 있는 동안에는 그 질문의 장소와 이유다 — 화면이 질문 카드의 이유를 추천에서 가져오기 때문이다.
 * 새 여정: [setProfile]에 다른 tripStart를 넘기면 주행 상태(피로, 기준선, 정차, 질문)를 새로 시작한다.
 *
 * @param random 톰프슨 샘플링용 난수. 테스트에서는 시드를 고정한 Random을 넣는다.
 * @param texts 화면·음성 문구. 상속해서 바꿀 수 있다.
 */
class CoachEngine(
    private val config: CoachConfig = CoachConfig(),
    profile: TripProfile,
    memory: PersonalMemory = PersonalMemory(),
    private val random: Random = Random.Default,
    private val texts: CoachTexts = CoachTexts(),
) {
    private var profile: TripProfile = profile
    private var memory: PersonalMemory = memory

    private val clock = EngineClock()
    private val local = LocalClock(config.zoneId)
    private val route = RouteContext(config)
    private val gap = GapAnalyzer(config)
    private val ranker = UrgentRanker(config, texts)
    private val planner = PitStopPlanner(config, texts)

    // 여정 단위 상태 (새 여정이면 새로 만든다)
    private var stops = StopTracker(config)
    private var meal = MealStopRule(config, local)
    private var needs = RestroomNeedModel(config, local)
    private var style = DrivingStyleAnalyzer(config)
    private var fatigue = FatigueModel(config, local)
    // 질문 id는 엔진 인스턴스에 하나: 새 여정(새 PromptPolicy)에도 이어 쓴다
    private val promptIds = PromptIds.forNewEngine()
    private var policy = PromptPolicy(config, random, promptIds)
    private var quiet = QuietTracker(config)

    /**
     * 들르기로 한 곳: 수락한 질문의 장소, 또는 운전자가 화면에서 직접 고른 곳([noteAccepted]).
     * [suggested]: 그곳에 서면 '앱이 권해서 들른 정차'로 배울지 (엔진의 마지막 기회·휴식 제안, 화면에서 고른 곳).
     * "화장실 급해"의 결과는 사용자가 필요를 말한 것이라 아니다 (그 사람은 말한 때 이미 사건으로 남았다)
     */
    private class Accepted(
        val stopId: String,
        var stop: StopCandidate,
        var purposes: Set<Purpose>,
        val suggested: Boolean,
        /** 여럿이 타서 앞당겨진 마지막 기회였는지: 그곳에 서면 모두 '적어도 그만큼 버텼다'로만 배운다 (RestroomNeedModel.onRestroomStop) */
        val groupDriven: Boolean,
        val atMono: Long,
    ) {
        var missingSince: Long = NONE
    }

    /**
     * 사용자 말에 대한 응답 하나. 물을 때까지 남는다(버리지 않는다). 처음 내놓을 때만 '새 응답'이라 활성 질문을 끊고 바로 묻고,
     * 앞서 밀렸으면 지금 질문이 끝난 뒤에 묻는다 (PromptPolicy.Candidate.fresh)
     */
    private class Pending {
        var on = false
            private set
        var offered = false
        var purpose: Purpose = Purpose.RESTROOM
            private set

        fun request(p: Purpose = Purpose.RESTROOM) {
            on = true
            offered = false
            purpose = p
        }

        fun clear() {
            on = false
            offered = false
        }
    }

    private var accepted: Accepted? = null
    /** 지난 update의 마지막 기회 ([noteAccepted]가 고른 곳이 묶음 때문에 앞당겨진 마지막 기회인지 보는 데 쓴다) */
    private var latestLastChance: GapAnalyzer.LastChance? = null
    private var restWantedAt = NONE
    private var mealWantedAt = NONE
    private val restAnswer = Pending()
    private val mealAnswer = Pending()
    private val howLongAnswer = Pending()
    private val needAnswer = Pending()
    private val fuelAnswer = Pending()
    private var styleMuteUntil = NONE
    private var cruiseOn = false

    private val restroomOpps = ArrayList<Opportunity>()
    private val restOpps = ArrayList<Opportunity>()
    private val mealOpps = ArrayList<Opportunity>()
    private val fuelOpps = ArrayList<Opportunity>()
    private val promptCands = ArrayList<PromptPolicy.Candidate>(8)
    private val promptOwners = ArrayList<Pending?>(8)
    private var currentCandidates: List<StopCandidate> = emptyList()
    private val distanceOfId: (String) -> Double? = { id ->
        var d: Double? = null
        for (c in currentCandidates) if (c.id == id) {
            val x = route.distanceOf(c)
            if (!x.isNaN() && x > 0.0) d = x
            break
        }
        d
    }

    /** 새 신호를 반영하고 게이지·추천·질문을 돌려준다 */
    @Suppress("NAME_SHADOWING")
    fun update(input: CoachInput): CoachOutput {
        val input = InputSanitizer.clean(input)
        val firstUpdate = !clock.started
        val dtMs = clock.advance(input.now)
        if (firstUpdate) {
            syncProfile()
            seedTrip(input.now)
        }
        val mono = clock.mono
        currentCandidates = input.candidates

        // 1. 표본
        val samples = input.samples
        for (i in samples.indices) {
            val s = samples[i]
            style.onSample(s)
            quiet.onSample(s)
            s.cruiseActive?.let { cruiseOn = it }
        }
        val speed = currentSpeed(input)
        fatigue.monotony = cruiseOn && speed != null && speed >= config.monotonyMinKph
        // 달린다는 것이 확인됐는지: 기어 D·R·N, 또는 기어를 모르면 이번에 잰 차속이 3km/h를 넘을 때.
        // 앱이 막 켜져 기어 신호가 오기 전(UNKNOWN, 차속 모름)에 묻던 것(2026-11-11 에뮬레이터)과, 기어를 모른 채 서 있던
        // 한 시간을 운전으로 세던 것(감사 S1·S2)을 함께 막는다
        val movementKnown = when (input.gear) {
            Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> true
            Gear.PARK -> false
            Gear.UNKNOWN -> speed != null && speed > StopTracker.MOVING_KPH
        }

        // 2. 정차
        val ev = stops.update(mono, input.gear, speed, input.doorOpened)
        val moving = !stops.parked && movementKnown
        fatigue.advance(dtMs, moving, input.now, mono)
        // 식사 정차 판단에 쓸 것(선 곳, 식사 목적)은 아래 정차 사건이 수락한 정차를 내리기 전에 본다
        val wasMealStop = meal.factor != 1.0
        meal.observe(mono, stops.sessionStart, stops.parked, stops.parked && mealIntent(mono), input.candidates, speed, dtMs)
        if (ev.restroomStopAt != NONE) {
            memory = needs.onRestroomStop(
                ev.restroomStopAt, memory, suggested = accepted?.suggested == true, groupDriven = accepted?.groupDriven == true,
            )
            accepted = null
        }
        if (ev.restAt != NONE) {
            fatigue.onRest(mono)
            style.resetScore()
            restWantedAt = NONE
            restAnswer.clear()
            accepted = null
        }
        if (ev.mealLengthAt != NONE) meal.onMealLength(input.now - (mono - stops.sessionStart))
        if (meal.factor != 1.0) {
            if (!wasMealStop) {
                // 식사 정차로 봤다: "배고파" 요청은 이 정차로 해결됐다 (바로 다음 정차까지 식사 정차가 되거나, 식사 뒤에 식사할 곳을 권하지 않게)
                mealWantedAt = NONE
                mealAnswer.clear()
            }
            // 그 정차에서 시계가 돌아간 사람의 구간에 건다. 다음 정차까지 매 update 다시 건다: 문이 늦게(떠나는 순간에) 열려 시계가
            // 뒤에 돌아가도, 프로필이 그 정차 안의 다른 시각으로 시계를 옮겨도 그 구간에 남는다 (RestroomNeedModel.onMealStop)
            needs.onMealStop(stops.sessionStart, meal.until, meal.factor)
        }
        needs.advance(mono, input.now, input.asleepOccupantIds)
        needs.tick(mono)
        fatigue.onCabin(input.cabin, mono)

        // 3. 음성 의도
        var urgentRequested = false
        if (input.intent != null) policy.noteInteraction(mono)
        when (val intent = input.intent) {
            null -> {}
            is VoiceIntent.Accept -> applyResult(intent.promptId, true, mono)
            is VoiceIntent.Decline -> applyResult(intent.promptId, false, mono)
            is VoiceIntent.OccupantNeed -> when (intent.purpose) {
                // "아이가 화장실 가고 싶대": 그 사람을 NOW로 올리고, 바로 다음 들를 곳을 답한다.
                // 예전에는 게이지만 올리고 엔진 질문이 30초 뒤에야 나왔다(그 사이 2km 안으로 들어온 휴게소는 건너뛰었다, 감사 V1)
                Purpose.RESTROOM, Purpose.FEEDING, Purpose.PET -> {
                    memory = needs.onVoiced(intent.occupantId, intent.purpose, mono, memory)
                    needAnswer.request(intent.purpose)
                }
                Purpose.REST -> requestRest(mono)
                Purpose.MEAL -> requestMeal(mono)
                Purpose.FUEL -> fuelAnswer.request(Purpose.FUEL) // 예전에는 아무 답도 없었다(감사 V2)
            }
            VoiceIntent.UrgentRestroom -> {
                memory = needs.onVoiced(null, Purpose.RESTROOM, mono, memory)
                urgentRequested = true
            }
            VoiceIntent.WantRest -> requestRest(mono)
            VoiceIntent.Sleepy -> {
                fatigue.sleepyVoiced = true
                requestRest(mono)
            }
            VoiceIntent.Hungry -> requestMeal(mono)
            VoiceIntent.HowLong -> howLongAnswer.request()
        }
        if (restWantedAt != NONE && mono - restWantedAt > WANT_HOLD_MS) restWantedAt = NONE
        if (mealWantedAt != NONE && mono - mealWantedAt > WANT_HOLD_MS) mealWantedAt = NONE

        // 4. 게이지
        val restroom = needs.gauge(mono, texts)
        val styleOut = style.output(texts)
        val styleMuted = styleMuteUntil != NONE && mono < styleMuteUntil
        val fat = fatigue.evaluate(input.now, mono, styleOut.deviation, styleOut.persistent, styleMuted, profile.driverSleepHours, texts)
        val fatigueGauge = fat.gauge

        // 5. 경로 앞 기회
        route.prepare(input.route, speed)
        route.opportunities(input.candidates, restroomOpps, RouteContext::isRestroomOpportunity)
        val deadline = needs.deadlineMin(mono, config.restroomDecisionProbability)
        val lastChance = gap.analyze(restroomOpps, route, deadline, needs.sensitive, needs.preferredMaxGapMin())
            ?.let { if (broughtForwardByGroup(it, deadline, mono)) it.groupDriven() else it }
        latestLastChance = lastChance
        trackAccepted(mono)

        val urgentList = if (urgentRequested) {
            ranker.rank(
                input.candidates, input.now, local, needs.has(OccupantType.INFANT), speed,
                if (stops.parked) config.urgentMaxResults else config.urgentMaxResultsDriving, route,
            )
        } else null

        val deviation = styleOut.deviation ?: 0.0
        val wantsRest = restWantedAt != NONE || fatigueGauge.level >= Level.SOON
        val restTarget = if (wantsRest || restAnswer.on) pickRestTarget(input.candidates, fatigueGauge) else null
        val mealTarget = if (mealWantedAt != NONE || mealAnswer.on) pickMealTarget(input.candidates) else null
        // 아직 안전하게 들를 수 있는 가장 가까운 화장실 기회 (진입로 2km 밖)
        val nearestRestroom = restroomOpps.firstOrNull { it.distanceM >= config.askWindowNearM }
        // 게이지가 NOW면 지금 필요한 것이라 가장 가까운 화장실이 먼 마지막 기회보다 먼저다. 마지막 기회가 바로 그 가장 가까운
        // 화장실일 때만 마지막 기회로(그 문구와 이유로) 묻는다. 예전에는 마지막 기회가 하나라도 있으면 NOW 질문을 만들지 않아,
        // 81km 앞 정체 전 휴게소가 마지막 기회로 잡히면 게이지 5분인 아이를 태우고 10km마다 있는 휴게소를 말없이 지나쳤다 (닫힘 검증 E2)
        val lastChanceIsNearest = lastChance != null && nearestRestroom != null && lastChance.opp.stop.id == nearestRestroom.stop.id
        val nowRestroom =
            if (restroom.level == Level.NOW && !lastChanceIsNearest && accepted == null && urgentList == null) nearestRestroom else null

        // 6. 질문 (사용자 응답 먼저, 그다음 엔진이 먼저 거는 질문을 우선순위 순으로).
        // 사용자의 말에 답할 곳이 이미 들르기로 한 곳이면 '들를까요?'를 다시 묻지 않고 그렇게만 답한다 ([goingThere])
        val ctx = PurposeContext(restroom.level, fatigueGauge.level, input.now, mono)
        promptCands.clear()
        promptOwners.clear()
        if (urgentRequested) {
            val top = urgentList?.firstOrNull()
            addCand(
                if (top != null) {
                    goingThere(top.stop, route.distanceOf(top.stop).takeIf { it.isFinite() && it > 0.0 }, fresh = true) ?: ask(
                        PromptKind.URGENT_RESULT, texts.urgentResult(top.stop.name, (top.totalTimeS / 60.0).roundToInt()),
                        urgentStop(top.stop, speed), null, restroom.level, user = true,
                        purposes = purposesFor(top.stop, Purpose.RESTROOM, ctx), reasons = top.why,
                    )
                } else {
                    ask(PromptKind.INFO, texts.urgentNone(), user = true)
                },
                null,
            )
        }
        if (needAnswer.on) {
            // 달릴 때는 아직 안전하게 들를 수 있는 가장 가까운 화장실 기회(진입로 2km 밖). 그곳이 마지막 기회이기도 하면 그 이유(정체·공백)로 답한다.
            // 서 있어도(기어 P), 움직이는지 몰라도 답한다: 꽉 막힌 정체에서 P로 서 있을 때 "아이가 화장실 가고 싶대"에 말이 없었다(감사 V1·V4의
            // 남은 부분, 닫힘 검증 E6). 선 곳 가까이에 화장실이 있으면 그렇게 답하고, 아니면 가장 가까운 화장실을 묻는다 (서 있으면 2km 안쪽도 들를 수 있다)
            val p = needAnswer.purpose
            val fresh = !needAnswer.offered
            val here = if (stops.parked) parkedPlace(input.candidates, restroom = p == Purpose.RESTROOM) else null
            val t = if (stops.parked) restroomOpps.firstOrNull { it.distanceM >= PromptPolicy.CANCEL_NEAR_M } else nearestRestroom
            addCand(
                when {
                    here != null ->
                        ask(PromptKind.INFO, if (p == Purpose.RESTROOM) texts.restroomHere(here.name) else texts.careHere(here.name), user = true, fresh = fresh)
                    t == null -> ask(PromptKind.INFO, texts.restroomRequestedNone(), user = true, fresh = fresh)
                    else -> goingThere(t.stop, t.distanceM, fresh) ?: run {
                        val lc = lastChance?.takeIf { it.opp.stop.id == t.stop.id }
                        val speech = when {
                            lc != null -> lastChanceSpeech(lc)
                            p == Purpose.RESTROOM -> texts.restroomRequested(t.stop.name, t.distanceM)
                            else -> texts.careStopSuggestion(t.stop.name, t.distanceM)
                        }
                        // 요청에 답한 곳이라는 이유. 그곳이 마지막 기회이기도 하면 그 이유가 앞에 온다 (문장도 그 이유로 말한다)
                        val requested = if (p == Purpose.RESTROOM) Reason("NEXT_REQUESTED_RESTROOM", texts.reasonNextRequestedRestroom())
                        else Reason("NEXT_REQUESTED_CARE", texts.reasonNextRequestedCare())
                        ask(
                            PromptKind.LAST_CHANCE, speech, t.stop, t.distanceM, restroom.level, user = true, fresh = fresh,
                            purposes = purposesFor(t.stop, p, ctx),
                            reasons = if (lc != null) lastChanceReasons(lc) + requested else listOf(requested),
                            groupDriven = lc?.groupDriven == true,
                        )
                    }
                },
                needAnswer,
            )
        }
        if (restAnswer.on) {
            val t = restTarget
            val fresh = !restAnswer.offered
            val going = goingThere(t?.stop, t?.distanceM, fresh, if (fatigue.sleepyVoiced) texts.sleepyAdvice(canSwapDriver()) else null)
            addCand(
                going ?: ask(
                    PromptKind.REST_SUGGESTION,
                    if (fatigue.sleepyVoiced) texts.restSuggestionSleepy(t?.stop?.name, t?.distanceM, canSwapDriver())
                    else texts.restSuggestionWanted(t?.stop?.name, t?.distanceM),
                    t?.stop, t?.distanceM, fatigueGauge.level, user = true, fresh = fresh,
                    purposes = t?.let { purposesFor(it.stop, Purpose.REST, ctx) } ?: setOf(Purpose.REST),
                ),
                restAnswer,
            )
        }
        if (mealAnswer.on) {
            val t = mealTarget
            val fresh = !mealAnswer.offered
            addCand(
                if (t != null) {
                    goingThere(t.stop, t.distanceM, fresh) ?: ask(
                        PromptKind.REST_SUGGESTION, texts.mealSuggestion(t.stop.name, t.distanceM), t.stop, t.distanceM,
                        fatigueGauge.level, user = true, fresh = fresh, purposes = purposesFor(t.stop, Purpose.MEAL, ctx),
                    )
                } else {
                    ask(PromptKind.INFO, texts.mealNone(), user = true, fresh = fresh)
                },
                mealAnswer,
            )
        }
        if (fuelAnswer.on) {
            val t = pickFuelTarget(input.candidates)
            val fresh = !fuelAnswer.offered
            addCand(
                if (t != null) {
                    goingThere(t.stop, t.distanceM, fresh) ?: ask(
                        PromptKind.REST_SUGGESTION, texts.fuelSuggestion(t.stop.name, t.distanceM), t.stop, t.distanceM,
                        fatigueGauge.level, user = true, fresh = fresh, purposes = purposesFor(t.stop, Purpose.FUEL, ctx),
                    )
                } else {
                    ask(PromptKind.INFO, texts.fuelNone(), user = true, fresh = fresh)
                },
                fuelAnswer,
            )
        }
        if (howLongAnswer.on) {
            val who = restroom.drivenBy?.let { id -> needs.tracked.firstOrNull { it.id == id } }
                ?.takeIf { needs.tracked.size > 1 }?.let { texts.occupantName(it.occupant) }
            val nextOpp = restroomOpps.firstOrNull()
            addCand(
                ask(
                    PromptKind.INFO,
                    texts.howLong(restroom.minutes, who, fatigueGauge.minutes, nextOpp?.stop?.name, nextOpp?.distanceM),
                    user = true, fresh = !howLongAnswer.offered,
                ),
                howLongAnswer,
            )
        }
        // 기어 P로 서 있는 동안에는 앞쪽 정차를 묻지 않는다 (이미 서 있다. 휴식은 15분, 화장실 시계는 3분 뒤에야 되돌아간다).
        // 달린다는 것이 확인돼야 묻는다 (movementKnown)
        if (accepted == null && moving) {
            if (nowRestroom != null) {
                addCand(
                    ask(
                        PromptKind.LAST_CHANCE, texts.restroomNowSuggestion(nowRestroom.stop.name, nowRestroom.distanceM),
                        nowRestroom.stop, nowRestroom.distanceM, Level.NOW,
                        purposes = purposesFor(nowRestroom.stop, Purpose.RESTROOM, ctx), reasons = listOf(nearestRestroomReason()),
                    ),
                    null,
                )
            } else if (lastChance != null) {
                addCand(
                    ask(
                        PromptKind.LAST_CHANCE, lastChanceSpeech(lastChance), lastChance.opp.stop, lastChance.opp.distanceM,
                        restroom.level, purposes = purposesFor(lastChance.opp.stop, Purpose.RESTROOM, ctx),
                        reasons = lastChanceReasons(lastChance), groupDriven = lastChance.groupDriven,
                    ),
                    null,
                )
            }
            if (fatigueGauge.level == Level.NOW) {
                val t = restTarget
                val drove = floor(fatigue.drivingMin).toInt()
                val speech = when {
                    fat.strong -> texts.restSuggestionSleepy(t?.stop?.name, t?.distanceM, canSwapDriver())
                    fat.sleepyCurveMin <= fat.rhythmMin -> texts.restSuggestionBodyClock(t?.stop?.name, t?.distanceM)
                    t != null -> texts.restSuggestion(drove, t.stop.name, t.distanceM)
                    else -> texts.restSuggestionNoStop(drove)
                }
                addCand(
                    ask(
                        PromptKind.REST_SUGGESTION, speech, t?.stop, t?.distanceM, fatigueGauge.level,
                        purposes = t?.let { purposesFor(it.stop, Purpose.REST, ctx) } ?: setOf(Purpose.REST),
                    ),
                    null,
                )
            }
        }
        val styleCheck = styleOut.persistent && !styleMuted && deviation >= config.styleAdvanceDeviation
        if (moving && !fatigue.sleepyVoiced && fatigueGauge.level < Level.NOW && (styleCheck || fatigueGauge.level >= Level.SOON)) {
            val speech = when {
                styleCheck -> texts.checkIn(floor(fatigue.drivingMin).toInt(), byStyle = true)
                fat.sleepyCurveMin <= fat.rhythmMin -> texts.checkInBodyClock()
                else -> texts.checkIn(floor(fatigue.drivingMin).toInt(), byStyle = false)
            }
            addCand(ask(PromptKind.CHECK_IN, speech, level = fatigueGauge.level), null)
        }
        val freshAir = fat.recircMin >= config.recirculationFreshAirMin &&
            (needs.humanCount >= config.freshAirMinOccupants || fatigueGauge.level >= Level.SOON)
        if (freshAir && !input.cabinAutoAllowed) {
            // 환기는 편의 기능: 피로 단계를 싣지 않는다 (피로가 올라도 60분 쉼·시간당 상한을 그대로 받는다)
            addCand(ask(PromptKind.FRESH_AIR, texts.freshAir(fat.recircMin), level = Level.OK), null)
        }
        val prompt = policy.decide(promptCands, input.now, mono, isQuiet(input, speed), distanceOfId, memory)
        // 물은 사용자 응답은 내리고, 못 물은 응답은 다음에 '밀린 응답'으로 남긴다
        val issued = policy.lastIssued
        for (i in promptCands.indices) {
            val owner = promptOwners[i] ?: continue
            if (promptCands[i] === issued) owner.clear() else owner.offered = true
        }

        // 7. 다음 추천. 질문을 정한 뒤에 낸다: 화장실 질문이 떠 있으면 추천은 그 질문의 장소와 이유다
        val next = recommend(ctx, urgentList, lastChance, lastChanceIsNearest, nowRestroom, wantsRest, restTarget, mealTarget, input.candidates, speed)

        // 8. 공조 (피로와 별개인 편의 기능. 앱은 사용자가 허용했을 때만 실행하고, 외기는 1~2분 뒤 되돌린다)
        val coolDown = !freshAir && fatigueGauge.level >= Level.SOON && input.cabin.hvacOn == true &&
            (input.cabin.setTempC ?: Float.NEGATIVE_INFINITY) >= config.coolDownAboveC
        val cabinAction = when {
            freshAir -> CabinAction.FRESH_AIR
            coolDown -> CabinAction.COOL_DOWN
            else -> null
        }
        val cabinReason = when (cabinAction) {
            CabinAction.FRESH_AIR -> Reason("CABIN_RECIRC", texts.reasonCabinRecirc(fat.recircMin))
            CabinAction.COOL_DOWN -> Reason("CABIN_COOL_DOWN", texts.reasonCabinCoolDown())
            null -> null
        }

        return CoachOutput(
            restroom = restroom,
            fatigue = fatigueGauge,
            style = styleOut,
            next = next,
            prompt = prompt,
            cabinAction = cabinAction,
            urgent = urgentList,
            parked = stops.parked,
            cabinReason = cabinReason,
        )
    }

    /**
     * 급해 모드: 후보를 총 소요 시간 순으로 정렬해 돌려준다 (상태를 바꾸지 않는다).
     * [CoachInput.route]를 함께 넘기면 update의 급해 결과와 같은 시간(정체 반영)으로 계산한다 ([UrgentRanker])
     */
    @Suppress("NAME_SHADOWING")
    fun urgent(input: CoachInput): List<RankedStop> {
        val input = InputSanitizer.clean(input)
        ensureStarted(input.now)
        val speed = currentSpeed(input)
        val ctx = RouteContext(config)
        ctx.prepare(input.route, speed)
        return ranker.rank(
            input.candidates, input.now, local, needs.has(OccupantType.INFANT), speed, config.urgentMaxResults, ctx,
        )
    }

    /** 피트스톱 계획: 경로 앞 후보로 정차 계획을 만든다 (상태를 바꾸지 않는다) */
    @Suppress("NAME_SHADOWING")
    fun plan(input: CoachInput): PitStopPlan {
        val input = InputSanitizer.clean(input)
        ensureStarted(input.now)
        val mono = clock.peek(input.now)
        val speed = currentSpeed(input)
        val ctx = RouteContext(config)
        ctx.prepare(input.route, speed)
        val opps = ArrayList<Opportunity>()
        ctx.opportunities(input.candidates, opps, RouteContext::isRestroomOpportunity)

        val p = config.restroomDecisionProbability
        val fe = fatigue.evaluate(
            input.now, mono, style.deviation, style.persistent,
            styleMuteUntil != NONE && mono < styleMuteUntil, profile.driverSleepHours, texts,
        )
        // 계획의 구간 상한은 한 사람 기준 마감이다(가장 급한 사람의 필요 확률 0.3). 계획은 편한 정차 간격을 잡는 것이고 화장실이 자주
        // 있는 길에서는 계획에 없던 정차 한 번의 값이 몇 분이라, 묶음 확률(누군가 필요해질 확률)로 촘촘히 세울 이유가 없다.
        // 묶음 확률은 값을 하는 곳, 긴 공백 앞의 마지막 기회 판단에만 쓴다. 예전에는 어른 넷이 화장실 70분 뒤 두 게이지가 모두
        // OK(80분·120분)인데 계획이 "지금 쉬어야 할 때예요"라고 했고, 어른 여덟의 400km는 정차 5번이었다 (닫힘 검증 E5a)
        val restroomFirstS = needs.soloDeadlineMin(mono, p)?.let { it * 60.0 } ?: Double.POSITIVE_INFINITY
        val fatigueFirstS = (fe.gauge.minutes ?: 0) * 60.0
        val sleep = profile.driverSleepHours
        val awakeNow = fatigue.awakeHours(mono)
        // 영아·반려견의 시계는 문맥 보정(식사·잠)을 받지 않으므로 지난 시간과 문턱을 그대로 쓴다
        val care = ArrayList<PitStopPlanner.Care>(2)
        needs.firstOf(OccupantType.INFANT)?.let { t ->
            care += PitStopPlanner.Care(
                Purpose.FEEDING, needs.elapsedMin(t, mono) * 60.0, t.thresholdMin * 60.0,
                { it.facilities.diaperTable || it.facilities.nursingRoom }, PitStopPlanner.FEEDING_MISMATCH_PENALTY,
            )
        }
        needs.firstOf(OccupantType.DOG)?.let { t ->
            care += PitStopPlanner.Care(
                Purpose.PET, needs.elapsedMin(t, mono) * 60.0, t.thresholdMin * 60.0,
                { it.facilities.petArea }, PitStopPlanner.PET_MISMATCH_PENALTY,
            )
        }
        val n = PitStopPlanner.Needs(
            firstLegMaxS = minOf(restroomFirstS, fatigueFirstS),
            restroomFullS = (needs.soloDeadlineMin(mono, p, fromReset = true) ?: Double.POSITIVE_INFINITY) * 60.0,
            fatigueFullS = { wall ->
                val awake = if (fatigue.wakeMono == NONE) 0.0 else awakeNow + (wall - input.now) / 3_600_000.0
                fatigue.fullRhythmWallMin(wall, awake, sleep) * 60.0
            },
            drivenNowS = fatigue.drivingMin * 60.0,
            restLabelS = config.fatigueWatchMin * 60.0,
            care = care,
            rangeM = input.rangeKm?.let { it * 1000.0 },
            sleepyCurveS = fe.sleepyCurveMin * 60.0,
        )
        return planner.plan(opps, ctx, n, input.now, local)
    }

    /** 사용자가 질문에 답했을 때 (음성 또는 버튼) */
    fun onPromptResult(promptId: String, accepted: Boolean, now: Long) {
        if (!clock.started) return
        applyResult(promptId, accepted, clock.peek(now))
    }

    /**
     * 운전자가 이 정차지를 골랐고 내비 명령(경유지·길안내)이 성공했다. 급해 목록, 계획 화면, 실패 뒤 다시 시도처럼
     * 엔진의 질문을 거치지 않은 선택을 알리는 길이다 (엔진이 물은 곳이 아니어도 된다). 첫 update 전에 불러도 된다
     * (앱이 다시 떠서 저장해 둔 '들르기로 한 곳'을 되살릴 때).
     *
     * - 그곳이 들르기로 한 곳이 된다: 추천은 NEXT_ACCEPTED, 그동안 엔진은 정차를 먼저 묻지 않는다. 유지·해제는 수락한 질문과 같다
     *   (후보 목록에서 1분 넘게 사라지면, 90분이 지나면, 화장실 정차·휴식이 기록되면 내린다. 서 있는 동안에는 내리지 않는다).
     *   그래서 앱은 그곳을 이후 update의 [CoachInput.candidates]에 같은 id로 계속 넣어야 한다.
     * - 같은 곳을 묻던 질문이 떠 있으면 그 질문을 수락한 것이다 ([onPromptResult]와 같다: 수락률 학습, 질문의 목적도 함께).
     * - 다른 곳을 묻던 화장실 질문(마지막 기회·여유 없음·"화장실 가고 싶대"의 답, 급해 결과)은 이 선택으로 끝난다. 무시한 것으로
     *   치지 않아 쉼·시간당 횟수가 남지 않고, 그 질문에 늦게 온 대답은 받지 않는다. 밀려 있던 "다음 화장실은 …" 답도 내린다.
     * - 그곳을 다시 묻지 않는다: 사용자의 말에 답할 곳이 바로 그곳이면 "…에 들르기로 했어요"라고만 답한다 ([goingThere]).
     * - 그 뒤 그곳에 서면 '앱이 권해서 들른 정차'(0.5회)로 배운다. 그곳이 여럿이 타서 앞당겨진 마지막 기회(LAST_CHANCE_GROUP)였으면
     *   누구의 사건으로도 배우지 않는다 (모두 '적어도 그만큼 버텼다').
     *
     * @param purposes 그 정차의 목적 (계획의 식사 정차면 MEAL 포함). 비어 있으면 화장실
     */
    fun noteAccepted(stop: StopCandidate, purposes: Set<Purpose>, now: Long) {
        ensureStarted(now)
        val mono = clock.peek(now)
        policy.noteInteraction(mono) // 방금 화면에서 고른 운전자에게 30초 동안 먼저 말을 걸지 않는다
        val asked = policy.active
        if (asked != null && asked.targetId == stop.id) {
            applyResult(asked.prompt.id, true, mono)
        } else if (asked != null && (asked.kind == PromptKind.LAST_CHANCE || asked.kind == PromptKind.URGENT_RESULT)) {
            policy.supersede(asked)
        }
        needAnswer.clear()
        val wanted = purposes.ifEmpty { setOf(Purpose.RESTROOM) }
        val a = accepted
        if (a != null && a.stopId == stop.id) {
            // 이미 들르기로 한 곳 (방금 수락한 질문, 또는 같은 곳을 두 번 알림): 유지 시간과 배우는 방식은 처음 것 그대로
            a.purposes = a.purposes + wanted
        } else {
            val group = latestLastChance?.let { it.groupDriven && it.opp.stop.id == stop.id } == true
            accepted = Accepted(stop.id, InputSanitizer.candidate(stop), wanted, suggested = true, groupDriven = group, mono)
        }
    }

    /**
     * 탑승자·수면 정보를 바꾼다. 같은 tripStart면 시계와 상태를 이어 가고(새 탑승자만 추가),
     * tripStart가 다르면 새 여정으로 보고 주행 상태를 새로 시작한다. 개인 기억은 유지한다.
     */
    fun setProfile(profile: TripProfile) {
        val newTrip = profile.tripStart != this.profile.tripStart
        this.profile = profile
        if (newTrip) {
            stops = StopTracker(config)
            meal = MealStopRule(config, local)
            needs = RestroomNeedModel(config, local)
            style = DrivingStyleAnalyzer(config)
            fatigue = FatigueModel(config, local)
            policy = PromptPolicy(config, random, promptIds)
            quiet = QuietTracker(config)
            accepted = null
            latestLastChance = null
            restWantedAt = NONE
            mealWantedAt = NONE
            restAnswer.clear()
            mealAnswer.clear()
            howLongAnswer.clear()
            needAnswer.clear()
            fuelAnswer.clear()
            styleMuteUntil = NONE
        }
        if (clock.started) {
            syncProfile()
            if (newTrip) seedTrip(clock.wall)
        }
    }

    /** 저장용 스냅샷 */
    fun memory(): PersonalMemory = memory

    /** 지금 문맥 보정(식사 뒤 구간, 잠든 아이)이 걸린 탑승자가 있는지. 효과 시뮬레이션이 '보정 없음' 가정을 지키는 데 쓴다 */
    internal fun contextInEffect(): Boolean = needs.tracked.any { it.mealFactor != 1.0 || it.asleep }

    // ------------------------------------------------------------------

    private class PurposeContext(val restroom: Level, val fatigue: Level, val wallNow: Long, val mono: Long)

    private fun ensureStarted(now: Long) {
        if (!clock.started) {
            clock.advance(now)
            syncProfile()
            seedTrip(now)
        }
    }

    /** 새 여정을 시작할 때: 엔진 전에 이미 달린 시간을 휴식 리듬에 채운다 (TripProfile.continuousDrivingMin) */
    private fun seedTrip(now: Long) {
        fatigue.seedContinuous(profile.continuousDrivingMin, now, clock.monoAt(now))
    }

    private fun syncProfile() {
        needs.sync(profile, clock, memory)
        // 기상 시각이 지금보다 뒤면 있을 수 없는 값이다(새벽 3시 출발에 오늘 날짜로 만든 '7시'). 그대로 두면 '방금 일어남'이 되어
        // 새벽 졸림 곡선이 꺼지므로 기본값을 쓴다 (감사 T4)
        val wake = profile.driverWakeAt?.takeIf { it <= clock.wall } ?: defaultWakeAt(profile.tripStart)
        fatigue.wakeMono = clock.monoAt(wake)
    }

    /** 기상 시각을 모르면: 그날 07:00. 04~07시 출발은 출발 1시간 전, 00~04시 출발은 전날 07:00 */
    private fun defaultWakeAt(tripStart: Long): Long {
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(tripStart), config.zoneId)
        val minute = start.hour * 60 + start.minute
        val wakeToday = start.toLocalDate().atStartOfDay(config.zoneId).plusMinutes(config.defaultWakeMinuteOfDay.toLong())
        return when {
            minute >= config.defaultWakeMinuteOfDay -> wakeToday.toInstant().toEpochMilli()
            minute >= EARLY_DEPARTURE_FROM_MIN -> tripStart - 60 * 60_000L
            else -> wakeToday.minusDays(1).toInstant().toEpochMilli()
        }
    }

    private fun canSwapDriver(): Boolean =
        needs.tracked.count { it.occupant.type == OccupantType.ADULT || it.occupant.type == OccupantType.ELDERLY } >= 2

    private fun applyResult(promptId: String, ok: Boolean, mono: Long) {
        val (mem, rec) = policy.onResult(promptId, ok, mono, memory)
        memory = mem
        if (rec == null) return
        if (!ok) {
            // 상태 확인에 "아니": 운전 패턴 편차를 잠시 피로에 넣지 않는다 (본인 자기보고를 더 믿는다)
            if (rec.kind == PromptKind.CHECK_IN) styleMuteUntil = mono + config.styleMuteAfterDeclineMin * 60_000L
            return
        }
        val stop = rec.prompt.stop
        when (rec.kind) {
            PromptKind.CHECK_IN -> {
                // "응, 졸려": 가장 강한 신호. NOW로 올리고 커피·짧은 낮잠·교대를 권한다
                fatigue.sleepyVoiced = true
                requestRest(mono)
            }
            PromptKind.LAST_CHANCE, PromptKind.REST_SUGGESTION, PromptKind.URGENT_RESULT ->
                if (stop != null) {
                    accepted = Accepted(
                        stop.id, stop, rec.purposes.ifEmpty { setOf(Purpose.RESTROOM) },
                        suggested = rec.kind != PromptKind.URGENT_RESULT, groupDriven = rec.groupDriven, mono,
                    )
                } else if (rec.kind == PromptKind.REST_SUGGESTION) {
                    restWantedAt = mono
                }
            PromptKind.FRESH_AIR, PromptKind.INFO -> {}
        }
    }

    private fun requestRest(mono: Long) {
        restWantedAt = mono
        restAnswer.request(Purpose.REST)
    }

    private fun requestMeal(mono: Long) {
        mealWantedAt = mono
        mealAnswer.request(Purpose.MEAL)
    }

    /**
     * 지금 서면 식사하려는 정차로 볼 근거가 있는지 ([MealStopRule]): "배고파" 요청이 살아 있거나,
     * 수락한 정차의 목적에 식사가 있고 그곳에 닿았다(도착하면 목록에서 빠진다. 아직 앞에 멀리 남아 있으면 다른 곳에 선 것이다).
     */
    private fun mealIntent(mono: Long): Boolean {
        if (mealWantedAt != NONE && mono - mealWantedAt <= WANT_HOLD_MS) return true
        val a = accepted ?: return false
        if (Purpose.MEAL !in a.purposes) return false
        val cur = currentCandidates.firstOrNull { it.id == a.stopId }
        if (cur != null) return MealStopRule.proximityM(cur) <= config.mealPlaceRadiusM
        // 목록에 없다: 도착해서 빠졌을 수 있다. 마지막으로 본 거리가 가까웠을 때만 그렇게 본다. 멀리 있던 곳이 목록에서 잠깐 빠진 것은
        // (목록 깜빡임) 도착이 아니다
        return MealStopRule.proximityM(a.stop) <= MealStopRule.ARRIVAL_MAX_TRAVEL_M
    }

    /**
     * 수락한 곳을 지나쳤거나(후보에서 1분 넘게 사라짐) 오래되면 내린다.
     * 정차 중에는 내리지 않는다: 그곳에 도착해 서면 후보 목록에서 빠지지만, 그 정차를 '권해서 들른 정차'로 기록해야 한다.
     */
    private fun trackAccepted(mono: Long) {
        val a = accepted ?: return
        val cur = currentCandidates.firstOrNull { it.id == a.stopId }
        val d = cur?.let { route.distanceOf(it) } ?: Double.NaN
        if (cur != null && !d.isNaN() && d > 0.0) {
            a.stop = cur
            a.missingSince = NONE
        } else if (stops.parked) {
            a.missingSince = NONE
        } else if (a.missingSince == NONE) {
            a.missingSince = mono
        } else if (mono - a.missingSince > ACCEPT_MISSING_MS) {
            accepted = null
            return
        }
        if (mono - a.atMono > ACCEPT_HOLD_MS) accepted = null
    }

    /**
     * 앱에 내보내는 정차 후보(추천·질문)에 도착 시간을 채우는 한 경로. 내비 ETA가 있으면 그대로, 없으면 엔진이 정체를 반영해
     * 계산한 이번 도착 시간(RouteContext.arrivalOf). 추천·질문은 모두 [rec]·[ask]로 만들어 이 경로를 빠뜨리지 않는다.
     * 예전에는 몇 곳만 채워서 수락한 곳·"쉬고 싶어" 답·NOW 추천 카드에는 도착 시간이 없었다(감사 R22).
     */
    private fun withEta(stop: StopCandidate): StopCandidate {
        if (stop.etaS != null) return stop
        val a = route.arrivalOf(stop)
        return if (a.isFinite()) stop.copy(etaS = a) else stop
    }

    private fun rec(stop: StopCandidate, purposes: Set<Purpose>, reasons: List<Reason>) = Recommendation(withEta(stop), purposes, reasons)

    private fun ask(
        kind: PromptKind,
        speech: String,
        stop: StopCandidate? = null,
        distanceM: Double? = null,
        level: Level = Level.OK,
        user: Boolean = false,
        fresh: Boolean = true,
        purposes: Set<Purpose> = emptySet(),
        reasons: List<Reason> = emptyList(),
        groupDriven: Boolean = false,
    ) = PromptPolicy.Candidate(kind, speech, stop?.let(::withEta), distanceM, level, user, purposes, fresh, reasons, groupDriven)

    /**
     * 사용자의 말에 답할 곳([stop])이 이미 들르기로 한 곳이면 '들를까요?' 대신 낼 안내, 아니면 null.
     * 다시 물으면 '응'에 같은 곳으로 내비 명령이 한 번 더 간다 (UI 감사 #9: 안내를 시작한 곳을 "바로 안내할까요?"라고 또 물었다)
     */
    private fun goingThere(stop: StopCandidate?, distanceM: Double?, fresh: Boolean, prefix: String? = null): PromptPolicy.Candidate? {
        val a = accepted ?: return null
        if (stop == null || a.stopId != stop.id) return null
        val text = texts.alreadyAccepted(stop.name, distanceM)
        return ask(PromptKind.INFO, if (prefix != null) "$prefix $text" else text, user = true, fresh = fresh)
    }

    /**
     * 서 있는 곳: 지금 후보 가운데 [CoachConfig.mealPlaceRadiusM] 안의 가장 가까운 곳. [restroom]이면 화장실이 있고 닫히지 않은 곳만.
     * 앱이 위치 기준 거리로 넣어 준 후보가 있을 때만 안다(길안내가 없을 때, 시연). 길안내 중 경로 위 후보는 진입로를 지나면 목록에서
     * 빠지므로 여기서는 기억으로 짐작하지 않는다: 정체 속에서 방금 지나친 휴게소를 '가까이에 있다'고 답하느니 다음 화장실을 묻는 쪽이 낫다.
     */
    private fun parkedPlace(candidates: List<StopCandidate>, restroom: Boolean): StopCandidate? {
        var best: StopCandidate? = null
        var bestD = Double.POSITIVE_INFINITY
        for (i in candidates.indices) {
            val c = candidates[i]
            if (restroom && (!c.facilities.restroom || c.openNow == false)) continue
            val d = MealStopRule.proximityM(c)
            if (d <= config.mealPlaceRadiusM && d < bestD) { // 같은 거리면 목록 앞쪽 (결정적)
                best = c
                bestD = d
            }
        }
        return best
    }

    private fun addCand(c: PromptPolicy.Candidate, owner: Pending?) {
        promptCands += c
        promptOwners += owner
    }

    private fun recommend(
        ctx: PurposeContext,
        urgentList: List<RankedStop>?,
        lastChance: GapAnalyzer.LastChance?,
        lastChanceIsNearest: Boolean,
        nowRestroom: Opportunity?,
        wantsRest: Boolean,
        restTarget: Opportunity?,
        mealTarget: Opportunity?,
        candidates: List<StopCandidate>,
        speed: Float?,
    ): Recommendation? {
        askedRestroomStop(speed)?.let { return it }
        accepted?.let { a ->
            return rec(a.stop, a.purposes, listOf(Reason("NEXT_ACCEPTED", texts.reasonNextAccepted())))
        }
        urgentList?.firstOrNull()?.let { r ->
            return rec(urgentStop(r.stop, speed), purposesFor(r.stop, Purpose.RESTROOM, ctx), r.why)
        }
        // 여유가 없을 때(NOW): 가장 가까운 화장실이 마지막 기회이기도 하면 그 이유로 낸다 (질문도 그 문구로 묻는다).
        // 아니면 가장 빨리 갈 수 있는 화장실이 먼 마지막 기회보다 먼저다
        if (ctx.restroom == Level.NOW && !(lastChance != null && lastChanceIsNearest)) {
            val best = ranker.rank(candidates, ctx.wallNow, local, needs.has(OccupantType.INFANT), speed, 1, route).firstOrNull()
            val stop = best?.stop ?: nowRestroom?.stop
            if (stop != null) {
                return rec(stop, purposesFor(stop, Purpose.RESTROOM, ctx), listOf(nearestRestroomReason()))
            }
        }
        if (lastChance != null) {
            return rec(lastChance.opp.stop, purposesFor(lastChance.opp.stop, Purpose.RESTROOM, ctx), lastChanceReasons(lastChance))
        }
        if (wantsRest && restTarget != null) {
            return rec(restTarget.stop, purposesFor(restTarget.stop, Purpose.REST, ctx), listOf(Reason("NEXT_REST", texts.reasonNextRest())))
        }
        if (mealWantedAt != NONE && mealTarget != null) {
            return rec(mealTarget.stop, purposesFor(mealTarget.stop, Purpose.MEAL, ctx), listOf(Reason("NEXT_MEAL", texts.reasonNextMeal())))
        }
        return null
    }

    private fun nearestRestroomReason() = Reason("NEXT_NEAREST_RESTROOM", texts.reasonNextNearestRestroom())

    /**
     * 떠 있는 화장실 질문(마지막 기회, 여유 없음, "화장실 가고 싶대"의 답, 급해 결과)의 장소와 이유. 그런 질문이 없거나 그 장소가 지금
     * 후보에 없으면 null. 화면은 질문 카드를 띄우는 틱의 추천에서 이유를 가져온다(같은 장소일 때만). 추천이 다른 곳이거나 다른 이유면
     * "정체 전 마지막 화장실이에요"라고 묻는 카드에 '가까운 화장실' 딱지가 붙는다(UI 감사 #6). 카드는 엔진이 낸 틱보다 늦게 뜰 수도
     * 있어서 질문이 떠 있는 내내 맞춘다. 거리는 지금 후보의 값이다 (지금 목록에 없으면 물은 때의 거리를 지금 값처럼 내보내지 않는다).
     * 급해 결과의 도착 시간은 순위에 쓴 주행 시간이다 ([urgentStop]).
     */
    private fun askedRestroomStop(speed: Float?): Recommendation? {
        val q = policy.active ?: return null
        if ((q.kind != PromptKind.LAST_CHANCE && q.kind != PromptKind.URGENT_RESULT) || q.reasons.isEmpty()) return null
        val id = q.targetId ?: return null
        val cur = currentCandidates.firstOrNull { it.id == id } ?: return null
        val d = route.distanceOf(cur)
        if (d.isNaN() || d <= 0.0) return null
        val stop = if (q.kind == PromptKind.URGENT_RESULT) urgentStop(cur, speed) else cur
        return rec(stop, q.purposes.ifEmpty { setOf(Purpose.RESTROOM) }, q.reasons)
    }

    /**
     * 급해 순위에서 나온 장소를 질문·추천으로 내보낼 때: 도착 시간은 순위에 쓴 주행 시간이다 ([UrgentRanker.driveS]).
     * 음성과 목록의 '약 N분'(총 소요)은 이 값에 주차·도보·유턴을 더한 것이라, 말한 시간과 카드의 시간이 한 계산에서 나온다.
     * 예전에는 음성은 거리 ÷ 80km/h, 카드는 엔진 도착 시간이어서 "약 6분"이라고 말한 카드에 17분이 보였다 (닫힘 검증 E1)
     */
    private fun urgentStop(stop: StopCandidate, speed: Float?): StopCandidate {
        if (stop.etaS != null) return stop
        val s = ranker.driveS(stop, speed, route)
        return if (s.isFinite()) stop.copy(etaS = s) else stop
    }

    /** 한 정차에서 함께 해결할 수 있는 목적을 묶는다 */
    private fun purposesFor(stop: StopCandidate, base: Purpose, ctx: PurposeContext): Set<Purpose> {
        val set = EnumSet.of(base)
        val f = stop.facilities
        if (f.restroom && ctx.restroom >= Level.WATCH) set += Purpose.RESTROOM
        if (ctx.fatigue >= Level.WATCH) set += Purpose.REST
        if (f.food && (mealWantedAt != NONE || inMealWindow(ctx.wallNow))) set += Purpose.MEAL
        needs.firstOf(OccupantType.INFANT)?.let { t ->
            if ((f.diaperTable || f.nursingRoom) && (t.voiced || needs.remainingMin(t, ctx.mono) <= config.restroomWatchMin)) {
                set += Purpose.FEEDING
            }
        }
        if (f.petArea && needs.has(OccupantType.DOG)) set += Purpose.PET
        return set
    }

    private fun pickRestTarget(candidates: List<StopCandidate>, fatigueGauge: Gauge): Opportunity? {
        route.opportunities(candidates, restOpps, RouteContext::isRestOpportunity)
        val limitS = (fatigueGauge.minutes ?: 0) * 60.0
        var preferredInLimit: Opportunity? = null
        var anyInLimit: Opportunity? = null
        var preferred: Opportunity? = null
        var any: Opportunity? = null
        for (o in restOpps) {
            if (o.distanceM < config.askWindowNearM) continue
            val pref = RouteContext.isPreferredRestKind(o.stop)
            val inLimit = o.arriveS <= limitS
            if (any == null) any = o
            if (pref && preferred == null) preferred = o
            if (inLimit && anyInLimit == null) anyInLimit = o
            if (pref && inLimit && preferredInLimit == null) preferredInLimit = o
        }
        if (fatigueGauge.level == Level.NOW) return preferred?.takeIf { p -> any == null || p.arriveS - any.arriveS <= NEAR_ENOUGH_S } ?: any
        return preferredInLimit ?: anyInLimit ?: preferred ?: any
    }

    private fun pickMealTarget(candidates: List<StopCandidate>): Opportunity? {
        route.opportunities(candidates, mealOpps, RouteContext::isMealOpportunity)
        return mealOpps.firstOrNull { it.distanceM >= config.askWindowNearM }
    }

    private fun pickFuelTarget(candidates: List<StopCandidate>): Opportunity? {
        route.opportunities(candidates, fuelOpps, RouteContext::isFuelOpportunity)
        return fuelOpps.firstOrNull { it.distanceM >= config.askWindowNearM }
    }

    /**
     * 여럿이 타서 앞당겨진 마지막 기회인지. 판단 마감은 '누군가 필요해질 확률'이라 가장 급한 한 사람의 마감보다 이르다
     * (RestroomNeedModel). 한 사람 기준 마감으로 같은 판정을 다시 했을 때(커버리지 규칙 포함) 물을 일이 없거나 더 뒤의 기회면
     * 묶음 때문이다. 이때는 게이지(한 사람 기준)가 넉넉해 보이는데 묻게 되므로 이유를 붙이고(감사 O6), 수락해서 선 정차를 누구의
     * 사건으로도 배우지 않는다. 한 사람만 봐도 이곳이나 더 앞에서 묻는다면 묶음 때문이 아니다: 예전에는 '이곳이 아니면'으로 봐서
     * 과민성 방광 탑승자 혼자여도 같은 곳에서 묻는데 '4명이 타서'가 붙었고(커버리지 없이 다시 판정했다), 한 사람 기준이 더 앞인
     * 경우에도 붙었다 (닫힘 검증 E5c). 마지막 기회가 있을 때만 계산한다.
     */
    private fun broughtForwardByGroup(lc: GapAnalyzer.LastChance, deadlineMin: Double?, mono: Long): Boolean {
        // 커버리지 규칙은 마감과 무관하다: 한 사람 기준으로도 같은 곳이다
        if (lc.trigger == GapAnalyzer.Trigger.PREFERENCE || needs.tracked.size < 2 || deadlineMin == null) return false
        val solo = needs.soloDeadlineMin(mono, config.restroomDecisionProbability) ?: return false
        if (solo - deadlineMin < GROUP_REASON_MIN_DIFF_MIN) return false
        val alone = gap.analyze(restroomOpps, route, solo, needs.sensitive, needs.preferredMaxGapMin())
        return alone == null || alone.index > lc.index
    }

    private fun lastChanceSpeech(lc: GapAnalyzer.LastChance): String = when (lc.trigger) {
        GapAnalyzer.Trigger.CONGESTION -> texts.lastChanceCongestion(lc.opp.stop.name, (lc.congestionS / 60.0).roundToInt())
        else -> texts.lastChanceGap(lc.opp.stop.name, (lc.gapS / 60.0).roundToInt())
    }

    /** 마지막 기회의 이유. 여럿이 타서 앞당겨진 것이면 그 설명을 덧붙인다 */
    private fun lastChanceReasons(lc: GapAnalyzer.LastChance): List<Reason> =
        if (lc.groupDriven) listOf(lastChanceReason(lc), Reason("LAST_CHANCE_GROUP", texts.reasonLastChanceGroup(needs.tracked.size)))
        else listOf(lastChanceReason(lc))

    private fun lastChanceReason(lc: GapAnalyzer.LastChance): Reason = when (lc.trigger) {
        GapAnalyzer.Trigger.CONGESTION ->
            Reason("LAST_CHANCE_CONGESTION", texts.reasonLastChanceCongestion((lc.congestionS / 60.0).roundToInt()))
        GapAnalyzer.Trigger.GAUGE -> Reason("LAST_CHANCE_GAP", texts.reasonLastChanceGap((lc.gapS / 60.0).roundToInt()))
        GapAnalyzer.Trigger.PREFERENCE -> Reason(
            "LAST_CHANCE_PREFERENCE",
            texts.reasonLastChancePreference((lc.gapS / 60.0).roundToInt(), needs.preferredMaxGapMin() ?: 0),
        )
    }

    /** 묻지 않는 때: 다음 회전 500m 안 또는 지금 속도로 30초 안, 최근 10초 안의 제동·방향지시등·큰 조향 */
    private fun isQuiet(input: CoachInput, speedKph: Float?): Boolean {
        val m = input.route?.nextManeuverDistanceM
        if (m != null && m >= 0.0) {
            if (m < config.maneuverQuietM) return true
            if (speedKph != null && speedKph > 1f && m / (speedKph / 3.6) < config.maneuverQuietS) return true
        }
        return quiet.quiet(input.now)
    }

    private fun inMealWindow(wall: Long): Boolean {
        val m = local.minuteOfDay(wall)
        return config.mealWindows.any { m in it }
    }

    /**
     * 이번 update의 차속: 입력 차속, 없으면 이번에 쌓인 표본 중 잰 지 [SPEED_MAX_AGE_MS] 안의 마지막 값. 앞 update의 값은 들고 있지 않는다.
     * 앱은 매 update(약 1초)마다 차속을 넣으므로 비어 있으면 신호가 끊긴 것이다. 예전에는 마지막 차속을 끝없이 들고 있어
     * 기어·차속이 끊긴 뒤에도 '시속 100km로 달리는 중'으로 보고 질문하고 정차를 못 봤다(감사 S3).
     */
    private fun currentSpeed(input: CoachInput): Float? {
        input.speedKph?.let { return it }
        val samples = input.samples
        for (i in samples.indices.reversed()) {
            val s = samples[i]
            val v = s.speedKph ?: continue
            if (abs(input.now - s.t) <= SPEED_MAX_AGE_MS) return v
        }
        return null
    }

    private companion object {
        /** 묶음 마감과 한 사람 마감의 차이가 이보다 작으면 같은 판단으로 본다 (한 사람이 거의 다 정하는 경우, 분) */
        const val GROUP_REASON_MIN_DIFF_MIN = 1.0
        /** "쉬고 싶어"·"배고파" 요청을 추천에 유지하는 시간 */
        const val WANT_HOLD_MS = 30 * 60_000L
        /** 수락한 곳을 추천에 유지하는 최대 시간 */
        const val ACCEPT_HOLD_MS = 90 * 60_000L
        /** 수락한 곳이 후보에서 이만큼 사라지면 지나친 것으로 본다 */
        const val ACCEPT_MISSING_MS = 60_000L
        /** 이 시각부터 07:00 사이 출발은 '일찍 일어나 출발'로 본다 (자정부터 분) */
        const val EARLY_DEPARTURE_FROM_MIN = 4 * 60
        /** NOW일 때 휴게소·졸음쉼터가 가장 가까운 곳보다 이만큼 안쪽으로 늦으면 그쪽을 고른다 (초) */
        const val NEAR_ENOUGH_S = 5 * 60.0
        /**
         * 표본 차속을 이번 차속으로 쓰는 최대 나이 (ms). 표본은 update(약 1초) 사이에 쌓이므로 보통 1초 안이다.
         * 5초는 엔진의 가장 짧은 시간 규칙(제동 뒤 10초 조용히)보다 짧아, 늦게 온 표본이 판단을 바꿀 수 있는 시간을 그 아래로 묶는다
         */
        const val SPEED_MAX_AGE_MS = 5_000L
    }
}
