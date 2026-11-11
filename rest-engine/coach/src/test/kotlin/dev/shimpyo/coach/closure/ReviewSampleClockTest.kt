package dev.shimpyo.coach.closure

import dev.shimpyo.coach.SignalHub
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.DrivingSample
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.TripProfile
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.sin

/**
 * 독립 리뷰(2026-11-12): 시연에서 조향 표본의 시각이 엔진 시각과 다른 시계다. 지금 실패한다(고치면 통과).
 * 앱 배선(CarSignals)을 그대로 흉내 낸다: 조향 리스너는 표본을 실제 시각(wall::now)으로 찍어 SignalHub.addSample에 넣고,
 * 코어의 틱은 SignalHub.drain(t)와 CoachInput.now에 세계 시각(시연이면 가상 시각)을 쓴다.
 */
class ReviewSampleClockTest {

    /** 실제 [minutes]분 동안 시속 100km 정속 주행. 기준선이 준비된 실제 시각(분)을 돌려준다 (안 되면 null) */
    private fun baselineReadyAfterMin(offsetMs: Long, stampWithWall: Boolean, minutes: Int = 40): Double? {
        val wall0 = 1_762_909_200_000L
        val engine = CoachEngine(profile = TripProfile(listOf(Occupant("driver", OccupantType.ADULT)), tripStart = wall0 + offsetMs))
        val hub = SignalHub()
        hub.gear = Gear.DRIVE
        hub.speedKph = 100f
        engine.update(CoachInput(now = wall0 + offsetMs, gear = Gear.DRIVE, speedKph = 100f))
        for (tick in 1..minutes * 60) {
            val wall = wall0 + tick * 1000L
            val world = wall + offsetMs // 배속 없는 시연 (시각만 다르다)
            for (ms in listOf(500L, 1000L)) { // 조향 리스너: 0.5초마다 값이 온다
                val at = wall - 1000 + ms
                val steer = (2.0 * sin(at / 700.0)).toFloat()
                hub.steeringDeg = steer
                hub.addSample(DrivingSample(t = if (stampWithWall) at else at + offsetMs, speedKph = 100f, steeringDeg = steer))
            }
            val (samples, door) = hub.drain(world)
            val out = engine.update(CoachInput(now = world, gear = Gear.DRIVE, speedKph = 100f, samples = samples, doorOpened = door))
            if (out.style.baselineReady) return tick / 60.0
        }
        return null
    }

    @Test
    fun steeringSamplesInADemoShareTheEngineClock() {
        // 전제: 한 시계로 찍으면 준비 5분 + 기준선 20분 뒤 운전 패턴 기준선이 선다 (실제 세계, 또는 세계 시각으로 찍은 시연)
        assertNotNull(baselineReadyAfterMin(offsetMs = 0, stampWithWall = true))
        assertNotNull(baselineReadyAfterMin(offsetMs = 13 * 3_600_000L, stampWithWall = false))
        // OPEN: 시연은 시각이 실제와 다르다(도심 급해 23:10, 귀성길 14:00, 오후 졸음 15:00). CarSignals가 조향 표본을 실제 시각으로 찍어서
        // 한 틱의 표본 목록에 두 시계가 섞인다(조향 표본 = 실제 시각, 틱 표본·입력 시각 = 가상 시각). DrivingStyleAnalyzer는 매 표본을
        // 시계 역행이나 긴 공백으로 보고 창을 버려 기준선이 끝내 서지 않는다. QuietTracker의 '큰 조향 뒤 10초' 판정도 같은 이유로 어긋난다.
        // (배속 시연(7.2배·10배)은 표본 간격이 가상 7~10초라 시각을 맞춰도 기준선이 서지 않는다: SPEC 7장 시연 2의 '조향 흔들림 재생'은
        // 피로 게이지에 닿지 않는다. 이 테스트는 배속이 없는 경우로 시계 섞임만 가린다.)
        assertNotNull(
            "시각만 다른 시연에서 실제 40분을 정속으로 달려도 운전 패턴 기준선이 서지 않는다",
            baselineReadyAfterMin(offsetMs = 13 * 3_600_000L, stampWithWall = true),
        )
    }
}
