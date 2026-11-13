package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 도심 다음 화장실 (일상 확장, 2026-11-13). 엔진의 정차 후보(CoachInput.candidates)는 고속도로 휴게소·졸음쉼터라 도심에서는
 * '다음 화장실'이 비거나 수십 km 밖 휴게소였다. 앱은 이제 차 가까이의 일상 정차 후보(공중화장실·화장실 있는 주유소·화장실 가까운
 * 공영주차장, 지금 연 곳, 차를 세울 수 있는 곳)를 따로 넣는다(CoachInput.nearby). 엔진은 이 목록을 고속도로의 공백 판단에 쓰지 않고,
 * 경로 위 화장실 기회가 [CoachConfig.nearbyPreferRouteM] 안에 없을 때만 다음 화장실·NOW 질문·"화장실 가고 싶대"의 답에 쓴다.
 */
class CityNearbyTest {
    private val start = kst(2026, 11, 13, 17, 30)

    private fun engine(restroomMinAgo: Int) = CoachEngine(
        profile = TripProfile(
            listOf(adult("driver"), child("kid", 7)), tripStart = start - 30 * MIN,
            lastRestroomAt = mapOf("driver" to start - restroomMinAgo * MIN, "kid" to start - restroomMinAgo * MIN),
        ),
        random = Random(7),
    )

    private fun everyday(id: String, straightM: Double, kind: StopKind = StopKind.PUBLIC_RESTROOM, name: String = id, open: Boolean? = true, parkable: Boolean = true) =
        stop(id, routeM = null, kind = kind, name = name, straightM = straightM, openNow = open, parkable = parkable)

    private val park = everyday("park", 600.0, name = "정자공원 공중화장실")
    private val gas = everyday("gas", 1_200.0, kind = StopKind.GAS_STATION, name = "분당주유소")

    private fun drive(e: CoachEngine, at: Long, nearby: List<StopCandidate>, candidates: List<StopCandidate> = emptyList(), intent: VoiceIntent? = null) =
        e.update(CoachInput(now = at, gear = Gear.DRIVE, speedKph = 40f, candidates = candidates, nearby = nearby, intent = intent))

    @Test
    fun inTheCityTheNextRestroomIsTheNearbyOneNotAFarRestArea() {
        val e = engine(restroomMinAgo = 40)
        val out = drive(e, start, listOf(park, gas), candidates = listOf(stop("ra", 25_000.0, name = "서울만남의광장휴게소")))
        val next = assertNotNull(out.next, "다음 화장실을 낸다")
        assertEquals("park", next.stop.id)
        assertTrue(next.reasons.any { it.code == "NEXT_NEARBY_RESTROOM" }, next.reasons.toString())
    }

    @Test
    fun whenTheGaugeIsNowTheNearbyRestroomIsAskedEvenThoughItIsClose() {
        // 아이가 지금 가야 한다. 600m 앞 공중화장실: 고속도로의 '진입로 2km 전' 규칙(빠지기 어렵다)은 도심 길에 쓰지 않는다
        val e = engine(restroomMinAgo = 110)
        var asked: Prompt? = null
        for (s in 0..40 step 5) {
            val p = drive(e, start + s * SEC, listOf(park, gas)).prompt
            if (p != null) { asked = p; break }
        }
        val p = assertNotNull(asked, "지금 필요하면 묻는다")
        assertEquals("park", p.stop?.id)
        assertTrue("정자공원" in p.speech, p.speech)
    }

    @Test
    fun aVoicedNeedIsAnsweredWithTheNearbyRestroom() {
        val e = engine(restroomMinAgo = 40)
        drive(e, start, listOf(park, gas))
        val p = assertNotNull(drive(e, start + 2 * SEC, listOf(park, gas), intent = VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM)).prompt)
        assertEquals("park", p.stop?.id, p.speech)
        assertTrue(p.userRequested)
    }

    @Test
    fun closedOrUnparkablePlacesAreSkipped() {
        val e = engine(restroomMinAgo = 40)
        val closed = everyday("closed", 300.0, open = false)
        val noParking = everyday("nopark", 400.0, parkable = false)
        assertEquals("park", drive(e, start, listOf(closed, noParking, park)).next?.stop?.id)
    }

    @Test
    fun onAHighwayRouteTheRouteOpportunityStaysFirst() {
        // 경로 위 휴게소가 4km 앞에 있다: 길가 공중화장실(고속도로에서는 못 간다)은 쓰지 않는다
        val e = engine(restroomMinAgo = 110)
        val rest = stop("ra", 4_000.0, name = "안성휴게소")
        var asked: Prompt? = null
        for (s in 0..40 step 5) {
            val p = e.update(CoachInput(now = start + s * SEC, gear = Gear.DRIVE, speedKph = 100f, candidates = listOf(rest), nearby = listOf(park))).prompt
            if (p != null) { asked = p; break }
        }
        assertEquals("ra", asked?.stop?.id, asked?.speech)
    }

    @Test
    fun withNothingNearbyNothingChanges() {
        val e = engine(restroomMinAgo = 40)
        assertEquals(null, drive(e, start, emptyList()).next, "가까운 후보도 경로 후보도 없으면 추천도 없다 (예전과 같다)")
    }
}
