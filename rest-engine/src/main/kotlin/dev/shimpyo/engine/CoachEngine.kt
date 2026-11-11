package dev.shimpyo.engine

import dev.shimpyo.engine.EngineMath.NONE
import java.time.Instant
import java.time.ZonedDateTime
import java.util.EnumSet
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
 * 3. 음성 의도 처리 (수락·거절, "화장실 급해", "아이가 화장실", "졸려", "쉬고 싶어", "배고파", "얼마나 남았어").
 * 4. 화장실 게이지([RestroomNeedModel]), 운전 패턴([DrivingStyleAnalyzer]), 피로 게이지([FatigueModel]).
 * 5. 경로 앞 화장실 기회와 도착 시간([RouteContext]) → 마지막 기회([GapAnalyzer]), 급해 순위([UrgentRanker]).
 * 6. 다음 추천과 질문 후보 → [PromptPolicy]가 지금 물을 하나를 고른다. 공조 제안은 따로 낸다.
 *
 * 질문: [CoachOutput.prompt]는 대답이 오거나 만료될 때까지 같은 id로 계속 나온다. 앱은 새 id일 때만 말하고,
 * 대답은 [VoiceIntent.Accept]/[VoiceIntent.Decline]을 다음 update에 넣거나 [onPromptResult]로 알린다.
 * 피로 질문 사다리: SOON(또는 운전 패턴 변화 지속) → CHECK_IN "혹시 졸리세요?" → "응"이면 졸림으로 보고 강한 휴식 권유,
 * "아니"면 운전 패턴 편차를 20분 동안 피로에 넣지 않는다. NOW(2시간 리듬·졸림 곡선) → REST_SUGGESTION.
 * 후보([CoachInput.candidates])는 매 update마다 지금 위치 기준 거리로 넘긴다.
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
    private var needs = RestroomNeedModel(config)
    private var style = DrivingStyleAnalyzer(config)
    private var fatigue = FatigueModel(config, local)
    private var policy = PromptPolicy(config, random)
    private var quiet = QuietTracker(config)

    private class Accepted(val stopId: String, var stop: StopCandidate, val purposes: Set<Purpose>, val kind: PromptKind, val atMono: Long) {
        var missingSince: Long = NONE
    }

    private var accepted: Accepted? = null
    private var restWantedAt = NONE
    private var restPromptPending = false
    private var mealWantedAt = NONE
    private var mealPromptPending = false
    private var styleMuteUntil = NONE
    private var lastSpeedKph: Float? = null
    private var cruiseOn = false

    private val restroomOpps = ArrayList<Opportunity>()
    private val restOpps = ArrayList<Opportunity>()
    private val mealOpps = ArrayList<Opportunity>()
    private val promptCands = ArrayList<PromptPolicy.Candidate>(6)
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
    fun update(input: CoachInput): CoachOutput {
        val firstUpdate = !clock.started
        val dtMs = clock.advance(input.now)
        if (firstUpdate) syncProfile()
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
        val speed = input.speedKph ?: lastSampleSpeed(samples) ?: lastSpeedKph
        if (speed != null) lastSpeedKph = speed
        fatigue.monotony = cruiseOn && speed != null && speed >= config.monotonyMinKph

        // 2. 정차
        val ev = stops.update(mono, input.gear, speed, input.doorOpened)
        fatigue.advance(dtMs, !stops.parked, input.now, mono)
        if (ev.restroomStopAt != NONE) {
            val acc = accepted
            val suggested = acc != null && (acc.kind == PromptKind.LAST_CHANCE || acc.kind == PromptKind.REST_SUGGESTION)
            memory = needs.onRestroomStop(ev.restroomStopAt, memory, suggested)
            accepted = null
        }
        if (ev.restAt != NONE) {
            fatigue.onRest(mono)
            style.resetScore()
            restWantedAt = NONE
            restPromptPending = false
            accepted = null
        }
        needs.tick(mono)
        fatigue.onCabin(input.cabin, mono)

        // 3. 음성 의도
        var urgentRequested = false
        var howLong = false
        if (input.intent != null) policy.noteInteraction(mono)
        when (val intent = input.intent) {
            null -> {}
            is VoiceIntent.Accept -> applyResult(intent.promptId, true, mono)
            is VoiceIntent.Decline -> applyResult(intent.promptId, false, mono)
            is VoiceIntent.OccupantNeed -> when (intent.purpose) {
                Purpose.RESTROOM, Purpose.FEEDING, Purpose.PET ->
                    memory = needs.onVoiced(intent.occupantId, intent.purpose, mono, memory)
                Purpose.REST -> requestRest(mono)
                Purpose.MEAL -> requestMeal(mono)
                Purpose.FUEL -> {}
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
            VoiceIntent.HowLong -> howLong = true
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
        val deadline = needs.groupDeadlineMin(mono, config.restroomDecisionProbability)
        val lastChance = gap.analyze(restroomOpps, route, deadline, needs.sensitive, needs.preferredMaxGapMin())
        trackAccepted(mono)

        val urgentList = if (urgentRequested) {
            ranker.rank(
                input.candidates, input.now, local, needs.has(OccupantType.INFANT), speed,
                if (stops.parked) config.urgentMaxResults else config.urgentMaxResultsDriving,
            )
        } else null

        val deviation = styleOut.deviation ?: 0.0
        val wantsRest = restWantedAt != NONE || fatigueGauge.level >= Level.SOON
        val restTarget = if (wantsRest || restPromptPending) pickRestTarget(input.candidates, fatigueGauge) else null
        val mealTarget = if (mealWantedAt != NONE || mealPromptPending) pickMealTarget(input.candidates) else null
        val nowRestroom = if (restroom.level == Level.NOW && lastChance == null && accepted == null && urgentList == null) {
            restroomOpps.firstOrNull { it.distanceM >= config.askWindowNearM }
        } else null

        // 6. 다음 추천
        val ctx = PurposeContext(restroom.level, fatigueGauge.level, input.now, mono)
        val next = recommend(ctx, urgentList, lastChance, nowRestroom, wantsRest, restTarget, mealTarget, input.candidates, speed)

        // 7. 질문 (사용자 응답 먼저, 그다음 엔진이 먼저 거는 질문을 우선순위 순으로)
        promptCands.clear()
        if (urgentRequested) {
            val top = urgentList?.firstOrNull()
            promptCands += if (top != null) {
                PromptPolicy.Candidate(
                    PromptKind.URGENT_RESULT, texts.urgentResult(top.stop.name, (top.totalTimeS / 60.0).roundToInt()),
                    top.stop, null, restroom.level, userInitiated = true, purposes = purposesFor(top.stop, Purpose.RESTROOM, ctx),
                )
            } else {
                PromptPolicy.Candidate(PromptKind.INFO, texts.urgentNone(), userInitiated = true)
            }
        }
        if (restPromptPending) {
            restPromptPending = false
            val t = restTarget
            val speech = if (fatigue.sleepyVoiced) texts.restSuggestionSleepy(t?.stop?.name, t?.distanceM, canSwapDriver())
            else texts.restSuggestionWanted(t?.stop?.name, t?.distanceM)
            promptCands += PromptPolicy.Candidate(
                PromptKind.REST_SUGGESTION, speech, t?.stop, t?.distanceM, fatigueGauge.level, userInitiated = true,
                purposes = t?.let { purposesFor(it.stop, Purpose.REST, ctx) } ?: setOf(Purpose.REST),
            )
        }
        if (mealPromptPending) {
            mealPromptPending = false
            val t = mealTarget
            promptCands += if (t != null) {
                PromptPolicy.Candidate(
                    PromptKind.REST_SUGGESTION, texts.mealSuggestion(t.stop.name, t.distanceM), t.stop, t.distanceM,
                    fatigueGauge.level, userInitiated = true, purposes = purposesFor(t.stop, Purpose.MEAL, ctx),
                )
            } else {
                PromptPolicy.Candidate(PromptKind.INFO, texts.mealNone(), userInitiated = true)
            }
        }
        if (howLong) {
            val who = restroom.drivenBy?.let { id -> needs.tracked.firstOrNull { it.id == id } }
                ?.takeIf { needs.tracked.size > 1 }?.let { texts.occupantName(it.occupant) }
            val nextOpp = restroomOpps.firstOrNull()
            promptCands += PromptPolicy.Candidate(
                PromptKind.INFO,
                texts.howLong(restroom.minutes, who, fatigueGauge.minutes, nextOpp?.stop?.name, nextOpp?.distanceM),
                userInitiated = true,
            )
        }
        // 기어 P로 서 있는 동안에는 앞쪽 정차를 묻지 않는다 (이미 서 있다. 휴식은 15분, 화장실 시계는 3분 뒤에야 되돌아간다)
        val moving = !stops.parked
        if (accepted == null && moving) {
            if (lastChance != null) {
                promptCands += PromptPolicy.Candidate(
                    PromptKind.LAST_CHANCE, lastChanceSpeech(lastChance), lastChance.opp.stop, lastChance.opp.distanceM,
                    restroom.level, purposes = purposesFor(lastChance.opp.stop, Purpose.RESTROOM, ctx),
                )
            } else if (nowRestroom != null) {
                promptCands += PromptPolicy.Candidate(
                    PromptKind.LAST_CHANCE, texts.restroomNowSuggestion(nowRestroom.stop.name, nowRestroom.distanceM),
                    nowRestroom.stop, nowRestroom.distanceM, Level.NOW,
                    purposes = purposesFor(nowRestroom.stop, Purpose.RESTROOM, ctx),
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
                promptCands += PromptPolicy.Candidate(
                    PromptKind.REST_SUGGESTION, speech, t?.stop, t?.distanceM, fatigueGauge.level,
                    purposes = t?.let { purposesFor(it.stop, Purpose.REST, ctx) } ?: setOf(Purpose.REST),
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
            promptCands += PromptPolicy.Candidate(PromptKind.CHECK_IN, speech, level = fatigueGauge.level)
        }
        val freshAir = fat.recircMin >= config.recirculationFreshAirMin &&
            (needs.humanCount >= config.freshAirMinOccupants || fatigueGauge.level >= Level.SOON)
        if (freshAir && !input.cabinAutoAllowed) {
            // 환기는 편의 기능: 피로 단계를 싣지 않는다 (피로가 올라도 60분 쉼·시간당 상한을 그대로 받는다)
            promptCands += PromptPolicy.Candidate(PromptKind.FRESH_AIR, texts.freshAir(fat.recircMin), level = Level.OK)
        }
        val prompt = policy.decide(promptCands, input.now, mono, isQuiet(input, speed), distanceOfId, memory)

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

    /** 급해 모드: 후보를 총 소요 시간 순으로 정렬해 돌려준다 (상태를 바꾸지 않는다) */
    fun urgent(input: CoachInput): List<RankedStop> {
        ensureStarted(input.now)
        val speed = input.speedKph ?: lastSampleSpeed(input.samples) ?: lastSpeedKph
        return ranker.rank(
            input.candidates, input.now, local, needs.has(OccupantType.INFANT), speed, config.urgentMaxResults,
        )
    }

    /** 피트스톱 계획: 경로 앞 후보로 정차 계획을 만든다 (상태를 바꾸지 않는다) */
    fun plan(input: CoachInput): PitStopPlan {
        ensureStarted(input.now)
        val mono = clock.peek(input.now)
        val speed = input.speedKph ?: lastSampleSpeed(input.samples) ?: lastSpeedKph
        val ctx = RouteContext(config)
        ctx.prepare(input.route, speed)
        val opps = ArrayList<Opportunity>()
        ctx.opportunities(input.candidates, opps, RouteContext::isRestroomOpportunity)

        val p = config.restroomDecisionProbability
        val fe = fatigue.evaluate(
            input.now, mono, style.deviation, style.persistent,
            styleMuteUntil != NONE && mono < styleMuteUntil, profile.driverSleepHours, texts,
        )
        val restroomFirstS = needs.groupDeadlineMin(mono, p)?.let { it * 60.0 } ?: Double.POSITIVE_INFINITY
        val fatigueFirstS = (fe.gauge.minutes ?: 0) * 60.0
        val sleep = profile.driverSleepHours
        val awakeNow = fatigue.awakeHours(mono)
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
            restroomFullS = (needs.groupDeadlineMin(mono, p, fromReset = true) ?: Double.POSITIVE_INFINITY) * 60.0,
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
     * 탑승자·수면 정보를 바꾼다. 같은 tripStart면 시계와 상태를 이어 가고(새 탑승자만 추가),
     * tripStart가 다르면 새 여정으로 보고 주행 상태를 새로 시작한다. 개인 기억은 유지한다.
     */
    fun setProfile(profile: TripProfile) {
        val newTrip = profile.tripStart != this.profile.tripStart
        this.profile = profile
        if (newTrip) {
            stops = StopTracker(config)
            needs = RestroomNeedModel(config)
            style = DrivingStyleAnalyzer(config)
            fatigue = FatigueModel(config, local)
            policy = PromptPolicy(config, random)
            quiet = QuietTracker(config)
            accepted = null
            restWantedAt = NONE
            restPromptPending = false
            mealWantedAt = NONE
            mealPromptPending = false
            styleMuteUntil = NONE
        }
        if (clock.started) syncProfile()
    }

    /** 저장용 스냅샷 */
    fun memory(): PersonalMemory = memory

    // ------------------------------------------------------------------

    private class PurposeContext(val restroom: Level, val fatigue: Level, val wallNow: Long, val mono: Long)

    private fun ensureStarted(now: Long) {
        if (!clock.started) {
            clock.advance(now)
            syncProfile()
        }
    }

    private fun syncProfile() {
        needs.sync(profile, clock, memory)
        fatigue.wakeMono = clock.monoAt(profile.driverWakeAt ?: defaultWakeAt(profile.tripStart))
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
                    accepted = Accepted(stop.id, stop, rec.purposes.ifEmpty { setOf(Purpose.RESTROOM) }, rec.kind, mono)
                } else if (rec.kind == PromptKind.REST_SUGGESTION) {
                    restWantedAt = mono
                }
            PromptKind.FRESH_AIR, PromptKind.INFO -> {}
        }
    }

    private fun requestRest(mono: Long) {
        restWantedAt = mono
        restPromptPending = true
    }

    private fun requestMeal(mono: Long) {
        mealWantedAt = mono
        mealPromptPending = true
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

    private fun recommend(
        ctx: PurposeContext,
        urgentList: List<RankedStop>?,
        lastChance: GapAnalyzer.LastChance?,
        nowRestroom: Opportunity?,
        wantsRest: Boolean,
        restTarget: Opportunity?,
        mealTarget: Opportunity?,
        candidates: List<StopCandidate>,
        speed: Float?,
    ): Recommendation? {
        accepted?.let { a ->
            return Recommendation(a.stop, a.purposes, listOf(Reason("NEXT_ACCEPTED", texts.reasonNextAccepted())))
        }
        urgentList?.firstOrNull()?.let { r ->
            return Recommendation(r.stop, purposesFor(r.stop, Purpose.RESTROOM, ctx), r.why)
        }
        if (ctx.restroom == Level.NOW) {
            val best = ranker.rank(candidates, ctx.wallNow, local, needs.has(OccupantType.INFANT), speed, 1).firstOrNull()
            val stop = best?.stop ?: nowRestroom?.stop
            if (stop != null) {
                return Recommendation(
                    stop, purposesFor(stop, Purpose.RESTROOM, ctx),
                    listOf(Reason("NEXT_NEAREST_RESTROOM", texts.reasonNextNearestRestroom())),
                )
            }
        }
        if (lastChance != null) {
            return Recommendation(
                lastChance.opp.stop, purposesFor(lastChance.opp.stop, Purpose.RESTROOM, ctx), listOf(lastChanceReason(lastChance)),
            )
        }
        if (wantsRest && restTarget != null) {
            return Recommendation(
                restTarget.stop, purposesFor(restTarget.stop, Purpose.REST, ctx),
                listOf(Reason("NEXT_REST", texts.reasonNextRest())),
            )
        }
        if (mealWantedAt != NONE && mealTarget != null) {
            return Recommendation(
                mealTarget.stop, purposesFor(mealTarget.stop, Purpose.MEAL, ctx),
                listOf(Reason("NEXT_MEAL", texts.reasonNextMeal())),
            )
        }
        return null
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

    private fun lastChanceSpeech(lc: GapAnalyzer.LastChance): String = when (lc.trigger) {
        GapAnalyzer.Trigger.CONGESTION -> texts.lastChanceCongestion(lc.opp.stop.name, (lc.congestionS / 60.0).roundToInt())
        else -> texts.lastChanceGap(lc.opp.stop.name, (lc.gapS / 60.0).roundToInt())
    }

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

    private fun lastSampleSpeed(samples: List<DrivingSample>): Float? {
        for (i in samples.indices.reversed()) samples[i].speedKph?.let { return it }
        return null
    }

    private companion object {
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
    }
}
