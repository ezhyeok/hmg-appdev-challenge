package dev.shimpyo.coach.closure

import dev.shimpyo.coach.SignalHub
import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.TripProfile
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.sin

/**
 * 독립 리뷰(2026-11-12, R-F6): 시연에서 조향 표본의 시각이 엔진 시각과 다른 시계였다.
 * 예전 앱 배선(CarSignals)은 조향 표본을 실제 시각(wall::now)으로 찍어 SignalHub에 넣었고, 코어의 틱은 SignalHub.drain(t)와
 * CoachInput.now에 세계 시각(시연이면 가상 시각)을 썼다. 한 틱의 표본 목록에 두 시계가 섞여 DrivingStyleAnalyzer가 매 표본을
 * 시계 역행이나 긴 공백으로 보고 창을 버렸고, 기준선이 끝내 서지 않았다. QuietTracker의 '큰 조향 뒤 10초' 판정도 같은 이유로 어긋났다.
 *
 * 지금은 수집 쪽이 시각을 찍지 못한다: 값만 넣고(SignalHub.addSteering), 허브가 받은 때를 경과 시계로 적어 두었다가 drain이
 * 그 틱의 세계 시각으로 바꾼다. 이 테스트는 앱 배선을 그대로 흉내 낸다 (허브 단위 규칙은 SignalHubTest).
 * (배속 시연(7.2배·10배)은 표본 간격이 가상 7~10초라 시각을 맞춰도 기준선이 서지 않는다: SPEC 7장 시연 2의 '조향 흔들림 재생'은
 * 피로 게이지에 닿지 않는다. 이 테스트는 배속이 없는 경우로 시계 섞임만 가린다.)
 */
class ReviewSampleClockTest {

    /**
     * 실제 [minutes]분 동안 시속 100km 정속 주행. 기준선이 준비된 실제 시각(분)을 돌려준다 (안 되면 null).
     * [offsetMs]: 세계 시각 − 실제 시각 (시연이면 0이 아니다). [elapsedBase]: 경과 시계의 시작값 (부팅 뒤 시간이라 실제 시각과 무관하다)
     */
    private fun baselineReadyAfterMin(offsetMs: Long, elapsedBase: Long = 0L, minutes: Int = 40): Double? {
        val wall0 = 1_762_909_200_000L
        var elapsedNow = elapsedBase
        val engine = CoachEngine(profile = TripProfile(listOf(Occupant("driver", OccupantType.ADULT)), tripStart = wall0 + offsetMs))
        val hub = SignalHub { elapsedNow }
        hub.gear = Gear.DRIVE
        hub.speedKph = 100f
        engine.update(CoachInput(now = wall0 + offsetMs, gear = Gear.DRIVE, speedKph = 100f))
        for (tick in 1..minutes * 60) {
            val wall = wall0 + tick * 1000L
            val world = wall + offsetMs // 배속 없는 시연 (시각만 다르다)
            for (ms in listOf(500L, 1000L)) { // 조향 리스너: 0.5초마다 값이 온다
                val at = wall - 1000 + ms
                elapsedNow = elapsedBase + (at - wall0)
                hub.addSteering((2.0 * sin(at / 700.0)).toFloat())
            }
            elapsedNow = elapsedBase + (wall - wall0)
            val (samples, door) = hub.drain(world)
            val out = engine.update(CoachInput(now = world, gear = Gear.DRIVE, speedKph = 100f, samples = samples, doorOpened = door))
            if (out.style.baselineReady) return tick / 60.0
        }
        return null
    }

    @Test
    fun steeringSamplesInADemoShareTheEngineClock() {
        // 한 시계로 찍히면 준비 5분 + 기준선 20분 뒤 운전 패턴 기준선이 선다: 실제 세계
        assertNotNull(baselineReadyAfterMin(offsetMs = 0))
        // 시연은 시각이 실제와 다르다 (도심 급해 23:10, 귀성길 14:00, 오후 졸음 15:00). 예전에는 여기서 기준선이 서지 않았다
        assertNotNull(
            "시각만 다른 시연에서 실제 40분을 정속으로 달려도 운전 패턴 기준선이 서지 않는다",
            baselineReadyAfterMin(offsetMs = 13 * 3_600_000L),
        )
        // 경과 시계가 어디서 시작하든 같다 (부팅 뒤 시간은 실제 시각과도 세계 시각과도 무관하다)
        assertNotNull(baselineReadyAfterMin(offsetMs = 13 * 3_600_000L, elapsedBase = 5_000_000L))
        assertNotNull(baselineReadyAfterMin(offsetMs = -9 * 3_600_000L, elapsedBase = 86_400_000L * 30))
    }
}
