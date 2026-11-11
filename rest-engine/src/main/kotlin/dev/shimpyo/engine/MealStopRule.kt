package dev.shimpyo.engine

import dev.shimpyo.engine.EngineMath.NONE
import kotlin.math.abs

/**
 * 지금 정차가 식사 정차인지, 그렇다면 어떤 배수를 걸지 정한다. docs/research/need-covariates.md 2장·8장.
 *
 * 식사 정차 = 기어 P가 [CoachConfig.mealStopMinMin]분 이상([StopTracker]가 알린다)이고 다음 중 하나:
 * - 선 곳에 음식이 있다: [CoachConfig.mealPlaceRadiusM] 안 가장 가까운 후보의 Facilities.food
 * - 수락한 정차의 목적에 식사가 있었고, 그곳에 섰다
 * - "배고파" 요청이 살아 있을 때 섰다
 * 먹고 마신 양을 아는 것이 아니다. 정차 길이와 장소로 '식사했을 것'을 추정할 뿐이다 (NC §9-9).
 * 배수는 [CoachConfig.mealIntervalFactor], 정차를 [CoachConfig.mealEveningStartHour]시 이후에 시작했으면
 * [CoachConfig.mealIntervalFactorEvening]. 그 배수를 누구의 어느 구간에 거는지는 [RestroomNeedModel.onMealStop]이 정한다.
 *
 * 선 곳 알기: 엔진은 차 위치를 모르고 후보의 거리만 받는다. 그런데 그 목록은 '진행 방향 앞쪽'이라, 길안내 중에는 휴게소
 * 진입로를 지나는 순간 그 휴게소가 목록에서 빠진다 (수락한 정차가 도착하면 사라지는 것과 같다, CoachEngine.trackAccepted).
 * 그래서 마지막으로 반경 안에서 본 후보를 기억하고, 그 뒤 [ARRIVAL_MAX_TRAVEL_M] 넘게 달리지 않고 섰으면 그곳에 선 것으로 본다.
 * 서 있는 동안 반경 안에 후보가 보이면(앱이 위치 기준 거리로 넣은 경우, 시연) 그것을 쓴다.
 */
internal class MealStopRule(private val config: CoachConfig, private val local: LocalClock) {
    private var session = NONE
    private var intent = false

    /** 이번 정차에 걸 식사 배수. 식사 정차가 아니거나 아직 모르면 1.0 */
    var factor: Double = 1.0
        private set

    // 마지막으로 반경 안에서 본 가장 가까운 후보
    private var placeKnown = false
    private var placeFood = false
    private var movedSinceM = 0.0

    /**
     * 매 update에 한 번, 정차 판정([StopTracker.update]) 바로 뒤에 부른다.
     * @param sessionStart 지금(또는 마지막) 정차가 시작된 엔진 시각
     * @param parkedForMeal 지금 서 있고, 식사하려는 정차로 볼 근거가 있는지 (수락한 식사 정차에 섰다, "배고파"가 살아 있다)
     */
    fun observe(
        sessionStart: Long,
        parked: Boolean,
        parkedForMeal: Boolean,
        candidates: List<StopCandidate>,
        speedKph: Float?,
        dtMs: Long,
    ) {
        if (sessionStart != session) {
            session = sessionStart
            intent = false
            factor = 1.0
        }
        if (parkedForMeal) intent = true

        var best: StopCandidate? = null
        var bestD = Double.POSITIVE_INFINITY
        for (i in candidates.indices) {
            val c = candidates[i]
            val d = proximityM(c)
            if (!(d <= config.mealPlaceRadiusM)) continue
            if (best == null || d < bestD) { // 같은 거리면 목록 앞쪽 (결정적)
                best = c
                bestD = d
            }
        }
        if (best != null) {
            placeKnown = true
            placeFood = best.facilities.food
            movedSinceM = 0.0
        } else if (placeKnown && !parked && dtMs > 0L) {
            // 차속을 모르면 고속으로 달린 것으로 본다 (기억을 일찍 버리는 쪽)
            val kph = speedKph?.toDouble() ?: config.highwayAvgSpeedKph
            movedSinceM += kph / 3.6 * dtMs / 1000.0
            if (movedSinceM > ARRIVAL_MAX_TRAVEL_M) placeKnown = false
        }
    }

    /**
     * 정차가 식사 길이를 넘겼다: 이번 정차의 배수를 정한다.
     * @param sessionStartWall 정차를 시작한 벽시계 시각 (저녁 배수 판단)
     */
    fun onMealLength(sessionStartWall: Long) {
        val meal = intent || (placeKnown && placeFood)
        factor = when {
            !meal -> 1.0
            local.hourOfDay(sessionStartWall) >= config.mealEveningStartHour -> RestroomNeedModel.saneFactor(config.mealIntervalFactorEvening)
            else -> RestroomNeedModel.saneFactor(config.mealIntervalFactor)
        }
    }

    /** 후보까지 아는 거리 중 짧은 쪽 (m). 경로 거리는 지나친 쪽(음수)도 가까우면 그곳이다. 모르면 무한대 */
    private fun proximityM(c: StopCandidate): Double {
        val route = c.routeDistanceM?.let { abs(it) } ?: Double.POSITIVE_INFINITY
        val straight = c.straightDistanceM ?: Double.POSITIVE_INFINITY
        return minOf(route, straight)
    }

    companion object {
        /**
         * 후보를 마지막으로 반경 안에서 본 뒤 이만큼 넘게 달렸으면 그곳에 선 것이 아니다 (m). 근거: 엔진 v0 (설계값).
         * 진입로 분기점에서 주차 칸까지 가는 길(감속 차로 + 주차장)을 넉넉히 덮고, 본선을 시속 100km로 지나치면 72초 만에 넘는다
         */
        const val ARRIVAL_MAX_TRAVEL_M = 2_000.0
    }
}
