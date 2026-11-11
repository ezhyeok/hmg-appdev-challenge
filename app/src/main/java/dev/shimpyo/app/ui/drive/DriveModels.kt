package dev.shimpyo.app.ui.drive

import androidx.compose.ui.graphics.vector.ImageVector
import dev.shimpyo.app.coach.CoachUiState
import dev.shimpyo.app.ui.Labels
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.app.ui.theme.DriveLimits
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.Recommendation

/*
 * 주행 화면이 그리는 값. CoachUiState → 게이지·추천 카드 내용으로 바꾸는 순수 함수라 화면 없이 시험할 수 있다.
 */

/** 게이지 카드 값 */
data class GaugeModel(val minutes: Int?, val level: Level?, val fraction: Float?, val tick: Float?, val reason: String?)

/** 화장실 게이지: 막대 = 남은 분 ÷ 게이지를 정한 탑승자의 기준 간격, 눈금 = SOON 경계(20분) */
fun restroomGauge(state: CoachUiState): GaugeModel {
    val g = state.restroom
    val who = state.occupants.firstOrNull { it.id == g?.drivenBy }
    val baseline = Labels.baselineMin(who).toFloat()
    val whoName = who?.let { Labels.publicName(it) }
    return GaugeModel(
        minutes = g?.minutes,
        level = g?.takeIf { it.minutes != null }?.level,
        fraction = g?.minutes?.let { (it / baseline).coerceIn(0f, 1f) },
        tick = (Labels.config.restroomSoonMin / baseline).coerceIn(0f, 1f),
        reason = g?.reasons?.firstNotNullOfOrNull { Labels.shortReason(it, whoName) }
            ?: whoName?.takeIf { g?.minutes != null }?.let { "$it 기준" },
    )
}

/** 피로 게이지: 막대 = 남은 분 ÷ 120분, 눈금 = SOON 경계(연속 100분 = 남은 20분) */
fun fatigueGauge(state: CoachUiState): GaugeModel {
    val g = state.fatigue
    val full = Labels.config.fatigueNowMin.toFloat()
    return GaugeModel(
        minutes = g?.minutes,
        level = g?.takeIf { it.minutes != null }?.level,
        fraction = g?.minutes?.let { (it / full).coerceIn(0f, 1f) },
        tick = ((full - Labels.config.fatigueSoonMin) / full).coerceIn(0f, 1f),
        reason = g?.reasons?.let(::fatigueReason) ?: if (g?.minutes != null && state.parked) "정차 중" else null,
    )
}

/** 피로 이유 한 줄: 직접 말한 것 > 운전 패턴 변화 > 환기 > 시간대 > 수면 > 연속 운전 */
fun fatigueReason(reasons: List<Reason>): String? {
    val order = listOf("FATIGUE_VOICED", "FATIGUE_STYLE", "FATIGUE_RECIRC", "FATIGUE_NIGHT", "FATIGUE_AFTERNOON", "FATIGUE_SHORT_SLEEP", "FATIGUE_DRIVING")
    return reasons.sortedBy { r -> order.indexOf(r.code).let { if (it < 0) order.size else it } }
        .firstNotNullOfOrNull { Labels.shortReason(it) }
}

/** 추천 카드 내용 */
data class StopCardModel(
    /** 맥락 한 줄 (예: "정체 전 마지막 화장실") */
    val context: String,
    /** 장소 이름, 또는 장소가 없는 질문·안내 문장 */
    val title: String,
    val meta: String?,
    val chips: List<String>,
    val icon: ImageVector,
    /** 질문 중이면 그 게이지 단계. 카드 테두리를 이 색 4dp로 바꾼다 */
    val askingLevel: Level?,
    /** title이 장소 이름이 아니라 문장이다 (여러 줄 허용) */
    val sentence: Boolean = false,
    /** 빈 상태 */
    val muted: Boolean = false,
)

/** 경로 앞에서 가장 가까운 화장실 (경로 거리, 없으면 직선거리 기준) */
internal fun nearestAhead(state: CoachUiState) = state.ahead
    .filter { it.facilities.restroom && (it.routeDistanceM ?: it.straightDistanceM ?: -1.0) >= 0.0 }
    .minByOrNull { it.routeDistanceM ?: it.straightDistanceM ?: Double.MAX_VALUE }

/** 질문에 대한 두 버튼의 글자. null이면 [확인] 하나만 둔다 */
fun acceptLabel(p: Prompt): String? = when (p.kind) {
    PromptKind.LAST_CHANCE, PromptKind.URGENT_RESULT -> "들르기"
    PromptKind.REST_SUGGESTION -> if (p.stop != null) "들르기" else "쉬어 갈게요"
    PromptKind.CHECK_IN -> "쉬어 갈게요"
    PromptKind.FRESH_AIR -> "외기로 전환"
    PromptKind.INFO -> null
}

/**
 * 추천 카드. 질문이 있으면 질문의 장소와 맥락, 없으면 엔진의 다음 추천, 정차 중이고 계획이 있으면 첫 정차.
 * 칩은 주행 중 [DriveLimits.MAX_REASON_CHIPS]개, 정차 중 3개까지.
 */
fun stopCard(state: CoachUiState, driving: Boolean): StopCardModel {
    val maxChips = if (driving) DriveLimits.MAX_REASON_CHIPS else 3
    val prompt = state.prompt
    val next = state.next
    val drivenBy = state.occupants.firstOrNull { it.id == state.restroom?.drivenBy }
    val who = drivenBy?.let { Labels.publicName(it) }

    if (prompt != null) {
        val stop = prompt.stop
        val reasons: List<Reason> = when {
            stop == null -> emptyList()
            next != null && next.stop.id == stop.id -> next.reasons
            else -> state.urgent?.firstOrNull { it.stop.id == stop.id }?.why.orEmpty()
        }
        val restroomKind = prompt.kind == PromptKind.LAST_CHANCE || prompt.kind == PromptKind.URGENT_RESULT
        val level = (if (restroomKind) state.restroom?.level else state.fatigue?.level) ?: Level.WATCH
        val context = when (prompt.kind) {
            PromptKind.LAST_CHANCE -> when (reasons.firstOrNull()?.code) {
                "LAST_CHANCE_CONGESTION" -> "정체 전 마지막 화장실"
                "LAST_CHANCE_GAP", "LAST_CHANCE_PREFERENCE" -> "화장실 공백 전 마지막 기회"
                else -> if (prompt.speech.contains("정체")) "정체 전 마지막 화장실" else "가까운 화장실"
            }
            PromptKind.REST_SUGGESTION -> if (prompt.speech.contains("식사")) "식사할 곳" else "쉬어 갈 곳"
            PromptKind.URGENT_RESULT -> "가장 빠른 화장실"
            PromptKind.CHECK_IN -> "운전 패턴 변화"
            PromptKind.FRESH_AIR -> "환기 권유"
            PromptKind.INFO -> "안내"
        }
        val icon = when (prompt.kind) {
            PromptKind.FRESH_AIR -> ShimpyoIcons.Air
            PromptKind.CHECK_IN, PromptKind.REST_SUGGESTION -> if (prompt.speech.contains("식사")) ShimpyoIcons.Restaurant else ShimpyoIcons.Rest
            PromptKind.INFO -> ShimpyoIcons.Info
            else -> ShimpyoIcons.Wc
        }
        val chips = buildList {
            reasons.forEach { r -> Labels.shortReason(r, who)?.let(::add) }
            if (restroomKind) {
                Labels.companionChip(drivenBy)?.let(::add)
            } else if (prompt.kind != PromptKind.INFO) {
                state.fatigue?.reasons?.let(::fatigueReason)?.let(::add)
            }
        }.distinct().take(maxChips)
        if (stop == null) {
            return StopCardModel(context, prompt.speech, null, chips, icon, askingLevel = level, sentence = true)
        }
        return StopCardModel(context, stop.name, Labels.stopMeta(stop), chips, icon, askingLevel = level)
    }

    // 정차 중이고 계획이 있으면 첫 정차를 보여 준다
    val firstPlanned = state.plan?.stops?.firstOrNull()
    if (!driving && state.parked && firstPlanned != null) {
        val meta = listOfNotNull(
            Labels.stopDistance(firstPlanned.stop),
            "${Labels.minutesOf(firstPlanned.arriveInS)} 뒤 도착",
            "${firstPlanned.dwellMin}분 정차",
        ).joinToString(" · ")
        val chips = Labels.sortedPurposes(firstPlanned.purposes).map(Labels::purposeName).take(maxChips)
        return StopCardModel("다음 계획", firstPlanned.stop.name, meta, chips, ShimpyoIcons.Route, askingLevel = null)
    }

    if (next != null) return recommendationCard(next, state, maxChips, who)

    // 엔진 추천이 없으면 경로 앞 첫 화장실을 보여 준다. 예전에는 '다음 화장실을 고르는 중이에요'가 남아서
    // 정체 속(마지막 기회를 지난 뒤)에 오래 머무르면 고장처럼 보였다(2026-11-11 에뮬레이터). DriveModelsTest가 지킨다
    nearestAhead(state)?.let { a ->
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

    return StopCardModel(
        context = if (state.guiding) "다음 화장실" else "진행 방향 가까운 화장실",
        title = when {
            !state.serviceRunning -> "준비하고 있어요"
            state.ahead.isEmpty() -> "앞쪽에 화장실 정보가 없어요"
            else -> "다음 화장실을 고르는 중이에요"
        },
        meta = if (!state.serviceRunning) "잠시 후 게이지와 추천이 나타나요" else "급할 때는 아래 버튼을 누르세요",
        chips = emptyList(),
        icon = ShimpyoIcons.Wc,
        askingLevel = null,
        sentence = true,
        muted = true,
    )
}

private fun recommendationCard(next: Recommendation, state: CoachUiState, maxChips: Int, who: String?): StopCardModel {
    val code = next.reasons.firstOrNull()?.code
    val context = when (code) {
        "NEXT_ACCEPTED" -> "경유지로 넣었어요"
        "NEXT_REST" -> "쉬어 갈 곳"
        "NEXT_MEAL" -> "식사할 곳"
        "LAST_CHANCE_CONGESTION" -> "정체 전 마지막 화장실"
        "LAST_CHANCE_GAP", "LAST_CHANCE_PREFERENCE" -> "화장실 공백 전 마지막 기회"
        else -> if (state.guiding) "다음 화장실" else "진행 방향 가까운 화장실"
    }
    val restroom = Purpose.RESTROOM in next.purposes || next.purposes.isEmpty()
    val drivenBy = state.occupants.firstOrNull { it.id == state.restroom?.drivenBy }
    val chips = buildList {
        next.reasons.forEach { r -> Labels.shortReason(r, who)?.let(::add) }
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
