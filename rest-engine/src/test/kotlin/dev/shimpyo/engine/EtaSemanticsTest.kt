package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 후보의 etaS 의미 (StopCandidate.etaS 주석). etaS는 내비가 그 후보까지 교통을 반영해 계산한 값만 넣고,
 * 없으면 엔진이 정체 구간으로 도착 시간을 계산해 추천에 채운다.
 *
 * 회귀: 2026-11-11 에뮬레이터 시연에서 앱이 평균 속도로 만든 etaS를 넣어 정체 뒤 휴게소가 가깝게 계산됐고
 * '정체 전 마지막 화장실' 추천이 나오지 않았다.
 */
class EtaSemanticsTest {
    private val km = 1_000.0
    private val start = kst(2026, 9, 24, 14, 0)

    /** 운전자와 7살 아이 모두 [sinceRestroomMin]분 전에 화장실에 다녀왔다 */
    private fun engine(sinceRestroomMin: Long) = CoachEngine(
        profile = TripProfile(
            listOf(adult("driver"), child("kid", 7, label = "7살 아이")),
            tripStart = start - 46 * MIN,
            lastRestroomAt = mapOf("driver" to start - sinceRestroomMin * MIN, "kid" to start - sinceRestroomMin * MIN),
        ),
        random = Random(7),
    )

    /** 장면 A의 기하: 옥산휴게소 4.4km, 5.4km부터 18.4km·60분 정체, 죽암휴게소 24.1km(정체 뒤) */
    private fun input(jukamEtaS: Double?) = CoachInput(
        now = start,
        gear = Gear.DRIVE,
        speedKph = 100f,
        route = RouteState(
            guiding = true, congestionBasisKnown = true,
            remainDistanceM = 300 * km,
            remainTimeS = 4 * 3_600.0,
            congestion = listOf(CongestionSegment(startDistanceM = 5.4 * km, lengthM = 18.4 * km, durationS = 3_600.0, roadName = "경부고속도로")),
            nextManeuverDistanceM = 20 * km,
        ),
        candidates = listOf(
            stop("oksan", 4.4 * km, name = "옥산휴게소", facilities = Facilities(food = true)),
            stop("jukam", 24.1 * km, name = "죽암휴게소", facilities = Facilities(food = true), etaS = jukamEtaS),
        ),
    )

    @Test
    fun sceneAWithoutNavEtaUsesTheCongestionAndFillsTheEta() {
        val out = engine(sinceRestroomMin = 55).update(input(jukamEtaS = null))
        val next = assertNotNull(out.next)
        assertEquals("oksan", next.stop.id)
        assertTrue(next.reasons.single().code.startsWith("LAST_CHANCE"), next.reasons.toString())
        val eta = assertNotNull(next.stop.etaS, "추천에 엔진이 정체를 반영해 계산한 도착 시간을 채운다")
        assertTrue(eta in 150.0..170.0, "옥산 4.4km를 시속 100km로: $eta s")
        // 4.4km는 묻는 거리(2~5km) 안이라 질문도 나온다. 질문 카드에도 같은 도착 시간이 있어야 한다
        val prompt = assertNotNull(out.prompt, "옥산 4.4km 전이면 묻는다")
        assertEquals(PromptKind.LAST_CHANCE, prompt.kind)
        assertEquals(eta, prompt.stop?.etaS)
    }

    @Test
    fun aNavEtaIsTrustedSoTheAppMustNotInventOne() {
        // 마지막 화장실 30분 전: 판단 마감이 약 38분 뒤라 정체 뒤 죽암(63분)은 늦고 옥산을 물어야 한다
        val noEta = engine(30).update(input(jukamEtaS = null)).next
        assertEquals("oksan", noEta?.stop?.id, "etaS가 없으면 정체를 반영해 계산한다")
        val navEta = engine(30).update(input(jukamEtaS = 63 * 60.0)).next
        assertEquals("oksan", navEta?.stop?.id, "내비가 정체를 반영해 준 값은 그대로 쓴다")
        // 경로 평균 속도로 만든 값(16분)은 엔진이 믿어 버려서 결론이 바뀐다. 그래서 앱은 이런 값을 넣지 않는다
        val invented = engine(30).update(input(jukamEtaS = 16 * 60.0)).next
        assertNotEquals("oksan", invented?.stop?.id)
    }
}
