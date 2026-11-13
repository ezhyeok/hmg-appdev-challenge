package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 도심 다음 화장실 (일상 확장, 2026-11-13). 엔진의 정차 후보(CoachInput.candidates)는 고속도로 휴게소·졸음쉼터라 도심에서는
 * '다음 화장실'이 비거나 수십 km 밖 휴게소였다. 앱은 이제 차 가까이의 일상 정차 후보(공중화장실·화장실 있는 주유소·화장실 가까운
 * 공영주차장, 지금 연 곳, 차를 세울 수 있는 곳)를 따로 넣는다(CoachInput.nearby). 엔진은 이 목록을 고속도로의 공백 판단에 쓰지 않고,
 * 경로의 화장실보다 빨리 닿을 때(NOW·말한 요청은 2분 넘게) 다음 화장실·NOW 질문·"화장실 가고 싶대"의 답에 쓴다.
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

    // ---------------------------------------------------------------- 2026-11-13 감사

    /** 아이가 지금 가야 하는 엔진 (110분 전 화장실) */
    private fun nowEngine(occupants: List<Occupant> = listOf(adult("driver"), child("kid", 7))) = CoachEngine(
        profile = TripProfile(occupants, tripStart = start - 30 * MIN, lastRestroomAt = occupants.associate { it.id to start - 110 * MIN }),
        random = Random(7),
    )

    private fun firstPrompt(e: CoachEngine, fromS: Int, toS: Int, input: (Long) -> CoachInput): Prompt? {
        for (s in fromS..toS) e.update(input(start + s * SEC)).prompt?.let { return it }
        return null
    }

    @Test
    fun aNearbyNowQuestionStaysUpForItsLifetimeAndIsNotRepeated() {
        // 감사 1: 질문이 한 틱 만에 사라지고 30초마다 새 id로 다시 떴다 (질문 장소를 경로 후보에서만 찾았다)
        val e = nowEngine()
        fun tick(sec: Int) = e.update(CoachInput(now = start + sec * SEC, gear = Gear.DRIVE, speedKph = 30f, nearby = listOf(park, gas))).prompt
        var askedAt = -1
        var id: String? = null
        for (sec in 0..40) {
            val p = tick(sec)
            if (p != null) { askedAt = sec; id = p.id; break }
        }
        assertTrue(askedAt >= 0, "지금 필요하면 묻는다")
        var last = askedAt
        for (sec in askedAt + 1..askedAt + 60) {
            if (tick(sec)?.id != id) break
            last = sec
        }
        assertTrue(last - askedAt >= 40, "질문 수명(45초) 동안 떠 있다: ${last - askedAt}초")
        for (sec in last + 1..last + 600 step 5) {
            val q = tick(sec)
            assertTrue(q == null || q.stop?.id != "park", "대답 없이 내려간 뒤 같은 곳을 곧바로 다시 묻지 않는다 (${sec}초: ${q?.speech})")
        }
    }

    @Test
    fun aVoicedNeedAnswerInTheCityStaysUp() {
        val e = engine(restroomMinAgo = 40)
        drive(e, start, listOf(park, gas))
        val p = assertNotNull(drive(e, start + 2 * SEC, listOf(park, gas), intent = VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM)).prompt)
        for (s in 3..30) assertEquals(p.id, drive(e, start + s * SEC, listOf(park, gas)).prompt?.id, "${s}초")
    }

    @Test
    fun leavingTheCityOnAHighwayRouteANowChildIsOfferedTheCloseStation() {
        // 감사 2: 부산 가는 길안내(첫 휴게소 30km), 아직 시내 도로. 아이가 지금 가야 하면 300m 앞 주유소다
        val e = nowEngine()
        val route = RouteState(guiding = true, remainDistanceM = 300_000.0, remainTimeS = 4 * 3600.0)
        val rest = stop("ra", 30_000.0, name = "서울만남의광장휴게소")
        val station = everyday("gas", 300.0, kind = StopKind.GAS_STATION, name = "분당주유소")
        val p = assertNotNull(firstPrompt(e, 0, 60) { CoachInput(now = it, gear = Gear.DRIVE, speedKph = 30f, route = route, candidates = listOf(rest), nearby = listOf(station)) })
        assertEquals("gas", p.stop?.id, p.speech)
    }

    @Test
    fun atNowAMuchCloserNearbyStopBeatsAnUnguidedRestArea4kmAway() {
        // 감사 3A: 길안내 없음, 내비로 잰 휴게소 4.5km, 주유소 300m
        val e = nowEngine()
        val rest = stop("ra", 4_500.0, name = "서울만남의광장휴게소")
        val station = everyday("gas", 300.0, kind = StopKind.GAS_STATION, name = "양재주유소")
        val p = assertNotNull(firstPrompt(e, 0, 60) { CoachInput(now = it, gear = Gear.DRIVE, speedKph = 30f, candidates = listOf(rest), nearby = listOf(station)) })
        assertEquals("gas", p.stop?.id, p.speech)
    }

    @Test
    fun anEverydayPlaceAmongTheCandidatesIsNotAskedWithTheHighwayTwoKmRule() {
        // 감사 3B: 급해 결과로 후보에 섞인 2.9km 주유소를 '진입로 2km 밖 가장 가까운 화장실'로 물었다 (300m 공중화장실을 두고)
        val e = nowEngine()
        val farGas = everyday("far-gas", 2_900.0, kind = StopKind.GAS_STATION, name = "먼 주유소")
        val p = assertNotNull(firstPrompt(e, 0, 60) { CoachInput(now = it, gear = Gear.DRIVE, speedKph = 30f, candidates = listOf(farGas), nearby = listOf(everyday("wc", 300.0, name = "역삼 공중화장실"))) })
        assertEquals("wc", p.stop?.id, p.speech)
    }

    @Test
    fun parkedNextToANearbyRestroomTheAnswerIsThatItIsRightHere() {
        // 감사 6: P, 40m 옆 공중화장실. "다음 화장실은 100m 앞 … 들를까요?"에 '응'이면 서 있는 곳으로 길안내를 걸었다
        val e = engine(restroomMinAgo = 40)
        val beside = everyday("beside", 40.0, name = "정자공원 공중화장실")
        e.update(CoachInput(now = start, gear = Gear.PARK, speedKph = 0f, nearby = listOf(beside, gas)))
        val p = assertNotNull(e.update(CoachInput(now = start + 2 * SEC, gear = Gear.PARK, speedKph = 0f, nearby = listOf(beside, gas), intent = VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM))).prompt)
        assertEquals(PromptKind.INFO, p.kind, p.speech)
        assertNull(p.stop, "내비 명령을 보낼 장소가 없다")
        assertTrue("정자공원" in p.speech, p.speech)
    }

    @Test
    fun aNearbyStopBeyondANearDestinationIsNotOffered() {
        // 감사 7: 목적지가 800m(3분) 앞인데 2km 밖 화장실을 물었다 (목적지를 지나갔다 돌아온다)
        val e = nowEngine()
        val route = RouteState(guiding = true, remainDistanceM = 800.0, remainTimeS = 180.0)
        val far = everyday("far", 2_000.0, name = "먼 공중화장실")
        for (s in 0..60) {
            val out = e.update(CoachInput(now = start + s * SEC, gear = Gear.DRIVE, speedKph = 30f, route = route, nearby = listOf(far)))
            assertNotEquals("far", out.prompt?.stop?.id, out.prompt?.speech)
            assertNotEquals("far", out.next?.stop?.id)
        }
    }

    @Test
    fun theRouteCandidateKeepsItsEtaWhileNearbyIsRanked() {
        // 감사 10: 가까운 후보 순위를 매기며 경로 도착 시간 색인을 덮어써서 휴게소 추천의 도착 시간이 비었다
        val e = engine(restroomMinAgo = 40)
        val route = RouteState(guiding = true, remainDistanceM = 100_000.0, remainTimeS = 4_500.0)
        val rest = stop("ra", 3_000.0, name = "안성휴게소")
        val out = e.update(CoachInput(now = start, gear = Gear.DRIVE, speedKph = 40f, route = route, candidates = listOf(rest), nearby = listOf(park)))
        assertEquals("ra", out.next?.stop?.id)
        assertNotNull(out.next?.stop?.etaS, "경로 후보의 도착 시간")
    }

    @Test
    fun aNearbyRecommendationsEtaUsesCitySpeed() {
        // 600m × 도로 계수 1.3 ÷ 시속 25km = 약 112초 (예전에는 시속 80km로 35초)
        val out = drive(engine(restroomMinAgo = 40), start, listOf(park, gas))
        val eta = assertNotNull(out.next?.stop?.etaS)
        assertTrue(eta in 90.0..140.0, "도심 속도의 도착 시간: $eta")
    }

    @Test
    fun aDiaperRequestInTheCityGetsANearbyChangingTable() {
        // 감사 12: 밤 11시, 영아. 500m 앞 기저귀 교환대 화장실이 있는데 "경로 앞에서 들를 수 있는 곳을 찾지 못했어요"
        val e = CoachEngine(
            profile = TripProfile(listOf(adult("driver"), infant("baby")), tripStart = start - 30 * MIN, lastRestroomAt = mapOf("driver" to start - 30 * MIN, "baby" to start - 30 * MIN)),
            random = Random(7),
        )
        val changing = everyday("changing", 500.0, name = "역삼 공중화장실").let { it.copy(facilities = it.facilities.copy(diaperTable = true)) }
        drive(e, start, listOf(changing))
        val p = assertNotNull(drive(e, start + 2 * SEC, listOf(changing), intent = VoiceIntent.OccupantNeed("baby", Purpose.FEEDING)).prompt)
        assertEquals("changing", p.stop?.id, p.speech)
    }

    @Test
    fun howLongInTheCityNamesTheNearbyRestroom() {
        val e = engine(restroomMinAgo = 40)
        drive(e, start, listOf(park, gas))
        val p = assertNotNull(drive(e, start + 2 * SEC, listOf(park, gas), intent = VoiceIntent.HowLong).prompt)
        assertTrue("정자공원" in p.speech, p.speech)
    }

    // ---------------------------------------------------------------- 2026-11-13 검증

    @Test
    fun aCityQuestionSurvivesAShortAbsenceLikeATurn() {
        // 검증 2: 좌회전하는 동안 장소가 진행 방향 뒤로 잠깐 빠지면 질문이 첫 틱에 사라지고 20분 동안 다른 질문까지 막혔다
        val e = nowEngine()
        fun tick(sec: Int, list: List<StopCandidate>) = e.update(CoachInput(now = start + sec * SEC, gear = Gear.DRIVE, speedKph = 30f, nearby = list)).prompt
        var askedAt = -1
        var id: String? = null
        for (sec in 0..40) { val p = tick(sec, listOf(park, gas)); if (p != null) { askedAt = sec; id = p.id; break } }
        assertTrue(askedAt >= 0)
        for (k in 1..3) tick(askedAt + k, listOf(gas)) // 회전: 정자공원이 3초 동안 목록에서 빠진다
        assertEquals(id, tick(askedAt + 4, listOf(park, gas))?.id, "짧게 빠졌다 돌아온 장소의 질문은 그대로다")
    }

    @Test
    fun aCityQuestionWhosePlaceIsGoneDoesNotBlockTheNextOne() {
        // 지나쳐 목록에서 빠진 장소의 질문은 내리되, 그것을 '대답하지 않음'으로 쳐서 다음 장소의 질문을 20분 막지 않는다
        val e = nowEngine()
        fun tick(sec: Int, list: List<StopCandidate>) = e.update(CoachInput(now = start + sec * SEC, gear = Gear.DRIVE, speedKph = 30f, nearby = list)).prompt
        var askedAt = -1
        for (sec in 0..40) { if (tick(sec, listOf(park, gas))?.stop?.id == "park") { askedAt = sec; break } }
        assertTrue(askedAt >= 0)
        var next: Prompt? = null
        for (sec in askedAt + 1..askedAt + 90) { val p = tick(sec, listOf(gas)); if (p?.stop?.id == "gas") { next = p; break } }
        assertNotNull(next, "정자공원을 지나친 뒤 1분 30초 안에 다음 곳(분당주유소)을 묻는다")
    }

    @Test
    fun anUrgentResultKeepsItsLifetimeThroughAOneTickAbsence() {
        // 검증 6: 거리 없이 낸 질문을 모두 도심 질문으로 봐서, 급해 결과도 후보가 한 틱 빠지면 사라졌다 (예전에는 수명까지 남았다)
        val e = engine(restroomMinAgo = 40)
        val rest = stop("ra", 6_000.0, name = "안성휴게소")
        val p = assertNotNull(e.update(CoachInput(now = start, gear = Gear.DRIVE, speedKph = 60f, candidates = listOf(rest), intent = VoiceIntent.UrgentRestroom)).prompt)
        assertEquals(PromptKind.URGENT_RESULT, p.kind)
        e.update(CoachInput(now = start + SEC, gear = Gear.DRIVE, speedKph = 60f, candidates = emptyList()))
        assertEquals(p.id, e.update(CoachInput(now = start + 2 * SEC, gear = Gear.DRIVE, speedKph = 60f, candidates = listOf(rest))).prompt?.id)
    }

    @Test
    fun onAGuidedRouteWithARestAreaCloseByTheNearbyParkDoesNotTakeOverTheNextCard() {
        // 검증 8: 경로 위 휴게소가 3km 앞인데(게이지 여유) 다음 화장실이 600m 공원이 됐다. 비슷하게 닿으면 경로의 화장실이다
        val e = engine(restroomMinAgo = 40)
        // 목적지 10km: 휴게소 뒤 공백이 짧아 마지막 기회가 아니다 (그 규칙 없이 다음 화장실을 고르는 경우)
        val route = RouteState(guiding = true, remainDistanceM = 10_000.0, remainTimeS = 420.0)
        val rest = stop("ra", 3_000.0, name = "안성휴게소")
        val out = e.update(CoachInput(now = start, gear = Gear.DRIVE, speedKph = 90f, route = route, candidates = listOf(rest), nearby = listOf(park)))
        assertNotEquals("park", out.next?.stop?.id, "${out.next}")
    }
}
