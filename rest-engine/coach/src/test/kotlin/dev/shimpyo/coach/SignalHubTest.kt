package dev.shimpyo.coach

import dev.shimpyo.engine.DrivingSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 표본의 시각은 허브만 정한다 (리뷰 R-F6): 받은 때를 경과 시계로 적고, drain이 그 틱의 엔진 시각으로 바꾼다.
 * 수집 쪽(앱 CarSignals)은 시계를 들고 오지 못한다. 엔진까지 이은 시나리오는 closure/ReviewSampleClockTest.
 */
class SignalHubTest {
    private var el = 5_000_000L
    private val hub = SignalHub { el }
    private val wall = 1_762_909_200_000L

    private fun times(samples: List<DrivingSample>) = samples.map { it.t }

    @Test
    fun samplesAreStampedOnTheTickClockByTheirAge() {
        // 시연: 세계 시각이 실제보다 13시간 앞선다. 표본은 그 세계 시각으로 나온다
        val world = wall + 13 * 3_600_000L
        hub.drain(world)
        el += 400
        hub.addSteering(1.5f)
        el += 300
        hub.addSteering(-0.5f)
        el += 300
        val (samples, _) = hub.drain(world + 1_000)
        assertEquals(listOf(world + 400, world + 700, world + 1_000), times(samples))
        assertEquals(listOf(1.5f, -0.5f, -0.5f), samples.map { it.steeringDeg })
    }

    @Test
    fun theSpeedAtTheMomentOfTheSampleIsKept() {
        hub.speedKph = 80f
        hub.addSteering(2f)
        hub.speedKph = 95f
        el += 1_000
        val (samples, _) = hub.drain(wall)
        assertEquals(listOf(80f, 95f), samples.map { it.speedKph })
    }

    @Test
    fun theLastSampleIsTheLatestValuesAtTheTick() {
        hub.speedKph = 60f
        hub.brake = true
        hub.addSteering(3f)
        el += 1_000
        val last = hub.drain(wall).first.last()
        assertEquals(DrivingSample(t = wall, speedKph = 60f, steeringDeg = 3f, brake = true), last)
        // 다음 틱: 새 이벤트가 없어도 최신값 표본 하나는 나온다
        el += 1_000
        assertEquals(listOf(wall + 1_000), times(hub.drain(wall + 1_000).first))
    }

    @Test
    fun samplesNeverPrecedeThePreviousTick() {
        // 세계 시각이 경과 시계보다 느리게 간 틱 (시계 보정): 지난 틱 직후에 받은 표본이 지난 틱 앞으로 가지 않는다.
        // 엔진은 지난 표본 이하의 시각을 버린다 (DrivingStyleAnalyzer: 중복·순서 바뀐 표본)
        hub.drain(wall)
        el += 1
        hub.addSteering(1f)
        el += 999
        val (samples, _) = hub.drain(wall + 990)
        assertEquals(listOf(wall + 1, wall + 990), times(samples))
    }

    @Test
    fun aTickClockThatWentBackIsFollowedNotFought() {
        // 시연을 끄면 세계 시각이 뒤로 간다. 그 틱의 표본은 새 세계 시각으로 나온다 (지난 틱에 맞추지 않는다)
        val demo = wall + 13 * 3_600_000L
        hub.drain(demo)
        el += 500
        hub.addSteering(1f)
        el += 500
        assertEquals(listOf(wall + 500, wall + 1_000), times(hub.drain(wall + 1_000).first))
    }

    @Test
    fun inAnAcceleratedDemoSamplesStayInsideTheirTick() {
        // 7.2배속 시연: 실제 1초에 세계 시각 7.2초. 표본은 지난 틱과 이번 틱 사이에 순서대로 놓인다
        var world = wall
        hub.drain(world)
        repeat(5) {
            el += 500
            hub.addSteering(it.toFloat())
            el += 500
            val before = world
            world += 7_200
            val t = times(hub.drain(world).first)
            assertEquals(listOf(world - 500, world), t)
            assertTrue(t.all { it > before && it <= world })
        }
    }

    @Test
    fun aSampleIsNeverLaterThanItsTick() {
        hub.addSteering(1f)
        el -= 50 // 경과 시계는 뒤로 가지 않지만, 가더라도 표본이 틱보다 뒤로 가지 않는다
        assertEquals(listOf(wall, wall), times(hub.drain(wall).first))
    }

    @Test
    fun anUnreadableSteeringValueClearsTheLatestValueAndAddsNoSample() {
        hub.addSteering(4f)
        hub.addSteering(null)
        el += 1_000
        val (samples, _) = hub.drain(wall)
        assertEquals(2, samples.size)
        assertEquals(4f, samples[0].steeringDeg)
        assertNull(samples[1].steeringDeg)
        assertNull(hub.steeringDeg)
    }

    @Test
    fun theBufferIsBoundedAndEmptiedByDrain() {
        repeat(3_000) { hub.addSteering(it.toFloat()) }
        assertEquals(2_000 + 1, hub.drain(wall).first.size)
        assertEquals(1, hub.drain(wall + 1_000).first.size)
    }

    @Test
    fun aDoorOpeningIsReportedOnce() {
        hub.markDoorOpened()
        assertTrue(hub.drain(wall).second)
        assertFalse(hub.drain(wall + 1_000).second)
    }
}
