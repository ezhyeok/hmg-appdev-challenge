package dev.shimpyo.app.ui.drive

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.shimpyo.app.ui.Labels
import dev.shimpyo.app.ui.beyondDestination
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.limitChars
import dev.shimpyo.app.ui.livePlan
import dev.shimpyo.app.ui.theme.DriveLimits
import dev.shimpyo.coach.CandidatesState
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.GoToOutcome
import dev.shimpyo.coach.GoToResult
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.Recommendation
import dev.shimpyo.engine.StopCandidate

/*
 * 주행 화면이 그리는 값. CoachUiState → 게이지·추천 카드·행동 줄로 바꾸는 순수 함수라 화면 없이 시험할 수 있다.
 * 상태별로 무엇을 보이는지는 docs/design/coach-layer/ui.md의 표가 기준이다.
 */

/** 게이지 카드 값 */
@Immutable
data class GaugeModel(val minutes: Int?, val level: Level?, val fraction: Float?, val tick: Float?, val reason: String?)

/** 화장실 게이지: 막대 = 남은 분 ÷ 게이지를 정한 탑승자의 기준 간격(개인 간격 우선), 눈금 = SOON 경계(20분) */
fun restroomGauge(state: CoachUiState): GaugeModel {
    val g = state.restroom
    val who = state.occupants.firstOrNull { it.id == g?.drivenBy }
    val baseline = Labels.baselineMin(who).toFloat()
    val tick = (Labels.config.restroomSoonMin / baseline).coerceIn(0f, 1f)
    if (g != null && !state.serviceRunning) return stoppedGauge(tick)
    val whoName = who?.let { Labels.publicName(it) }
    return GaugeModel(
        minutes = g?.minutes,
        level = g?.takeIf { it.minutes != null }?.level,
        fraction = g?.minutes?.let { (it / baseline).coerceIn(0f, 1f) },
        tick = tick,
        reason = g?.reasons?.let { restroomReason(it, whoName) }
            ?: whoName?.takeIf { g?.minutes != null }?.let { "$it 기준" },
    )
}

/** 화장실 이유 한 줄: 직접 요청 > 정차 간 상한(숫자를 정한 이유) > 누구 기준 > 개인 기록 > 설정 필요 */
fun restroomReason(reasons: List<Reason>, who: String?): String? {
    val order = listOf("RESTROOM_VOICED", "RESTROOM_CAP", "RESTROOM_ELAPSED", "RESTROOM_PERSONAL", "RESTROOM_NONE")
    return reasons.sortedBy { r -> order.indexOf(r.code).let { if (it < 0) order.size else it } }
        .firstNotNullOfOrNull { Labels.shortReason(it, who) }
}

/** 피로 게이지: 막대 = 남은 분 ÷ 120분, 눈금 = SOON 경계(연속 100분 = 남은 20분) */
fun fatigueGauge(state: CoachUiState): GaugeModel {
    val g = state.fatigue
    val full = Labels.config.fatigueNowMin.toFloat()
    val tick = ((full - Labels.config.fatigueSoonMin) / full).coerceIn(0f, 1f)
    if (g != null && !state.serviceRunning) return stoppedGauge(tick)
    return GaugeModel(
        minutes = g?.minutes,
        level = g?.takeIf { it.minutes != null }?.level,
        fraction = g?.minutes?.let { (it / full).coerceIn(0f, 1f) },
        tick = tick,
        reason = g?.reasons?.let(::fatigueReason) ?: if (g?.minutes != null && state.parked) "정차 중" else null,
    )
}

/** 서비스가 멈춘 뒤 마지막 값이 그대로 남아 살아 있는 것처럼 보이지 않게 (UI 감사 #18) */
private fun stoppedGauge(tick: Float) = GaugeModel(minutes = null, level = null, fraction = null, tick = tick, reason = "코치 멈춤")

/**
 * 피로 이유 한 줄: 직접 말함 > 강한 권고 > 운전 패턴 > 환기 > 시간대·깨어 있은 시간 > 수면 > 단조로움 > 연속 운전.
 * 연속 운전은 늘 붙는 기본 이유라 맨 뒤다: 시간대 때문에 오른 게이지가 "연속 20분"으로 읽히던 것(UI 감사 #17)
 */
fun fatigueReason(reasons: List<Reason>): String? {
    val order = listOf(
        "FATIGUE_VOICED", "FATIGUE_STRONG", "FATIGUE_STYLE", "FATIGUE_RECIRC", "FATIGUE_BODY_CLOCK", "FATIGUE_NIGHT",
        "FATIGUE_AFTERNOON", "FATIGUE_AWAKE_LONG", "FATIGUE_SHORT_SLEEP", "FATIGUE_MONOTONY", "FATIGUE_DRIVING",
    )
    return reasons.sortedBy { r -> order.indexOf(r.code).let { if (it < 0) order.size else it } }
        .firstNotNullOfOrNull { Labels.shortReason(it) }
}

/** 추천 카드 내용 */
@Immutable
data class StopCardModel(
    /** 맥락 한 줄 (예: "정체 전 마지막 화장실") */
    val context: String,
    /** 장소 이름, 또는 장소가 없는 질문·안내 문장 */
    val title: String,
    val meta: String?,
    val chips: List<String>,
    val icon: ImageVector,
    /** 질문 중이거나 확인이 필요한 결과(경유지 실패)면 그 단계. 카드 테두리를 이 색 4dp로 바꾼다 */
    val askingLevel: Level?,
    /** title이 장소 이름이 아니라 문장이다 (여러 줄 허용) */
    val sentence: Boolean = false,
    /** 빈 상태 */
    val muted: Boolean = false,
)

/** 경로 앞에서 가장 가까운 화장실 (경로 거리, 없으면 직선거리 기준). 길안내 중이면 목적지까지만 */
internal fun nearestAhead(state: CoachUiState) = state.ahead
    .filter { s ->
        val d = s.routeDistanceM ?: s.straightDistanceM ?: -1.0
        s.facilities.restroom && d >= 0.0 && !(s.routeDistanceM != null && beyondDestination(state, d))
    }
    .minByOrNull { it.routeDistanceM ?: it.straightDistanceM ?: Double.MAX_VALUE }

/**
 * 질문에 대한 왼쪽 버튼 글자. null이면 [확인] 하나만 둔다.
 * 좌표 없는 경로 위 시설(TBT)도 "들르기"다: 경유지를 넣지 못해도 코치가 ON_ROUTE(진입로로 들어가면 된다)로 답한다.
 * 외기 전환을 지원하지 않는 차(cabinSupported = false)에서는 받을 수 없는 제안이라 [확인]만 둔다 (UI 감사 #16).
 */
fun acceptLabel(p: Prompt, cabinSupported: Boolean = true): String? = when (p.kind) {
    PromptKind.LAST_CHANCE, PromptKind.URGENT_RESULT -> "들르기"
    PromptKind.REST_SUGGESTION -> if (p.stop != null) "들르기" else "쉬어 갈게요"
    PromptKind.CHECK_IN -> "쉬어 갈게요"
    PromptKind.FRESH_AIR -> if (cabinSupported) "외기로 전환" else null
    PromptKind.INFO -> null
}

/**
 * 장소 없는 질문의 화면 글자 (screens.md 1.5: TTS 문장을 줄여 보인다). 마지막 질문 문장(…?)이 짧으면 그것,
 * 길면 종류별 짧은 질문. 질문이 없는 안내(INFO)는 첫 문장. 40sp·3줄에 들어가는 길이다 (UI 감사 #11)
 */
fun shortQuestion(p: Prompt): String {
    val sentences = splitSentences(p.speech)
    val question = sentences.lastOrNull { it.endsWith("?") }
    if (question != null && question.length <= SHORT_QUESTION_CHARS) return question
    return when (p.kind) {
        PromptKind.CHECK_IN -> "혹시 졸리세요?"
        PromptKind.FRESH_AIR -> "잠깐 환기할까요?"
        PromptKind.REST_SUGGESTION -> "가까운 곳에서 쉬어 갈까요?"
        PromptKind.LAST_CHANCE, PromptKind.URGENT_RESULT -> "들를까요?"
        PromptKind.INFO -> sentences.firstOrNull()?.let { limitChars(it, SHORT_QUESTION_CHARS) } ?: p.speech
    }
}

/** 질문 앞의 설명 문장 (예: "1시간 35분째 운전 중이에요"). 카드 둘째 줄에 넣을 만큼 짧을 때만 */
fun promptLead(p: Prompt): String? {
    val sentences = splitSentences(p.speech)
    if (sentences.size < 2) return null
    return sentences.first().removeSuffix(".").takeIf { it.length <= LEAD_CHARS && !it.endsWith("?") }
}

/** 문장 끝(. ? !) 뒤 공백에서 나눈다. "3.5km"처럼 숫자 안의 점에서는 나누지 않는다 */
private fun splitSentences(text: String): List<String> =
    text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }

private val SENTENCE_END = Regex("(?<=[.?!])\\s+")
private const val SHORT_QUESTION_CHARS = 30
private const val LEAD_CHARS = 24

/**
 * 추천 카드. 우선순위: 서비스 멈춤 > 질문 > 경유지 실패 > (정차 중) 다음 계획 > 엔진 추천 > 경로 앞 첫 화장실 > 빈 상태.
 * 칩은 주행 중 [DriveLimits.MAX_REASON_CHIPS]개, 정차 중 3개까지. 문구는 주행 제한의 글자 수([maxChars])로 자른다.
 */
fun stopCard(state: CoachUiState, driving: Boolean, maxChars: Int = DriveLimits.MAX_STRING_LENGTH): StopCardModel {
    val maxChips = if (driving) DriveLimits.MAX_REASON_CHIPS else 3
    val drivenBy = state.occupants.firstOrNull { it.id == state.restroom?.drivenBy }
    val who = drivenBy?.let { Labels.publicName(it) }
    val prompt = state.prompt
    val failed = state.goToResult?.takeIf { it.outcome == GoToOutcome.FAILED }
    val card = when {
        !state.serviceRunning -> serviceCard(state)
        prompt != null -> promptCard(prompt, state, maxChips, who)
        failed != null -> failedGoToCard(failed)
        !driving && state.parked && livePlan(state).isNotEmpty() -> planCard(state, maxChips)
        state.next != null -> recommendationCard(state.next!!, state, maxChips, who)
        else -> nearestAhead(state)?.let { nearestCard(it, state) } ?: emptyCard(state)
    }
    return card.copy(
        context = limitChars(card.context, maxChars),
        title = limitChars(card.title, maxChars),
        meta = card.meta?.let { limitChars(it, maxChars) },
    )
}

private fun serviceCard(state: CoachUiState): StopCardModel {
    val everRan = state.restroom != null || state.fatigue != null
    return StopCardModel(
        context = if (state.guiding) "다음 화장실" else "진행 방향 가까운 화장실",
        title = if (everRan) "코치가 멈췄어요" else "준비하고 있어요",
        meta = if (everRan) "앱을 다시 열면 이어서 계산해요" else "잠시 후 게이지와 추천이 나타나요",
        chips = emptyList(),
        icon = ShimpyoIcons.Wc,
        askingLevel = null,
        sentence = true,
        muted = true,
    )
}

/**
 * 질문 카드. 맥락과 칩은 질문을 띄운 때의 이유(promptReasons, 코치가 담는다)로 정한다: 진입로 2km 안에서 엔진 추천이
 * 다음 곳으로 넘어가도 "화장실 공백 전 마지막 기회"가 "가까운 화장실"로 바뀌지 않게 (UI 감사 #6).
 * 거리는 지금 값만 쓴다: 같은 곳의 엔진 추천(next) → 질문 장소의 지금 후보(promptStop). 둘 다 없으면 물은 때의 거리를
 * 지금 거리처럼 보이지 않고 위치("경로 위")만 둔다 (UI 감사 #1: 지나친 곳에 '3.9km'가 남았다).
 */
private fun promptCard(prompt: Prompt, state: CoachUiState, maxChips: Int, who: String?): StopCardModel {
    val stop = prompt.stop
    val next = state.next
    val reasons: List<Reason> = state.promptReasons.ifEmpty {
        when {
            stop == null -> emptyList()
            next != null && next.stop.id == stop.id -> next.reasons
            else -> state.urgent?.firstOrNull { it.stop.id == stop.id }?.why.orEmpty()
        }
    }
    val restroomKind = prompt.kind == PromptKind.LAST_CHANCE || prompt.kind == PromptKind.URGENT_RESULT
    val level = (if (restroomKind) state.restroom?.level else state.fatigue?.level) ?: Level.WATCH
    val codes = reasons.map { it.code }
    val context = when (prompt.kind) {
        PromptKind.LAST_CHANCE -> when {
            "LAST_CHANCE_CONGESTION" in codes -> "정체 전 마지막 화장실"
            "LAST_CHANCE_GAP" in codes || "LAST_CHANCE_PREFERENCE" in codes -> "화장실 공백 전 마지막 기회"
            "NEXT_NEAREST_RESTROOM" in codes -> "가까운 화장실"
            // 이유를 모르면 문장으로 고른다. 마지막 기회 질문은 정체 아니면 공백이다
            prompt.speech.contains("정체") -> "정체 전 마지막 화장실"
            prompt.speech.contains("여유가 거의 없어요") -> "가까운 화장실"
            else -> "화장실 공백 전 마지막 기회"
        }
        PromptKind.REST_SUGGESTION -> if ("NEXT_MEAL" in codes || prompt.speech.contains("식사")) "식사할 곳" else "쉬어 갈 곳"
        PromptKind.URGENT_RESULT -> "가장 빠른 화장실"
        // 상태 확인은 운전 패턴 편차 때문일 수도, 연속 운전 시간·졸음 시간대 때문일 수도 있다 (CoachTexts.checkIn)
        PromptKind.CHECK_IN -> if (prompt.speech.contains("패턴")) "운전 패턴 변화" else "졸음 확인"
        PromptKind.FRESH_AIR -> "환기 권유"
        PromptKind.INFO -> "안내"
    }
    val icon = when (prompt.kind) {
        PromptKind.FRESH_AIR -> ShimpyoIcons.Air
        PromptKind.CHECK_IN, PromptKind.REST_SUGGESTION -> if (context == "식사할 곳") ShimpyoIcons.Restaurant else ShimpyoIcons.Rest
        PromptKind.INFO -> ShimpyoIcons.Info
        else -> ShimpyoIcons.Wc
    }
    val chips = buildList {
        reasons.forEach { r -> Labels.shortReason(r, who)?.let(::add) }
        if (restroomKind) {
            Labels.companionChip(state.occupants.firstOrNull { it.id == state.restroom?.drivenBy })?.let(::add)
        } else if (prompt.kind != PromptKind.INFO) {
            state.fatigue?.reasons?.let(::fatigueReason)?.let(::add)
        }
    }.distinct().take(maxChips)
    if (stop == null) {
        return StopCardModel(context, shortQuestion(prompt), promptLead(prompt), chips, icon, askingLevel = level, sentence = true)
    }
    val live = next?.stop?.takeIf { it.id == stop.id } ?: state.promptStop?.takeIf { it.id == stop.id }
    val meta = if (live != null) Labels.stopMeta(live) else Labels.stopPosition(stop)
    return StopCardModel(context, stop.name, meta, chips, icon, askingLevel = level)
}

/** 경유지·길안내 요청이 실패했다: 넣었다고 말하지 않고 다시 시도를 연다 (UI 감사 #3) */
private fun failedGoToCard(r: GoToResult) = StopCardModel(
    context = "경유지를 넣지 못했어요",
    title = r.stop.name,
    meta = "다시 시도하거나 내비에서 넣어 주세요",
    chips = emptyList(),
    icon = ShimpyoIcons.Navigation,
    askingLevel = Level.WATCH,
)

/** 정차 중 다음 계획: 지나친 정차는 빼고 지금 거리·도착 시간으로 (UI 감사 #4) */
private fun planCard(state: CoachUiState, maxChips: Int): StopCardModel {
    val first = livePlan(state).first()
    val distance = Labels.distance(first.distanceM).let { if (first.estimated) "약 $it" else it }
    val meta = listOf(distance, "${Labels.minutesOf(first.etaS)} 뒤 도착", "${first.planned.dwellMin}분 정차").joinToString(" · ")
    val chips = Labels.sortedPurposes(first.planned.purposes).map(Labels::purposeName).take(maxChips)
    return StopCardModel("다음 계획", first.stop.name, meta, chips, ShimpyoIcons.Route, askingLevel = null)
}

/** 들르기·안내 요청 결과의 맥락 문구. FAILED는 [failedGoToCard]가 따로 그린다 */
fun goToContext(outcome: GoToOutcome): String = when (outcome) {
    GoToOutcome.WAYPOINT_ADDED -> "경유지로 넣었어요"
    GoToOutcome.ROUTE_STARTED -> "길안내를 시작했어요"
    GoToOutcome.ON_ROUTE -> "경로 위 · 진입로로 들어가세요"
    GoToOutcome.SEARCH_OPENED -> "내비에서 찾아 두었어요"
    GoToOutcome.FAILED -> "경유지를 넣지 못했어요"
}

private fun recommendationCard(next: Recommendation, state: CoachUiState, maxChips: Int, who: String?): StopCardModel {
    val code = next.reasons.firstOrNull()?.code
    val result = state.goToResult?.takeIf { it.stop.id == next.stop.id && it.outcome != GoToOutcome.FAILED }
    val context = when {
        result != null -> goToContext(result.outcome)
        // 엔진은 '들르기'를 받은 순간 기록한다. 내비에 실제로 들어갔는지는 goToResult가 알려 준다
        code == "NEXT_ACCEPTED" -> "들르기로 한 곳"
        code == "NEXT_REST" -> "쉬어 갈 곳"
        code == "NEXT_MEAL" -> "식사할 곳"
        code == "LAST_CHANCE_CONGESTION" -> "정체 전 마지막 화장실"
        code == "LAST_CHANCE_GAP" || code == "LAST_CHANCE_PREFERENCE" -> "화장실 공백 전 마지막 기회"
        else -> if (state.guiding) "다음 화장실" else "진행 방향 가까운 화장실"
    }
    val restroom = Purpose.RESTROOM in next.purposes || next.purposes.isEmpty()
    val drivenBy = state.occupants.firstOrNull { it.id == state.restroom?.drivenBy }
    val chips = buildList {
        next.reasons.forEach { r -> if (r.code != "NEXT_ACCEPTED" || result == null) Labels.shortReason(r, who)?.let(::add) }
        if (restroom) Labels.companionChip(drivenBy)?.let(::add)
        if (Purpose.REST in next.purposes) state.fatigue?.reasons?.let(::fatigueReason)?.let(::add)
    }.distinct().take(maxChips)
    val icon = when {
        code == "NEXT_MEAL" || (Purpose.MEAL in next.purposes && !restroom) -> ShimpyoIcons.Restaurant
        code == "NEXT_REST" -> ShimpyoIcons.Rest
        else -> ShimpyoIcons.Wc
    }
    return StopCardModel(context, next.stop.name, Labels.stopMeta(next.stop), chips, icon, askingLevel = null)
}

/** 엔진 추천이 없을 때 경로 앞 첫 화장실 (마지막 기회를 지난 정체 속에서 '고르는 중'에 멈춰 보이지 않게) */
private fun nearestCard(a: StopCandidate, state: CoachUiState): StopCardModel {
    val afterJam = a.routeDistanceM?.let { d -> state.congestion.any { it.startDistanceM in 0.0..d } } == true
    return StopCardModel(
        context = if (state.guiding) "다음 화장실" else "진행 방향 가까운 화장실",
        title = a.name,
        meta = Labels.stopMeta(a),
        chips = if (afterJam) listOf("정체 뒤") else emptyList(),
        icon = ShimpyoIcons.Wc,
        askingLevel = null,
    )
}

/**
 * 앞쪽 후보가 없을 때. 후보 목록 상태로 이유를 나눈다: 데이터를 읽는 중이거나 위치를 모를 때 "화장실 정보가 없어요"라고
 * 하면 틀린 말이다 (UI 감사 #18, screens.md 1.8)
 */
private fun emptyCard(state: CoachUiState): StopCardModel = StopCardModel(
    context = if (state.guiding) "다음 화장실" else "진행 방향 가까운 화장실",
    title = when (state.candidatesState) {
        CandidatesState.LOADING_DATA -> "정차 데이터를 읽는 중이에요"
        CandidatesState.NO_LOCATION -> "위치를 확인하는 중이에요"
        CandidatesState.READY -> "앞쪽에 화장실 정보가 없어요"
    },
    meta = "급할 때는 아래 버튼을 누르세요",
    chips = emptyList(),
    icon = ShimpyoIcons.Wc,
    askingLevel = null,
    sentence = true,
    muted = true,
)

/** 행동 줄(높이 100). 주행 중에도 터치 요소는 마이크·왼쪽·오른쪽·급해 4개를 넘지 않는다 */
@Immutable
sealed interface DriveActions {
    /** 질문: [accept]가 null이면 [확인] 하나 */
    data class Answer(val prompt: Prompt, val accept: String?) : DriveActions
    /** 경유지 실패: [다시 시도] [닫기] */
    data class RetryGoTo(val stop: StopCandidate) : DriveActions
    /** 정차 중: [계획 보기] [탑승자] */
    data object Parked : DriveActions
    /** 주행 중 빈 자리 (레이아웃이 움직이지 않게 높이는 남긴다) */
    data object None : DriveActions
}

fun driveActions(state: CoachUiState, driving: Boolean): DriveActions {
    val prompt = state.prompt
    val failed = state.goToResult?.takeIf { it.outcome == GoToOutcome.FAILED }
    return when {
        // 서비스가 멈췄으면 받을 사람이 없는 버튼을 두지 않는다
        state.serviceRunning && prompt != null -> DriveActions.Answer(prompt, acceptLabel(prompt, state.cabinSupported))
        state.serviceRunning && failed != null -> DriveActions.RetryGoTo(failed.stop)
        !driving -> DriveActions.Parked
        else -> DriveActions.None
    }
}
