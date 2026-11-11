package dev.shimpyo.app.ui.drive

import dev.shimpyo.app.coach.CoachUiState
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
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
    fun withNothingAheadTheEmptyStateSaysSo() {
        val card = stopCard(CoachUiState(serviceRunning = true), driving = true)
        assertEquals("앞쪽에 화장실 정보가 없어요", card.title)
    }
}
