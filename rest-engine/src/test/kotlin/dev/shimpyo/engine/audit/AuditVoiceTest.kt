package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CoachOutput
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.MIN
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.SEC
import dev.shimpyo.engine.StopKind
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
import kotlin.test.assertTrue

/** 음성 의도: 사용자가 말한 것에는 바로 답한다 (README 3-8). 탑승자 조합별 해석 */
class AuditVoiceTest {
    private val start = kst(2026, 11, 11, 10, 0)
    private val route = RouteState(guiding = true, congestionBasisKnown = true, remainDistanceM = 200 * KM, remainTimeS = 200 * KM / V100, nextManeuverDistanceM = 50 * KM)
    private val cands = listOf(stop("a", 6 * KM, name = "가휴게소"), stop("b", 30 * KM, name = "나휴게소"))

    private fun familyEngine() = CoachEngine(
        profile = TripProfile(listOf(adult("driver"), child("kid", 7)), tripStart = start - 30 * MIN),
        random = Random(7),
    )

    private fun CoachEngine.say(intent: VoiceIntent, list: List<dev.shimpyo.engine.StopCandidate> = cands) =
        update(CoachInput(now = start, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = list)).let {
            update(CoachInput(now = start + SEC, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = list, intent = intent))
        }

    @Test
    fun aPassengersRestroomNeedIsAnsweredRightAway() {
        // 회귀(감사 V1): "아이가 화장실 가고 싶대"는 게이지만 NOW로 올리고 답이 없었다. 엔진 질문은 말한 뒤 30초를 기다려야 했다
        val out = familyEngine().say(VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM))
        assertEquals(Level.NOW, out.restroom.level)
        assertNotNull(out.prompt, "아이가 가고 싶다는 말에 바로 답해야 한다")
    }

    // ---------------- 서 있을 때(기어 P), 움직이는지 모를 때의 요청 (닫힘 검증 E6, 감사 V1·V4의 남은 부분) ----------------

    /** 첫 update로 정차 상태를 만들고 두 번째 update에 말한다 */
    private fun CoachEngine.sayParked(intent: VoiceIntent, list: List<dev.shimpyo.engine.StopCandidate> = cands, gear: Gear = Gear.PARK, speed: Float? = 0f): CoachOutput {
        update(CoachInput(now = start, gear = gear, speedKph = speed, route = route, candidates = list))
        return update(CoachInput(now = start + SEC, gear = gear, speedKph = speed, route = route, candidates = list, intent = intent))
    }

    @Test
    fun aPassengersRestroomNeedIsAnsweredWhileParked() {
        // 회귀: 달리는 중에는 바로 답했지만 기어 P(또는 기어·차속을 모를 때)에는 답을 말없이 버렸다. 같은 정차 상태에서 "쉬고 싶어"·
        // "배고파"·주유·"얼마나 남았어"·"화장실 급해"에는 답했다. 꽉 막힌 정체에서 P로 서 있을 때 "아이가 화장실 가고 싶대"라고 하면
        // 게이지만 NOW가 되고 음성은 조용했다
        assertNotNull(familyEngine().sayParked(VoiceIntent.WantRest).prompt, "대조: 정차 중 \"쉬고 싶어\"에는 답한다")
        val out = familyEngine().sayParked(VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM))
        assertEquals(Level.NOW, out.restroom.level)
        val p = assertNotNull(out.prompt, "정차 중 \"아이가 화장실 가고 싶대\"에도 답한다")
        // 달릴 때와 같은 답: 가장 가까운 화장실을 묻는다 (수락하면 경유지로 넣을 수 있다)
        assertEquals(PromptKind.LAST_CHANCE, p.kind)
        assertEquals("a", p.stop?.id)
        assertEquals("다음 화장실은 6km 앞 가휴게소예요. 들를까요?", p.speech)
        assertEquals(listOf("NEXT_REQUESTED_RESTROOM"), out.next?.reasons?.map { it.code })
        assertTrue(p.userRequested, "사용자의 말에 대한 답이라는 표시: 앱이 정차 중 '주행 질문'을 거둘 때 이것은 남긴다")
        assertTrue(out.parked)
    }

    @Test
    fun whenMovementIsUnknownTheRequestIsStillAnswered() {
        // 기어·차속을 모를 때(앱이 막 켜졌다)도 말한 것에는 답한다. 달리는 중일 수 있으므로 진입로 2km 밖의 화장실만
        val near = listOf(stop("close", 1.2 * KM, name = "코앞휴게소")) + cands
        val out = familyEngine().sayParked(VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM), list = near, gear = Gear.UNKNOWN, speed = null)
        assertEquals("a", assertNotNull(out.prompt).stop?.id)
    }

    @Test
    fun parkedARestroomCloserThanTwoKilometresIsOffered() {
        // 서 있으면 진입로를 놓칠 일이 없다: 800m 앞 화장실도 답한다 (달릴 때는 2km 안쪽을 건너뛴다)
        val near = listOf(stop("close", 800.0, kind = StopKind.PUBLIC_RESTROOM, name = "공원화장실")) + cands
        val parked = familyEngine().sayParked(VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM), list = near)
        assertEquals("close", assertNotNull(parked.prompt).stop?.id)
        assertEquals("다음 화장실은 800m 앞 공원화장실이에요. 들를까요?", parked.prompt?.speech)
        val driving = familyEngine().say(VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM), near)
        assertEquals("a", assertNotNull(driving.prompt).stop?.id)
    }

    @Test
    fun parkedNextToARestroomTheAnswerSaysSo() {
        // 선 곳 가까이(300m 안)에 화장실 있는 곳이 보이면 다음 화장실을 묻지 않고 그렇게 답한다 (앱이 위치 기준 거리로 넣은 후보)
        val here = stop("here", null, name = "가휴게소", straightM = 80.0)
        val out = familyEngine().sayParked(VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM), list = listOf(here, stop("b", 30 * KM, name = "나휴게소")))
        val p = assertNotNull(out.prompt)
        assertEquals(PromptKind.INFO, p.kind)
        assertEquals("가까이에 화장실이 있어요. 가휴게소예요.", p.speech)
        assertEquals(null, p.stop)
        // 화장실이 없는 곳(주차만 되는 졸음쉼터)이나 닫힌 곳 옆이면 다음 화장실을 묻는다
        for (noRestroom in listOf(
            stop("here", null, kind = StopKind.DROWSY_SHELTER, name = "가졸음쉼터", straightM = 80.0, facilities = dev.shimpyo.engine.Facilities(restroom = false)),
            stop("here", null, name = "가휴게소", straightM = 80.0, openNow = false),
        )) {
            val other = familyEngine().sayParked(VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM), list = listOf(noRestroom, stop("b", 30 * KM, name = "나휴게소")))
            assertEquals("b", assertNotNull(other.prompt).stop?.id, noRestroom.toString())
        }
        // 달리는 중이면 300m 안의 후보는 지나가는 곳이다: '가까이에 있다'고 답하지 않는다
        val driving = familyEngine().say(VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM), listOf(here, stop("b", 30 * KM, name = "나휴게소")))
        assertEquals(PromptKind.LAST_CHANCE, driving.prompt?.kind)
    }

    @Test
    fun feedingAndPetRequestsAreAnsweredWhileParkedToo() {
        for (purpose in listOf(Purpose.FEEDING, Purpose.PET)) {
            val out = familyEngine().sayParked(VoiceIntent.OccupantNeed(null, purpose))
            assertEquals("6km 앞 가휴게소에서 잠깐 쉬어 갈 수 있어요. 들를까요?", out.prompt?.speech, purpose.name)
            assertEquals(listOf("NEXT_REQUESTED_CARE"), out.next?.reasons?.map { it.code })
            val here = familyEngine().sayParked(VoiceIntent.OccupantNeed(null, purpose), list = listOf(stop("here", null, name = "가휴게소", straightM = 80.0)))
            assertEquals("지금 서 있는 가휴게소에서 잠깐 쉬어 갈 수 있어요.", here.prompt?.speech)
        }
    }

    @Test
    fun anUnansweredAnswerGivenWhileParkedDoesNotSilenceTheQuestionAfterDeparture() {
        // 서 있는 동안 답한 "다음 화장실은 …"에 대답이 없었다 (앱은 정차 중에 주행 질문 카드를 거두기도 한다). 달리다 대답 없이 지나간
        // 답은 '그곳은 됐다'로 보고 그곳을 지날 때까지 다시 묻지 않지만, 서 있을 때의 무응답을 그렇게 보면 출발한 뒤에도 가장 가까운
        // 화장실을 묻지 못한다(아이는 여전히 화장실이 필요하다). 서 있을 때의 무응답에는 쉼을 걸지 않는다
        val e = familyEngine()
        val first = assertNotNull(e.sayParked(VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM)).prompt)
        assertEquals("a", first.stop?.id)
        var t = start + SEC
        val parked = (0 until 70).map { t += SEC; e.update(CoachInput(now = t, gear = Gear.PARK, speedKph = 0f, route = route, candidates = cands)) }
        assertEquals(null, parked.last().prompt, "45초 뒤 답은 내려갔다")
        val driving = (0 until 40).map { t += SEC; e.update(CoachInput(now = t, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands)) }
        val again = assertNotNull(driving.firstNotNullOfOrNull { it.prompt }, "출발하면 가까운 화장실을 묻는다")
        assertEquals("a", again.stop?.id)
        assertTrue(again.id != first.id && !again.userRequested)

        // 대조: 달리다 받은 답에 대답이 없으면 그곳(가휴게소)은 다시 묻지 않는다
        val d = familyEngine()
        val asked = assertNotNull(d.say(VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM)).prompt)
        var u = start + SEC
        val later = (0 until 110).map { u += SEC; d.update(CoachInput(now = u, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands)) }
        assertTrue(later.none { it.prompt != null && it.prompt?.id != asked.id }, later.firstNotNullOfOrNull { it.prompt?.takeIf { p -> p.id != asked.id } }.toString())
    }

    @Test
    fun enginePromptsAreNotMarkedAsUserRequested() {
        val e = CoachEngine(
            profile = TripProfile(listOf(adult("driver"), child("kid", 5)), tripStart = start, lastRestroomAt = mapOf("kid" to start - 30 * MIN)),
            random = Random(7),
        )
        val q = assertNotNull(e.update(CoachInput(now = start, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = listOf(stop("a", 3 * KM), stop("b", 40 * KM)))).prompt)
        assertEquals(PromptKind.LAST_CHANCE, q.kind)
        assertTrue(!q.userRequested, "엔진이 먼저 건 마지막 기회")
        assertTrue(familyEngine().say(VoiceIntent.HowLong).prompt!!.userRequested)
        assertTrue(familyEngine().say(VoiceIntent.UrgentRestroom).prompt!!.userRequested)
    }

    @Test
    fun wantRestIsAnsweredRightAway() {
        // 대조: "쉬고 싶어"는 바로 답한다 (지금 맞다)
        val out = familyEngine().say(VoiceIntent.WantRest)
        assertEquals(PromptKind.REST_SUGGESTION, out.prompt?.kind)
    }

    @Test
    fun urgentRestroomIsAnsweredRightAway() {
        val out = familyEngine().say(VoiceIntent.UrgentRestroom)
        assertEquals(PromptKind.URGENT_RESULT, out.prompt?.kind)
        assertTrue(out.urgent.orEmpty().size <= 2, "주행 중 2곳까지")
    }

    @Test
    fun aFuelRequestIsNotSilentlyDropped() {
        // 회귀(감사 V2): 주유 요청은 아무 일도 하지 않았다(그러고도 30초 조용히가 시작됐다)
        val out = familyEngine().say(VoiceIntent.OccupantNeed(null, Purpose.FUEL))
        assertNotNull(out.prompt, "말한 것에는 바로 답한다 (README 3-8)")
    }

    @Test
    fun urgentWithOnlyADogDoesNotTrainTheDogsModel() {
        // 회귀(감사 O3): 사람이 없으면 운전자의 "화장실 급해"가 반려견의 정확한 간격으로 남아 반려견 게이지를 계속 줄였다
        val e = CoachEngine(profile = TripProfile(listOf(Occupant("dog", OccupantType.DOG)), tripStart = start - 60 * MIN), random = Random(7))
        e.say(VoiceIntent.UrgentRestroom)
        assertTrue(e.memory().restroomIntervalsMin["dog"].isNullOrEmpty(), e.memory().restroomIntervalsMin.toString())
    }
}
