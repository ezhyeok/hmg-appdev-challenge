package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.MIN
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.RouteState
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
import kotlin.test.assertTrue

/** 음성 의도: 사용자가 말한 것에는 바로 답한다 (README 3-8). 탑승자 조합별 해석 */
class AuditVoiceTest {
    private val start = kst(2026, 11, 11, 10, 0)
    private val route = RouteState(guiding = true, remainDistanceM = 200 * KM, remainTimeS = 200 * KM / V100, nextManeuverDistanceM = 50 * KM)
    private val cands = listOf(stop("a", 6 * KM, name = "가휴게소"), stop("b", 30 * KM, name = "나휴게소"))

    private fun familyEngine() = CoachEngine(
        profile = TripProfile(listOf(adult("driver"), child("kid", 7)), tripStart = start - 30 * MIN),
        random = Random(7),
    )

    private fun CoachEngine.say(intent: VoiceIntent) =
        update(CoachInput(now = start, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands)).let {
            update(CoachInput(now = start + SEC, gear = Gear.DRIVE, speedKph = 100f, route = route, candidates = cands, intent = intent))
        }

    @Test
    fun aPassengersRestroomNeedIsAnsweredRightAway() {
        // 회귀(감사 V1): "아이가 화장실 가고 싶대"는 게이지만 NOW로 올리고 답이 없었다. 엔진 질문은 말한 뒤 30초를 기다려야 했다
        val out = familyEngine().say(VoiceIntent.OccupantNeed("kid", Purpose.RESTROOM))
        assertEquals(Level.NOW, out.restroom.level)
        assertNotNull(out.prompt, "아이가 가고 싶다는 말에 바로 답해야 한다")
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
