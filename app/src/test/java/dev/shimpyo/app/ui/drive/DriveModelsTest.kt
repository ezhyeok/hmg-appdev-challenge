package dev.shimpyo.app.ui.drive

import dev.shimpyo.app.coach.CoachUiState
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Prompt
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.Recommendation
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 추천 카드의 빈 상태. 회귀: 2026-11-11 에뮬레이터에서 마지막 기회를 지나 정체에 들어가자 카드가
 * '다음 화장실을 고르는 중이에요'에 오래 머물러 고장처럼 보였다. 추천이 없으면 경로 앞 첫 화장실을 보여 준다.
 */
class DriveModelsTest {

    private fun stop(id: String, name: String, km: Double) = StopCandidate(
        id = id, name = name, kind = StopKind.HIGHWAY_REST_AREA, lat = 36.4, lon = 127.4,
        facilities = Facilities(), parkable = true, routeDistanceM = km * 1_000.0, directionOk = true,
    )

    @Test
    fun withoutRecommendationTheNearestRestroomAheadIsShown() {
        val state = CoachUiState(
            serviceRunning = true,
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
    fun questionCardShowsTheLiveDistanceNotTheOneAtAskTime() {
        val asked = stop("oksan", "옥산휴게소", 3.9)
        val prompt = Prompt("q1", PromptKind.LAST_CHANCE, "앞에 1시간 정체가 있어요. 들를까요?", asked, expiresAt = Long.MAX_VALUE)
        val now = stop("oksan", "옥산휴게소", 1.2)
        val state = CoachUiState(
            serviceRunning = true,
            guiding = true,
            prompt = prompt,
            next = Recommendation(now, setOf(Purpose.RESTROOM), listOf(Reason("LAST_CHANCE_CONGESTION", "정체 전 마지막"))),
        )
        val card = stopCard(state, driving = true)
        assertEquals("옥산휴게소", card.title)
        assertTrue(card.meta!!, card.meta!!.startsWith("1.2km"))

        // 엔진 추천이 다른 곳이면 질문 때 값을 쓴다 (20초마다만 바뀌는 경로 앞 목록의 더 오래된 값은 쓰지 않는다)
        val other = state.copy(
            next = Recommendation(stop("jukam", "죽암휴게소", 20.0), setOf(Purpose.RESTROOM), emptyList()),
            ahead = listOf(stop("oksan", "옥산휴게소", 6.0)),
        )
        assertTrue(stopCard(other, driving = true).meta!!.startsWith("3.9km"))
    }

    @Test
    fun withNothingAheadTheEmptyStateSaysSo() {
        val card = stopCard(CoachUiState(serviceRunning = true), driving = true)
        assertEquals("앞쪽에 화장실 정보가 없어요", card.title)
    }
}
