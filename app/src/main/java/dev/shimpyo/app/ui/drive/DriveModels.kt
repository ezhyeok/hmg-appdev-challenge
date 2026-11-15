package dev.shimpyo.app.ui.drive

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.shimpyo.app.ui.Labels
import dev.shimpyo.app.ui.beyondDestination
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.displayJams
import dev.shimpyo.app.ui.limitChars
import dev.shimpyo.app.ui.livePlan
import dev.shimpyo.app.ui.plannedDistanceText
import dev.shimpyo.app.ui.theme.DriveLimits
import dev.shimpyo.coach.CandidatesState
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.GoToFailure
import dev.shimpyo.coach.GoToOutcome
import dev.shimpyo.coach.GoToResult
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.EnergyKind
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

@Immutable
data class EnergyReadout(val value: String, val unit: String, val label: String)

/** 차가 보내 준 실제 남은 거리. 이유 칩의 '예비 거리 제외' 숫자와 구분하며 모르면 숨긴다. */
fun energyReadout(state: CoachUiState): EnergyReadout? {
    val km = state.rangeKm?.takeIf { state.serviceRunning && it.isFinite() && it >= 0.0 } ?: return null
    val label = when (state.energy) {
        EnergyKind.ELECTRIC -> "충전 주행가능거리"
        EnergyKind.FUEL -> "주유 주행가능거리"
        null -> "차 주행가능거리"
    }
    return EnergyReadout(km.toInt().toString(), "km", label)
}

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

/**
 * 화장실 이유 한 줄: 직접 요청 > 정차 간 상한(숫자를 정한 이유) > 자는 중 > 식사 뒤 > 누구 기준 > 개인 기록 > 설정 필요.
 * '누구 기준'은 늘 붙는 기본 이유라, 숫자가 평소와 다른 이유(잠든 아이는 여유를 더, 식사 뒤에는 조금 일찍)를 먼저 보인다
 */
fun restroomReason(reasons: List<Reason>, who: String?): String? {
    val order = listOf(
        "RESTROOM_VOICED", "RESTROOM_CAP", "RESTROOM_ASLEEP", "RESTROOM_AFTER_MEAL", "RESTROOM_ELAPSED", "RESTROOM_PERSONAL", "RESTROOM_NONE",
    )
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

/**
 * 경로 앞에서 가장 가까운 화장실 (경로 거리, 없으면 직선거리 기준). 길안내 중이면 목적지까지만, 유턴이 필요한 곳은 뺀다
 * ('진행 방향 가까운 화장실 … · 유턴 필요'처럼 맥락과 어긋났다, 재검토 #13)
 */
internal fun nearestAhead(state: CoachUiState) = state.ahead
    .filter { s ->
        val d = s.routeDistanceM ?: s.straightDistanceM ?: -1.0
        s.facilities.restroom && d >= 0.0 && s.directionOk != false && !(s.routeDistanceM != null && beyondDestination(state, d))
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
    PromptKind.DEPARTURE -> "다녀올게요"
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
        PromptKind.DEPARTURE -> "출발 전에 화장실 다녀오세요"
    }
}

/** 질문 앞의 설명 문장 (예: "1시간 35분째 운전 중이에요"). 카드 둘째 줄에 넣을 만큼 짧을 때만 */
fun promptLead(p: Prompt): String? {
    val sentences = splitSentences(p.speech)
    if (sentences.size < 2) return null
    // 출발 전 한마디: 도착까지 걸리는 시간은 칩(DEPARTURE_TRIP)이 말한다. 설명 줄은 왜 묻는지("7살 아이는 44분쯤 뒤 …")를 보인다.
    // 서 있을 때만 뜨는 카드라 주행 중 글자 수 제한보다 길어도 된다
    if (p.kind == PromptKind.DEPARTURE) {
        sentences.getOrNull(1)?.removeSuffix(".")?.takeIf { it.length <= DEPARTURE_LEAD_CHARS }?.let { return it }
    }
    return sentences.first().removeSuffix(".").takeIf { it.length <= LEAD_CHARS && !it.endsWith("?") }
}

/** 문장 끝(. ? !) 뒤 공백에서 나눈다. "3.5km"처럼 숫자 안의 점에서는 나누지 않는다 */
private fun splitSentences(text: String): List<String> =
    text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }

private val SENTENCE_END = Regex("(?<=[.?!])\\s+")
private const val SHORT_QUESTION_CHARS = 30
private const val LEAD_CHARS = 24
private const val DEPARTURE_LEAD_CHARS = 34

/**
 * 추천 카드. 우선순위: 서비스 멈춤 > 질문 > 내비에 넣는 중 > 경유지 실패 > (정차 중) 다음 계획 > 엔진 추천 > 경로 앞 첫 화장실 > 빈 상태.
 * 칩은 주행 중 [DriveLimits.MAX_REASON_CHIPS]개, 정차 중 3개까지. 문구는 주행 제한의 글자 수([maxChars])로 자른다.
 */
fun stopCard(state: CoachUiState, driving: Boolean, maxChars: Int = DriveLimits.MAX_STRING_LENGTH): StopCardModel {
    val maxChips = if (driving) DriveLimits.MAX_REASON_CHIPS else 3
    val drivenBy = state.occupants.firstOrNull { it.id == state.restroom?.drivenBy }
    val who = drivenBy?.let { Labels.publicName(it) }
    val prompt = state.prompt
    val pending = state.goToPending
    val failed = failedGoTo(state)
    val card = when {
        !state.serviceRunning -> serviceCard(state)
        prompt != null -> promptCard(prompt, state, maxChips, who)
        pending != null -> pendingGoToCard(pending, state)
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
 * 이유가 비었으면(띄울 때 추천이 다른 곳이었다) 지금 추천(next)의 이유로 채우지 않는다: 1초 뒤 추천이 넘어오면 질문 도중에
 * 맥락 줄과 칩이 바뀌었다(닫힘 검증 UI-6). 급해 질문만 결과 목록의 이유(영업·방향)를 칩으로 쓴다.
 * 거리는 지금 값만 쓴다: 같은 곳의 엔진 추천(next) → 질문 장소의 지금 후보(promptStop). 둘 다 없으면 물은 때의 거리를
 * 지금 거리처럼 보이지 않고 위치("경로 위")만 둔다 (UI 감사 #1: 지나친 곳에 '3.9km'가 남았다).
 * 시간은 주행 시간(etaS)이되, 급해 질문은 급해 목록의 합친 시간(주행 + 주차·걷기 + 유턴)을 쓴다: 문장과 목록이 말하는 시간이다
 * (엔진 닫힘 검증 E1).
 */
private fun promptCard(prompt: Prompt, state: CoachUiState, maxChips: Int, who: String?): StopCardModel {
    val stop = prompt.stop
    val next = state.next
    val reasons: List<Reason> = state.promptReasons.ifEmpty {
        if (stop != null && prompt.kind == PromptKind.URGENT_RESULT) state.urgent?.firstOrNull { it.stop.id == stop.id }?.why.orEmpty() else emptyList()
    }
    val codes = reasons.map { it.code }
    val purposes = next?.takeIf { it.stop.id == stop?.id }?.purposes.orEmpty()
    val energyContext = energyContext(codes, prompt.speech, state.energy, purposes)
    val energyOnly = hasRangeLastChance(codes, prompt.speech) && !hasRestroomLastChance(codes, prompt.speech) &&
        !hasFatigueLastChance(codes, prompt.speech) && Purpose.RESTROOM !in purposes && Purpose.REST !in purposes
    // 졸음만의 마지막 기회 (엔진 FatigueLastChanceTest): 화장실이 아니라 쉼터를 묻는다. 단계·아이콘·칩이 피로의 것이다
    val fatigueOnly = prompt.kind == PromptKind.LAST_CHANCE && fatigueLastChanceOnly(codes, prompt.speech)
    val restroomKind = !fatigueOnly && !energyOnly &&
        (prompt.kind == PromptKind.LAST_CHANCE || prompt.kind == PromptKind.URGENT_RESULT || prompt.kind == PromptKind.DEPARTURE)
    val level = if (energyOnly) Level.SOON else (if (restroomKind) state.restroom?.level else state.fatigue?.level) ?: Level.WATCH
    val context = energyContext ?: when (prompt.kind) {
        PromptKind.LAST_CHANCE -> restroomQuestionContext(codes, prompt.speech)
        PromptKind.REST_SUGGESTION -> if ("NEXT_MEAL" in codes || prompt.speech.contains("식사")) "식사할 곳" else "쉬어 갈 곳"
        PromptKind.URGENT_RESULT -> "가장 빠른 화장실"
        // 상태 확인은 운전 패턴 편차 때문일 수도, 연속 운전 시간·졸음 시간대 때문일 수도 있다 (CoachTexts.checkIn)
        PromptKind.CHECK_IN -> if (prompt.speech.contains("패턴")) "운전 패턴 변화" else "졸음 확인"
        PromptKind.FRESH_AIR -> "환기 권유"
        PromptKind.INFO -> "안내"
        // 서 있고 목적지가 있을 때 (일상 주행, CoachEngine.departureCheck)
        PromptKind.DEPARTURE -> "출발 전 화장실"
    }
    val icon = when (prompt.kind) {
        PromptKind.FRESH_AIR -> ShimpyoIcons.Air
        PromptKind.CHECK_IN, PromptKind.REST_SUGGESTION -> if (context == "식사할 곳") ShimpyoIcons.Restaurant else ShimpyoIcons.Rest
        PromptKind.LAST_CHANCE -> if (energyOnly) ShimpyoIcons.GasStation else if (fatigueOnly) ShimpyoIcons.Rest else ShimpyoIcons.Wc
        PromptKind.INFO -> ShimpyoIcons.Info
        else -> ShimpyoIcons.Wc
    }
    val chips = buildList {
        // 맥락 줄이 이미 말한 이유(요청하신 화장실 등)는 칩으로 되풀이하지 않는다
        energyFirst(reasons).forEach { r -> if (r.code !in CONTEXT_LINE_CODES) Labels.shortReason(r, who)?.takeIf { it !in context }?.let(::add) }
        if (restroomKind) {
            Labels.companionChip(state.occupants.firstOrNull { it.id == state.restroom?.drivenBy })?.let(::add)
        } else if (prompt.kind != PromptKind.INFO && !fatigueOnly && !energyOnly) {
            state.fatigue?.reasons?.let(::fatigueReason)?.let(::add)
        }
    }.distinct().take(maxChips)
    if (stop == null) {
        return StopCardModel(context, shortQuestion(prompt), promptLead(prompt), chips, icon, askingLevel = level, sentence = true)
    }
    val live = next?.stop?.takeIf { it.id == stop.id } ?: state.promptStop?.takeIf { it.id == stop.id }
    // 급해 질문의 문장("…까지 약 18분이에요")과 급해 목록은 주행 + 주차·걷기 + 유턴을 합친 시간을 말한다 (RankedStop.totalTimeS).
    // 카드가 주행 시간(etaS)을 보이면 "약 18분"이라고 묻는 카드에 17분이 찍힌다: 목록에 그곳이 있으면 같은 시간을 보인다
    val totalS = if (prompt.kind == PromptKind.URGENT_RESULT) state.urgent?.firstOrNull { it.stop.id == stop.id }?.totalTimeS else null
    val meta = when {
        live == null -> Labels.stopPosition(stop)
        totalS != null -> Labels.stopMeta(live.copy(etaS = totalS))
        else -> Labels.stopMeta(live)
    }
    return StopCardModel(context, stop.name, meta, chips, icon, askingLevel = level)
}

/** 맥락 줄 문구 */
private const val CONTEXT_JAM = "정체 전 마지막 화장실"
private const val CONTEXT_GAP = "화장실 공백 전 마지막 기회"
private const val CONTEXT_JAM_REST = "정체 전 마지막 쉼터"
private const val CONTEXT_GAP_REST = "쉼터 공백 전 마지막 기회"
private const val CONTEXT_JAM_BOTH = "정체 전 마지막 화장실·쉼터"
private const val CONTEXT_GAP_BOTH = "공백 전 마지막 화장실·쉼터"
private const val CONTEXT_RANGE = "마지막 기회"
private const val CONTEXT_ENERGY_BUNDLE = "함께"

private fun hasRangeLastChance(codes: List<String>, speech: String): Boolean =
    "RANGE_LAST_CHANCE" in codes || speech.contains("충전도 되는 마지막 곳이에요") || speech.contains("주유도 되는 마지막 곳이에요") ||
        speech.contains("충전할 수 있는 마지막 곳이에요") || speech.contains("주유할 수 있는 마지막 곳이에요")

/** 구조화된 이유 우선. 장소 이름의 '충전소'·'정체'를 이유로 읽지 않는다. 함께 들르기는 마지막 기회라고 부르지 않는다. */
private fun energyContext(codes: List<String>, speech: String, energy: EnergyKind?, purposes: Set<Purpose>): String? {
    val last = hasRangeLastChance(codes, speech)
    if (!last && "ENERGY_BUNDLE" !in codes) return null
    val human = buildList {
        if (Purpose.RESTROOM in purposes || hasRestroomLastChance(codes, speech) || "NEXT_NEAREST_RESTROOM" in codes ||
            "NEXT_REQUESTED_RESTROOM" in codes || "NEXT_NEARBY_RESTROOM" in codes) add("화장실")
        if (Purpose.REST in purposes || hasFatigueLastChance(codes, speech) || "NEXT_REST" in codes) add("휴식")
        add(if (energy == EnergyKind.ELECTRIC) "충전" else "주유")
    }.joinToString("·")
    return "$human ${if (last) CONTEXT_RANGE else CONTEXT_ENERGY_BUNDLE}"
}

/** 주행 중 칩 두 개 안에도 차량 이유가 남아야 한다. 나머지 이유의 순서는 보존한다. */
private fun energyFirst(reasons: List<Reason>): List<Reason> = reasons.sortedBy {
    if (it.code == "RANGE_LAST_CHANCE" || it.code == "ENERGY_BUNDLE") 0 else 1
}

/** 졸음의 마지막 기회 이유가 있는가 (엔진 CoachEngine.fatigueLastChanceReason). 이유가 비었으면 엔진 문장으로 본다 */
private fun hasFatigueLastChance(codes: List<String>, speech: String): Boolean =
    codes.any { it.startsWith("FATIGUE_LAST_CHANCE") } || speech.contains("쉴 곳이 없") || speech.contains("마지막 쉼터") ||
        speech.contains("화장실이자 쉼터") || speech.contains("휴식도 충전도 되는") || speech.contains("휴식도 주유도 되는")

private fun hasRestroomLastChance(codes: List<String>, speech: String): Boolean =
    codes.any { it.startsWith("LAST_CHANCE_") } || speech.contains("화장실이 없어요") || speech.contains("마지막 화장실") ||
        speech.contains("화장실도 쉴 곳도") || speech.contains("화장실도 충전도 되는") || speech.contains("화장실도 주유도 되는") ||
        speech.contains("화장실도 휴식도")

/** 졸음만의 마지막 기회 (화장실의 마지막 기회와 같은 곳이 아니다) */
internal fun fatigueLastChanceOnly(codes: List<String>, speech: String): Boolean =
    hasFatigueLastChance(codes, speech) && !hasRestroomLastChance(codes, speech)
private const val CONTEXT_NEAR = "가까운 화장실"
private const val CONTEXT_REQUESTED_RESTROOM = "요청하신 화장실"
private const val CONTEXT_REQUESTED_CARE = "요청하신 곳"

/**
 * 맥락 줄이 뜻을 말해 주는 이유 코드: 같은 말을 칩으로 되풀이하지 않는다. 그 밖에도 칩 문구가 맥락 줄에 이미 있으면 붙이지 않는다
 * (도심 가까운 화장실을 묻는 카드의 맥락 줄 "가까운 화장실", 길안내 없을 때 '다음 화장실' 카드의 "진행 방향 가까운 화장실")
 */
private val CONTEXT_LINE_CODES = setOf("NEXT_ACCEPTED", "NEXT_REQUESTED_RESTROOM", "NEXT_REQUESTED_CARE")

/**
 * 화장실 질문(PromptKind.LAST_CHANCE)의 맥락 줄. 엔진은 이 종류로 다섯 가지 문장을 낸다(CoachTexts): 정체 전 마지막,
 * 공백 전 마지막, 여유가 거의 없을 때 가까운 곳, "화장실 가고 싶대"의 답, 수유·반려견 요청의 답. 그래서 종류만 보고
 * '마지막 기회'라고 하면 틀린 말이 된다 (닫힘 검증 UI-N5: 이유가 비면 요청의 답에도 '화장실 공백 전 마지막 기회'라고 했다).
 *
 * 1. 띄운 때의 이유가 정체(LAST_CHANCE_CONGESTION)인지 공백(LAST_CHANCE_GAP·PREFERENCE)인지 말하면 그것으로.
 * 2. 없으면 엔진의 마지막 기회 문장인지 본다: 게이지가 NOW일 때 엔진은 "정체 전 마지막 화장실이에요"라고 물으면서 이유는
 *    NEXT_NEAREST_RESTROOM 하나만 줬다(UI-6). 말한 문장과 맥락 줄이 어긋나지 않게 문장이 요청·가까운 곳 코드보다 먼저다.
 * 3. 그 밖의 LAST_CHANCE_* 이유만 있으면(여럿이 타서 앞당긴 것 등) 마지막 기회.
 * 4. 요청에 답한 곳(NEXT_REQUESTED_*), 가까운 곳(NEXT_NEAREST_RESTROOM, 도심 일상 후보 NEXT_NEARBY_RESTROOM) 코드.
 * 5. 이유가 없으면 나머지 세 문장으로. 아는 문장이 아니면 '다음 화장실': 마지막 기회라고 하지 않는다.
 */
internal fun restroomQuestionContext(codes: List<String>, speech: String): String = when {
    // 졸음의 마지막 기회 (엔진 FatigueLastChanceTest): 쉼터만, 또는 화장실과 같은 곳이면 둘 다 말한다
    hasFatigueLastChance(codes, speech) -> {
        val jam = "LAST_CHANCE_CONGESTION" in codes || "FATIGUE_LAST_CHANCE_CONGESTION" in codes || speech.contains("정체가 있어요") || speech.contains("정체 전")
        val both = hasRestroomLastChance(codes, speech)
        when {
            both && jam -> CONTEXT_JAM_BOTH
            both -> CONTEXT_GAP_BOTH
            jam -> CONTEXT_JAM_REST
            else -> CONTEXT_GAP_REST
        }
    }
    "LAST_CHANCE_CONGESTION" in codes -> CONTEXT_JAM
    "LAST_CHANCE_GAP" in codes || "LAST_CHANCE_PREFERENCE" in codes -> CONTEXT_GAP
    // 장소 이름에 들어갈 수 있는 낱말("신정체육공원")이 아니라 엔진 문장의 구절로 가린다
    speech.contains("정체 전 마지막") || speech.contains("정체가 있어요") -> CONTEXT_JAM
    speech.contains("동안 화장실이 없어요") -> CONTEXT_GAP
    codes.any { it.startsWith("LAST_CHANCE_") } -> CONTEXT_GAP
    "NEXT_REQUESTED_RESTROOM" in codes -> CONTEXT_REQUESTED_RESTROOM
    "NEXT_REQUESTED_CARE" in codes -> CONTEXT_REQUESTED_CARE
    "NEXT_NEAREST_RESTROOM" in codes || "NEXT_NEARBY_RESTROOM" in codes -> CONTEXT_NEAR
    speech.contains("여유가 거의 없어요") -> CONTEXT_NEAR
    speech.startsWith("다음 화장실은") -> CONTEXT_REQUESTED_RESTROOM
    speech.contains("쉬어 갈 수 있어요") -> CONTEXT_REQUESTED_CARE
    else -> "다음 화장실"
}

/** 내비에 넣는 중일 때의 맥락 문구. 움직이는 표시 없이 글자로만 알린다 (주행 중 애니메이션 최소화) */
const val GOTO_PENDING_CONTEXT = "내비에 넣는 중…"

/**
 * 들르기를 시작해 내비의 확인을 기다리는 동안 (CoachUiState.goToPending). 에뮬레이터에서 경유지 추가 확인이 19.5초 걸렸다:
 * 그동안 '들르기로 한 곳'이나 지난 실패 카드가 아니라 진행을 보이고 [다시 시도]를 두지 않는다 (닫힘 검증 UI-N2).
 * 거리는 지금 값만: 같은 곳의 추천 → 앞쪽 후보. 없으면 위치만 둔다 (넣기 시작한 때의 거리를 지금 거리처럼 보이지 않는다)
 */
private fun pendingGoToCard(stop: StopCandidate, state: CoachUiState): StopCardModel {
    val live = state.next?.stop?.takeIf { it.id == stop.id } ?: state.ahead.firstOrNull { it.id == stop.id }
    return StopCardModel(
        context = GOTO_PENDING_CONTEXT,
        title = stop.name,
        meta = if (live != null) Labels.stopMeta(live) else Labels.stopPosition(stop),
        chips = emptyList(),
        icon = ShimpyoIcons.Navigation,
        askingLevel = null,
    )
}

/**
 * 다시 시도할 만한 경유지 실패. 실패한 곳을 이미 지나쳤으면(후보 목록이 준비됐는데 앞쪽 후보·추천·질문·급해 결과·계획
 * 어디에도 없으면) 두지 않는다: 지나친 곳을 경유지로 넣으라는 버튼이 남았다(재검토 #4). 목록을 모르면 지나쳤다고 단정하지 않는다
 */
fun failedGoTo(state: CoachUiState): GoToResult? {
    val r = state.goToResult?.takeIf { it.outcome == GoToOutcome.FAILED } ?: return null
    if (state.candidatesState != CandidatesState.READY) return r
    val id = r.stop.id
    val stillAhead = state.ahead.any { it.id == id } || state.next?.stop?.id == id || state.promptStop?.id == id ||
        state.urgent.orEmpty().any { it.stop.id == id } || livePlan(state).any { it.live && it.stop.id == id }
    return r.takeIf { stillAhead }
}

/**
 * 주행 화면 카드가 지금 [r]을 보이고 있는지 (그렇지 않으면 토스트로 알린다, 재검토 #10).
 * 결과가 온 들르기의 '넣는 중' 표시는 곧 내려간다(코치가 결과와 진행 표시를 한 번에 올리지 않을 수 있다): 그 표시가 내려간 뒤의
 * 카드로 본다. 그러지 않으면 같은 결과를 토스트와 카드가 잇달아 두 번 알린다
 */
fun cardShowsGoTo(state: CoachUiState, driving: Boolean, r: GoToResult): Boolean {
    val settled = if (state.goToPending?.id == r.stop.id) state.copy(goToPending = null) else state
    val card = stopCard(settled, driving)
    return card.title == r.stop.name && card.context == goToContext(r.outcome, r.failure)
}

/**
 * 경유지·길안내 요청이 실패했다: 넣었다고 말하지 않고 다시 시도를 연다 (UI 감사 #3). 까닭을 알면(경유지가 가득 참 등) 할 일을
 * 함께 보인다: 그 경우 다시 눌러도 또 실패한다 (2026-11-12 에뮬레이터, 내비는 경유지를 둘까지만 받는다)
 */
private fun failedGoToCard(r: GoToResult) = StopCardModel(
    context = goToContext(GoToOutcome.FAILED, r.failure),
    title = r.stop.name,
    meta = when (r.failure) {
        GoToFailure.WAYPOINTS_FULL -> "정차한 뒤 내비에서 경유지를 하나 지워 주세요"
        GoToFailure.AHEAD_OF_OTHER_WAYPOINT -> "정차한 뒤 내비에서 경유지를 정리해 주세요"
        null -> "다시 시도하거나 내비에서 넣어 주세요"
    },
    chips = emptyList(),
    icon = ShimpyoIcons.Navigation,
    askingLevel = Level.WATCH,
)

/** 정차 중 다음 계획: 지나친 정차는 빼고 지금 거리·도착 시간으로 (UI 감사 #4) */
private fun planCard(state: CoachUiState, maxChips: Int): StopCardModel {
    val first = livePlan(state).first()
    // 지금 후보가 없으면 계획 때 값임을 밝히고 도착 시간은 뺀다 (재검토 #5)
    val meta = listOfNotNull(
        plannedDistanceText(first),
        first.etaS?.let { "${Labels.minutesOf(it)} 뒤 도착" },
        "${first.planned.dwellMin}분 정차",
    ).joinToString(" · ")
    val chips = Labels.sortedPurposes(first.planned.purposes).map { Labels.purposeName(it, state.energy) }.take(maxChips)
    return StopCardModel("다음 계획", first.stop.name, meta, chips, ShimpyoIcons.Route, askingLevel = null)
}

/** 들르기·안내 요청 결과의 맥락 문구. FAILED는 [failedGoToCard]가 이 문구와 할 일로 그린다 */
fun goToContext(outcome: GoToOutcome, failure: GoToFailure? = null): String = when (outcome) {
    GoToOutcome.WAYPOINT_ADDED -> "경유지로 넣었어요"
    GoToOutcome.ROUTE_STARTED -> "길안내를 시작했어요"
    GoToOutcome.ON_ROUTE -> "경로 위 · 진입로로 들어가세요"
    GoToOutcome.SEARCH_OPENED -> "내비에서 찾아 두었어요"
    GoToOutcome.FAILED -> when (failure) {
        GoToFailure.WAYPOINTS_FULL -> "내비 경유지가 가득 찼어요"
        GoToFailure.AHEAD_OF_OTHER_WAYPOINT -> "경유지 순서를 맞추지 못했어요"
        null -> "경유지를 넣지 못했어요"
    }
}

private fun recommendationCard(next: Recommendation, state: CoachUiState, maxChips: Int, who: String?): StopCardModel {
    val codes = next.reasons.map { it.code }
    val code = codes.firstOrNull()
    val result = state.goToResult?.takeIf { it.stop.id == next.stop.id && it.outcome != GoToOutcome.FAILED }
    val energyContext = energyContext(codes, "", state.energy, next.purposes)
    val context = when {
        result != null -> goToContext(result.outcome)
        // 엔진은 '들르기'를 받은 순간 기록한다. 내비에 실제로 들어갔는지는 goToResult가 알려 준다
        code == "NEXT_ACCEPTED" -> "들르기로 한 곳"
        energyContext != null -> energyContext
        code == "NEXT_REST" -> "쉬어 갈 곳"
        code == "NEXT_MEAL" -> "식사할 곳"
        // 질문 카드와 같은 순서: 마지막 기회가 먼저, 그다음 요청에 답한 곳. 이유가 놓인 순서와 관계없이 본다
        codes.any { it.startsWith("FATIGUE_LAST_CHANCE") } -> restroomQuestionContext(codes, "")
        "LAST_CHANCE_CONGESTION" in codes -> CONTEXT_JAM
        codes.any { it.startsWith("LAST_CHANCE_") } -> CONTEXT_GAP
        "NEXT_REQUESTED_RESTROOM" in codes -> CONTEXT_REQUESTED_RESTROOM
        "NEXT_REQUESTED_CARE" in codes -> CONTEXT_REQUESTED_CARE
        else -> if (state.guiding) "다음 화장실" else "진행 방향 가까운 화장실"
    }
    val restroom = Purpose.RESTROOM in next.purposes || next.purposes.isEmpty()
    val drivenBy = state.occupants.firstOrNull { it.id == state.restroom?.drivenBy }
    val chips = buildList {
        // 들르기로 한 곳·요청하신 곳은 맥락 줄이 이미 말한다: 같은 말을 칩으로 되풀이하지 않는다
        energyFirst(next.reasons).forEach { r -> if (r.code !in CONTEXT_LINE_CODES) Labels.shortReason(r, who)?.takeIf { it !in context }?.let(::add) }
        if (restroom) Labels.companionChip(drivenBy)?.let(::add)
        if (Purpose.REST in next.purposes) state.fatigue?.reasons?.let(::fatigueReason)?.let(::add)
    }.distinct().take(maxChips)
    val icon = when {
        Purpose.FUEL in next.purposes && !restroom && Purpose.REST !in next.purposes -> ShimpyoIcons.GasStation
        code == "NEXT_MEAL" || (Purpose.MEAL in next.purposes && !restroom) -> ShimpyoIcons.Restaurant
        Purpose.FUEL in next.purposes && Purpose.REST in next.purposes && !restroom -> ShimpyoIcons.Rest
        code == "NEXT_REST" -> ShimpyoIcons.Rest
        else -> ShimpyoIcons.Wc
    }
    return StopCardModel(context, next.stop.name, Labels.stopMeta(next.stop), chips, icon, askingLevel = null)
}

/** 엔진 추천이 없을 때 경로 앞 첫 화장실 (마지막 기회를 지난 정체 속에서 '고르는 중'에 멈춰 보이지 않게) */
private fun nearestCard(a: StopCandidate, state: CoachUiState): StopCardModel {
    return StopCardModel(
        context = if (state.guiding) "다음 화장실" else "진행 방향 가까운 화장실",
        title = a.name,
        meta = Labels.stopMeta(a),
        chips = listOfNotNull(jamChip(a, state)),
        icon = ShimpyoIcons.Wc,
        askingLevel = null,
    )
}

/**
 * 그곳까지 가는 길에 정체가 있으면: 정체가 그 앞에서 끝나면 "정체 뒤", 그곳을 지나서도 이어지면 "정체 구간 안".
 * 차가 이미 정체 안(시작 거리 < 0)이어도 센다 (예전에는 앞에서 시작하는 정체만 봤다, 재검토 #17).
 * 화면에 정체로 보이는 것만 센다(displayJams): 2km 앞의 느린 200m 때문에 20km 앞 휴게소에 '정체 뒤'가 붙었다 (2026-11-12)
 */
private fun jamChip(a: StopCandidate, state: CoachUiState): String? {
    val d = a.routeDistanceM ?: return null
    val jams = displayJams(state).filter { it.startDistanceM < d }
    if (jams.isEmpty()) return null
    return if (jams.any { it.startDistanceM + it.lengthM > d }) "정체 구간 안" else "정체 뒤"
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

/**
 * CCP(로터리)·키 입력의 기본 포커스 (screens.md 1.7, UI 감사 #13). 질문이나 경유지 실패 중이면 왼쪽 버튼([primary]),
 * 아니면 급해 버튼. [key]가 바뀔 때마다 포커스를 다시 준다: 질문이 끝나 버튼이 사라지면 급해로 옮겨 포커스가 갈 곳을 잃지 않는다.
 */
@Immutable
data class DriveFocus(val key: String, val primary: Boolean)

fun driveFocus(actions: DriveActions): DriveFocus = when (actions) {
    is DriveActions.Answer -> DriveFocus("answer:${actions.prompt.id}", primary = true)
    is DriveActions.RetryGoTo -> DriveFocus("retry:${actions.stop.id}", primary = true)
    DriveActions.Parked -> DriveFocus("parked", primary = false)
    DriveActions.None -> DriveFocus("none", primary = false)
}

fun driveActions(state: CoachUiState, driving: Boolean): DriveActions {
    val prompt = state.prompt
    val failed = failedGoTo(state)
    return when {
        // 서비스가 멈췄으면 받을 사람이 없는 버튼을 두지 않는다
        state.serviceRunning && prompt != null -> DriveActions.Answer(prompt, acceptLabel(prompt, state.cabinSupported))
        // 내비에 넣는 중에는 [다시 시도]를 두지 않는다: 또 보내면 경유지가 둘 들어간다 (닫힘 검증 UI-N2)
        state.serviceRunning && failed != null && state.goToPending == null -> DriveActions.RetryGoTo(failed.stop)
        !driving -> DriveActions.Parked
        else -> DriveActions.None
    }
}
