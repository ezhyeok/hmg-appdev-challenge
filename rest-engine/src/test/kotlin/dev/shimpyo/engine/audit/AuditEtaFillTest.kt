package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.HighwaySim
import dev.shimpyo.engine.Jam
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.MIN
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.RouteStop
import dev.shimpyo.engine.SEC
import dev.shimpyo.engine.TripProfile
import dev.shimpyo.engine.VoiceIntent
import dev.shimpyo.engine.adult
import dev.shimpyo.engine.child
import dev.shimpyo.engine.kst
import dev.shimpyo.engine.stop
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 엔진이 계산한 도착 시간을 추천·질문 카드에 채우는가 (오늘 고친 etaS 결함의 형제들).
 * 계약(Api.kt StopCandidate.etaS): etaS는 내비 값만, 없으면 엔진이 정체로 계산해 '추천'에 채운다. 앱이 평균 속도로 지어내지 않게.
 */
class AuditEtaFillTest {

    @Test
    fun theAcceptedStopKeepsTheEngineArrivalTime() {
        // 회귀(감사 R22): 질문 카드에는 도착 시간이 있었는데 "응" 직후 수락한 곳 카드(지금 가는 경유지)에는 없었다
        val start = kst(2026, 9, 24, 14, 0)
        val engine = CoachEngine(
            profile = TripProfile(
                listOf(adult("driver"), child("kid", 7)), tripStart = start - 46 * MIN,
                lastRestroomAt = mapOf("driver" to start - 55 * MIN, "kid" to start - 55 * MIN),
            ),
            random = Random(7),
        )
        val sim = HighwaySim(
            engine, start, routeLengthM = 300 * KM,
            stops = listOf(
                RouteStop(4.45 * KM, stop("oksan", null, name = "옥산휴게소", facilities = Facilities(food = true))),
                RouteStop(24.1 * KM, stop("jukam", null, name = "죽암휴게소", facilities = Facilities(food = true))),
            ),
            jams = listOf(Jam(5.4 * KM, 18.4 * KM, 3_600.0)),
        )
        val lc = assertNotNull(sim.step().prompt)
        assertEquals(PromptKind.LAST_CHANCE, lc.kind)
        assertNotNull(lc.stop?.etaS, "질문 카드에는 있다")
        val out = sim.step(intent = VoiceIntent.Accept(lc.id))
        assertEquals("NEXT_ACCEPTED", out.next?.reasons?.single()?.code)
        assertNotNull(out.next?.stop?.etaS, "수락한 곳 카드에도 엔진 도착 시간이 있어야 한다")
    }

    private val start = kst(2026, 11, 11, 10, 0)
    private val route = RouteState(guiding = true, remainDistanceM = 200 * KM, remainTimeS = 200 * KM / V100, nextManeuverDistanceM = 50 * KM)
    private val cands = listOf(
        stop("a", 6 * KM, name = "가휴게소", facilities = Facilities(food = true, diaperTable = true)),
        stop("b", 30 * KM, name = "나휴게소", facilities = Facilities(food = true)),
    )

    private fun engine() = CoachEngine(
        profile = TripProfile(listOf(adult("driver"), child("kid", 7)), tripStart = start - 30 * MIN),
        random = Random(7),
    )

    @Test
    fun theAnswerToWantRestCarriesTheEngineArrivalTime() {
        // 회귀(감사 R22): "쉬고 싶어"·"배고파" 답 카드는 엔진 도착 시간을 채우지 않아 같은 곳의 next 카드와 달랐다
        val e = engine()
        e.update(CoachInput(now = start, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands))
        val out = e.update(CoachInput(now = start + SEC, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands, intent = VoiceIntent.WantRest))
        val p = assertNotNull(out.prompt)
        assertEquals(PromptKind.REST_SUGGESTION, p.kind)
        assertNotNull(out.next?.stop?.etaS, "next 카드에는 있다")
        assertNotNull(p.stop?.etaS, "질문 카드에도 있어야 한다")
    }

    @Test
    fun theNowLevelRecommendationCarriesTheEngineArrivalTime() {
        // 회귀(감사 R22): 화장실 NOW·급해 추천은 급해 순위의 후보를 그대로 내보내 도착 시간이 없었다
        val e = engine()
        e.update(CoachInput(now = start, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands))
        val out = e.update(
            CoachInput(now = start + SEC, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands, intent = VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM)),
        )
        assertEquals(Level.NOW, out.restroom.level)
        val next = assertNotNull(out.next)
        assertNotNull(next.stop.etaS, "NOW 추천 카드에도 엔진 도착 시간이 있어야 한다: ${next.stop.id}")
    }
}
