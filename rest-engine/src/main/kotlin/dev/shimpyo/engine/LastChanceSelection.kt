package dev.shimpyo.engine

import kotlin.math.roundToInt

internal data class LastChanceOption(val chance: GapAnalyzer.LastChance, val purpose: Purpose, val level: Level, val reasons: List<Reason>)
internal data class LastChanceSelection(
    val opp: Opportunity, val purposes: Set<Purpose>, val reasons: List<Reason>, val level: Level,
    val speech: String, val groupDriven: Boolean,
)

/** 질문과 다음 카드가 함께 쓰는 순수 선택. NOW 화장실이 있으면 그곳, 아니면 가장 가까운 마지막 기회. 같은 id만 합친다. */
internal fun selectLastChance(options: List<LastChanceOption>, texts: CoachTexts, energy: EnergyKind?): LastChanceSelection? {
    val first = options.firstOrNull { it.purpose == Purpose.RESTROOM && it.level == Level.NOW }
        ?: options.minByOrNull { it.chance.opp.distanceM } ?: return null
    val same = options.filter { it.chance.opp.stop.id == first.chance.opp.stop.id }
    val purposes = same.map { it.purpose }.toSet()
    val human = same.firstOrNull { it.purpose == Purpose.RESTROOM } ?: same.firstOrNull { it.purpose == Purpose.REST }
    val lc = human?.chance ?: first.chance
    val name = lc.opp.stop.name
    val jam = lc.trigger == GapAnalyzer.Trigger.CONGESTION
    val minutes = ((if (jam) lc.congestionS else lc.gapS) / 60).roundToInt()
    val speech = when {
        Purpose.FUEL in purposes -> texts.energyLastChance(name, purposes, energy, same.any { it.chance.rangeTight })
        Purpose.RESTROOM in purposes && Purpose.REST in purposes ->
            if (jam) texts.lastChanceBothCongestion(name, minutes) else texts.lastChanceBothGap(name, minutes)
        Purpose.RESTROOM in purposes -> if (jam) texts.lastChanceCongestion(name, minutes) else texts.lastChanceGap(name, minutes)
        else -> if (jam) texts.fatigueLastChanceCongestion(name, minutes) else texts.fatigueLastChanceGap(name, minutes)
    }
    return LastChanceSelection(first.chance.opp, purposes, same.flatMap { it.reasons }.distinctBy { it.code },
        // 화장실·피로만 있을 때의 기존 단계(화장실 우선)를 보존한다. RANGE는 계속 SOON이라 잔량 감소로 재질문하지 않는다.
        maxOf(human?.level ?: first.level, same.firstOrNull { it.purpose == Purpose.FUEL }?.level ?: Level.OK), speech, same.any { it.chance.groupDriven })
}

internal fun CoachConfig.energyReserveKm(energy: EnergyKind?): Double =
    if (energy == EnergyKind.ELECTRIC) electricReserveKm else fuelReserveKm
