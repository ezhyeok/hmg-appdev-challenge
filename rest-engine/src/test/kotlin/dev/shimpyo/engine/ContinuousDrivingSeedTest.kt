package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 엔진 전에 이미 달린 시간 (TripProfile.continuousDrivingMin). 회귀: 오후 졸음 시연의 '연속 95분'이 출발 시각에만 들어가
 * 피로 게이지가 0분부터 셌고, 휴식 제안이 나오지 않았다(2026-11-11 에뮬레이터).
 */
class ContinuousDrivingSeedTest {
    private val start = kst(2026, 11, 11, 15, 0)

    private fun engine(continuousMin: Double) = CoachEngine(
        profile = TripProfile(listOf(adult("driver")), tripStart = start - (continuousMin * 60_000).toLong(), continuousDrivingMin = continuousMin),
        random = Random(7),
    )

    private fun drive(e: CoachEngine, from: Long, seconds: Int): List<CoachOutput> = (0 until seconds).map { i ->
        val t = from + i * SEC
        e.update(CoachInput(now = t, gear = Gear.DRIVE, speedKph = 90f, samples = listOf(DrivingSample(t = t, speedKph = 90f, steeringDeg = 0f))))
    }

    @Test
    fun ninetyFiveMinutesAtThreePmStartNearTheRestPoint() {
        val e = engine(95.0)
        val first = e.update(CoachInput(now = start, gear = Gear.DRIVE, speedKph = 90f))
        val minutes = assertNotNull(first.fatigue.minutes)
        assertTrue(minutes <= 15, "휴식 리듬이 95분만큼 차 있어야 한다: 남은 $minutes 분")
        assertTrue(first.fatigue.reasons.any { it.code == "FATIGUE_DRIVING" && it.text.contains("1시간 35분") }, first.fatigue.reasons.toString())
        // 몇 분 안에 휴식을 권한다
        val outs = drive(e, start + SEC, 20 * 60)
        assertTrue(outs.any { it.prompt?.kind == PromptKind.REST_SUGGESTION }, "20분 안에 휴식 제안")
    }

    @Test
    fun noDrivingQuestionUntilMovementIsKnown() {
        // 피로가 곧 바닥나는 상태에서 앱이 막 켜졌다. 기어 신호가 오기 전·P로 서 있는 동안에는 묻지 않고, D가 되면 묻는다
        // (2026-11-11 에뮬레이터: 휴게소에 P로 서 있는데 시작 첫 초에 '혹시 졸리세요?'가 나왔다)
        val e = engine(110.0)
        assertEquals(null, e.update(CoachInput(now = start, gear = Gear.UNKNOWN)).prompt, "기어·차속을 모르면 묻지 않는다")
        assertEquals(null, e.update(CoachInput(now = start + SEC, gear = Gear.UNKNOWN, speedKph = 0f)).prompt, "서 있으면 묻지 않는다")
        assertEquals(null, e.update(CoachInput(now = start + 2 * SEC, gear = Gear.PARK, speedKph = 0f)).prompt, "P면 묻지 않는다")
        val asked = drive(e, start + 3 * SEC, 120).mapNotNull { it.prompt }
        assertTrue(asked.isNotEmpty(), "D로 달리면 묻는다")
    }

    @Test
    fun withoutASeedTheClockStartsAtZeroAsBefore() {
        val out = engine(0.0).update(CoachInput(now = start, gear = Gear.DRIVE, speedKph = 90f))
        val minutes = assertNotNull(out.fatigue.minutes)
        assertTrue(minutes >= 90, "씨앗이 없으면 예전처럼 0분부터: 남은 $minutes 분")
        assertEquals(false, out.fatigue.reasons.any { it.code == "FATIGUE_DRIVING" })
    }
}
