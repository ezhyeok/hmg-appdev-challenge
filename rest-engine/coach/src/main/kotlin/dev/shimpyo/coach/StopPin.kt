package dev.shimpyo.coach

import dev.shimpyo.engine.StopCandidate

/**
 * 들르기로 한 곳을 엔진 후보에 남긴다 (고정). 엔진은 들르기로 한 곳(수락한 질문의 장소, CoachEngine.noteAccepted)이 후보 목록에서
 * 1분 넘게 빠지면 잊으므로 앱이 그곳을 같은 id로 계속 넣어 줘야 한다. 경로 후보만으로는 지켜지지 않는다:
 * - 급해 목록에서 고른 곳은 대개 경로 후보가 아니다 (UI 감사 #9).
 * - 길안내 없이 달리다 고른 곳은 새 길안내의 목적지가 된다. 그 뒤 후보는 새 경로의 TBT(가는 길의 휴게소)로 만드는데 목적지는
 *   거기에 없다: 내비는 그곳으로 가는데 엔진은 1분 뒤 그곳을 잊고 다른 정차를 물을 수 있었다.
 * - 경로가 바뀌면 같은 시설의 후보 id가 바뀔 수 있다 (데이터 행 ↔ `tbt:` 자리 표시).
 * 그래서 성공한 들르기는 급해 목록이든 계획 화면이든 질문 수락이든 모두 고정한다. 같은 id의 경로 후보가 있는 동안은 그것이 쓰이고
 * (거리를 더 잘 안다, CoreRules.withExtras) 고정한 것은 그 후보가 없을 때만 지금 위치 기준 직선거리로 들어간다.
 *
 * 들르기를 시작할 때부터 넣고(내비 확인 20초 동안 급해 화면을 닫아도 남는다, UI 닫힘 검증 9c) 실패하면 뺀다. 성공한 뒤에는
 * - 지나쳤으면 푼다: 직선거리가 가장 가까웠던 때보다 [PASSED_M] 넘게 멀어짐, 또는 [ARRIVED_M] 안까지 갔다가 벗어남. 예전에는 엔진이
 *   추천을 멈출 때만 풀었는데, 고정이 후보를 대 주므로 엔진은 지나친 곳을 90분 동안 추천했다 (리뷰 R-F1).
 * - 엔진이 [GRACE_MS] 넘게 추천하지 않으면 푼다 (도착해 섰다, 엔진이 내렸다, 다른 곳을 들르기로 했다). 엔진이 다른 곳을 묻느라
 *   추천이 그 질문의 장소를 따라가는 동안(질문 수명 45초까지)은 세지 않는다: 그때 풀면 질문이 대답 없이 끝난 뒤 엔진이 그곳을 잊는다.
 * 좌표 없는 경로 후보(`tbt:`)는 고정하지 않는다: 거리를 잴 수 없고, 경로의 TBT가 그 id로 계속 준다.
 *
 * 순수 상태라 코어 없이 StopPinTest로 지킨다. 코어 스레드에서만 부른다. 시각은 경과 시간이다.
 */
class StopPin {
    private class Pin(val stop: StopCandidate) {
        /** 엔진이 마지막으로 이곳을 추천한 때 */
        var seenAtMs = 0L
        /** 차가 가장 가까웠던 직선거리 (m) */
        var minDistM = Double.POSITIVE_INFINITY
    }

    /** 내비에 넣는 중인 곳. 새로 고른 곳이 실패하면 앞서 들르기로 한 곳([pin])은 그대로다 */
    private var picking: Pin? = null
    /** 내비에 넣은 곳 */
    private var pin: Pin? = null

    /** 들르기로 한 곳 (내비에 넣은 뒤) */
    val pinned: StopCandidate? get() = pin?.stop

    /** 들르기를 시작했다 (PromptEffect.StartGoTo): 결과를 기다리는 동안에도 후보에 넣는다 */
    fun onGoToStarted(stop: StopCandidate) {
        picking = if (pinnable(stop)) Pin(bare(stop)) else null
    }

    /** 들르기가 끝났다 ([ok]: 내비 명령 성공). 성공했으면 그곳이 들르기로 한 곳이다 */
    fun onGoToFinished(stop: StopCandidate, ok: Boolean, nowMs: Long) {
        val p = picking?.takeIf { it.stop.id == stop.id } ?: return
        picking = null
        if (ok) {
            p.seenAtMs = nowMs
            pin = p
        }
    }

    /** 재시작 전에 들르기로 한 곳을 이어받았다 (TripSession.restoredAccepted가 후보에 보였을 때) */
    fun adopt(stop: StopCandidate, nowMs: Long) {
        if (pinnable(stop)) pin = Pin(bare(stop)).also { it.seenAtMs = nowMs }
    }

    /** 엔진 후보에 더할 것: 들르기로 한 곳과 넣는 중인 곳. 같은 id의 경로 후보가 있으면 그것이 쓰인다 (CoreRules.withExtras) */
    fun extras(): List<StopCandidate> {
        val a = pin?.stop
        val b = picking?.stop
        return when {
            a == null && b == null -> emptyList()
            a != null && b != null && a.id == b.id -> listOf(a)
            else -> listOfNotNull(a, b)
        }
    }

    /**
     * 틱마다 차 위치([pos], 모르면 null)와 엔진의 추천([nextStopId])을 본다. 들르기로 한 곳을 지나쳤거나 엔진이 더는 추천하지 않으면
     * 고정을 푼다: 그러면 후보에서 빠지고 엔진이 그곳을 잊어 보통 추천으로 돌아간다.
     * [askedStopId]: 엔진이 지금 묻고 있는 질문의 장소 (CoachOutput.prompt.stop). 추천이 그 장소인 동안은 유예를 세지 않는다
     */
    fun track(pos: Fix?, nextStopId: String?, askedStopId: String?, nowMs: Long) {
        // 넣는 중에도 가장 가까웠던 거리는 잰다 (내비 확인이 20초 걸리는 동안 500m를 간다)
        picking?.let { p -> if (pos != null) p.minDistM = minOf(p.minDistM, distanceM(pos, p.stop)) }
        val p = pin ?: return
        if (pos != null) {
            val dist = distanceM(pos, p.stop)
            if (dist < p.minDistM) p.minDistM = dist
            if (dist > p.minDistM + PASSED_M || (p.minDistM <= ARRIVED_M && dist > ARRIVED_M)) {
                pin = null
                return
            }
        }
        val followsQuestion = nextStopId != null && nextStopId == askedStopId
        if (nextStopId == p.stop.id || followsQuestion) p.seenAtMs = nowMs else if (nowMs - p.seenAtMs > GRACE_MS) pin = null
    }

    /** 세계(시연·실제)가 바뀌었다: 지난 세계에서 들르기로 한 곳이다 */
    fun reset() {
        picking = null
        pin = null
    }

    private fun distanceM(pos: Fix, stop: StopCandidate) = Geo.distanceM(pos.lat, pos.lon, stop.lat, stop.lon)

    /** 좌표가 그 시설의 것인지 (경로 위 자리 표시 후보의 좌표는 만든 때의 차 위치다) */
    private fun pinnable(stop: StopCandidate) = !stop.id.startsWith(GoToPlanner.TBT_PREFIX) && stop.lat.isFinite() && stop.lon.isFinite()

    /** 고를 때의 경로 거리·도착 시간은 그때 경로의 값이다: 나중에 지금 값처럼 엔진에 넣지 않는다 (직선거리는 넣을 때 다시 잰다) */
    private fun bare(stop: StopCandidate) = stop.copy(routeDistanceM = null, etaS = null)

    companion object {
        /** 고정한 곳을 엔진이 추천하지 않은 채로 두는 시간 (엔진이 다른 곳을 묻는 동안은 빼고) */
        const val GRACE_MS = 15_000L
        /** 가장 가까웠던 때보다 이만큼 넘게 멀어지면 지나친 것이다 (m) */
        const val PASSED_M = 300.0
        /** 이 안까지 갔으면 도착했던 것이다. 다시 벗어나면 떠난 것이다 (m) */
        const val ARRIVED_M = 150.0
    }
}
