package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.CoachOutput
import dev.shimpyo.engine.DrivingSample
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.MIN
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.SEC
import dev.shimpyo.engine.TripProfile
import dev.shimpyo.engine.adult
import dev.shimpyo.engine.kst
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 신호: 기어 UNKNOWN 오래, 차속 null, 오래된 차속, 순서 바뀐 표본 */
class AuditSignalsTest {
    private val start = kst(2026, 11, 11, 10, 0)

    private fun engine(continuousMin: Double = 0.0) = CoachEngine(
        profile = TripProfile(listOf(adult("driver")), tripStart = start - (continuousMin * 60_000).toLong(), continuousDrivingMin = continuousMin),
        random = Random(7),
    )

    private fun hour(e: CoachEngine, gear: Gear, speed: Float?): CoachOutput {
        var out = e.update(CoachInput(now = start, gear = gear, speedKph = speed))
        for (m in 1..60) out = e.update(CoachInput(now = start + m * MIN, gear = gear, speedKph = speed))
        return out
    }

    @Test
    fun anHourOfUnknownGearAtZeroSpeedIsNotDriving() {
        // 회귀(감사 S1): 기어를 모른 채 서 있던 한 시간을 운전으로 세서, 첫 D에서 '1시간 운전하셨어요'가 나왔다
        val out = hour(engine(), Gear.UNKNOWN, 0f)
        assertTrue(out.fatigue.reasons.none { it.code == "FATIGUE_DRIVING" }, out.fatigue.reasons.toString())
        assertTrue(assertNotNull(out.fatigue.minutes) >= 110, "피로 게이지가 그대로여야: ${out.fatigue.minutes}")
    }

    @Test
    fun anHourOfUnknownGearWithoutSpeedIsNotDriving() {
        // 회귀(감사 S2): 같은 경우에 차속도 없을 때 (차량 서비스 연결 전 앱이 켜짐)
        val out = hour(engine(), Gear.UNKNOWN, null)
        assertTrue(out.fatigue.reasons.none { it.code == "FATIGUE_DRIVING" }, out.fatigue.reasons.toString())
    }

    @Test
    fun aStaleSpeedDoesNotMakeAnUnknownGearLookMoving() {
        // 회귀(감사 S3): 마지막 차속을 끝없이 들고 있어, 기어·차속이 끊긴 뒤에도 시속 100km로 달리는 중으로 보고 질문했다
        val e = engine(continuousMin = 105.0) // 피로 SOON → 달린다면 상태 확인을 묻는다
        val busy = RouteState(guiding = true, nextManeuverDistanceM = 100.0) // 회전 직전: 아직 묻지 않는다
        for (s in 0 until 5) assertNull(e.update(CoachInput(now = start + s * SEC, gear = Gear.DRIVE, speedKph = 100f, route = busy)).prompt)
        val outs = (5 until 65).map { s -> e.update(CoachInput(now = start + s * SEC, gear = Gear.UNKNOWN, speedKph = null)) }
        assertTrue(outs.all { it.prompt == null }, "기어·차속을 모르면 묻지 않는다: ${outs.firstNotNullOfOrNull { it.prompt }}")
    }

    // ---------------- 지금 맞는 경계 (고칠 때 깨지지 않게) ----------------

    @Test
    fun driveGearAtStandstillInAJamStillCountsAsDriving() {
        // D로 정체 속에 서 있는 시간은 운전 시간이다 (UNKNOWN을 고칠 때 이걸 깨면 안 된다)
        val out = hour(engine(), Gear.DRIVE, 0f)
        assertTrue(out.fatigue.reasons.any { it.code == "FATIGUE_DRIVING" && it.text.contains("1시간") }, out.fatigue.reasons.toString())
    }

    @Test
    fun unknownGearWhileMovingCountsAsDriving() {
        val out = hour(engine(), Gear.UNKNOWN, 80f)
        assertTrue(out.fatigue.reasons.any { it.code == "FATIGUE_DRIVING" }, out.fatigue.reasons.toString())
    }

    @Test
    fun outOfOrderDuplicateAndFutureSamplesDoNotBreakTheUpdate() {
        val e = engine()
        val t = start + 10 * SEC
        val samples = listOf(
            DrivingSample(t = t, speedKph = 100f, steeringDeg = 1f),
            DrivingSample(t = t - 3 * SEC, speedKph = 100f, steeringDeg = 2f),
            DrivingSample(t = t, speedKph = 100f, steeringDeg = 1f),
            DrivingSample(t = t + 3_600_000L, speedKph = null, steeringDeg = null, brake = true),
            DrivingSample(t = t + SEC, speedKph = 100f, steeringDeg = 1f),
        )
        val out = e.update(CoachInput(now = t + SEC, gear = Gear.DRIVE, speedKph = null, samples = samples))
        assertTrue(out.restroom.minutes != null && out.fatigue.minutes != null)
    }
}
