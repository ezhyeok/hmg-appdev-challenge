package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.CoreRig
import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.KID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * '자는 중' 표시를 코어 끝까지 (U4): 화면 명령·말 → CoachUiState.asleepOccupantIds → 엔진 입력(CoachInput.asleepOccupantIds).
 * 규칙은 AsleepMarksTest. 엔진은 밤(20~07시)에만 잠든 아이의 게이지에 여유를 두므로 밤 시각에서 돌린다.
 */
class CoachCoreAsleepTest {
    private val min = 60_000L
    /** 2026-11-12 22:00 KST */
    private val night = 1_762_909_200_000L + 12 * 60 * min

    private fun nightRig(scope: kotlinx.coroutines.test.TestScope, wallBase: Long = night): CoreRig {
        val rig = CoreRig(scope, settings = FakeSettings(listOf(DRIVER, KID)), wallBase = wallBase)
        rig.drive(100f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(5_000)
        return rig
    }

    private fun CoreRig.asleepReason() = state.restroom?.reasons.orEmpty().any { it.code == "RESTROOM_ASLEEP" }

    @Test
    fun aChildMarkedAsleepOnTheScreenReachesTheEngineAndTheGaugeSaysSo() = runTest {
        val rig = nightRig(this)
        assertTrue(rig.state.asleepOccupantIds.isEmpty())
        rig.run(10 * min)
        val awake = checkNotNull(rig.state.restroom?.minutes)
        assertFalse(rig.asleepReason())
        rig.command(UiCommand.SetAsleep("kid", true))
        assertEquals("명령에 바로 올린다", setOf("kid"), rig.state.asleepOccupantIds)
        rig.run(30 * min)
        assertTrue("엔진이 잠든 아이로 센다: ${rig.state.restroom?.reasons?.map { it.code }}", rig.asleepReason())
        assertEquals("아이의 게이지다", "kid", rig.state.restroom?.drivenBy)
        assertTrue("30분 동안 게이지는 30분보다 덜 줄었다 ($awake → ${rig.state.restroom?.minutes})", rig.state.restroom!!.minutes!! > awake - 30)

        rig.command(UiCommand.SetAsleep("kid", false))
        assertTrue(rig.state.asleepOccupantIds.isEmpty())
        rig.run(2_000)
        assertFalse("깨면 이유 표시도 내려간다", rig.asleepReason())
    }

    @Test
    fun onlyAChildCanBeMarked() = runTest {
        val rig = nightRig(this)
        rig.command(UiCommand.SetAsleep("driver", true))
        rig.command(UiCommand.SetAsleep("nobody", true))
        assertTrue(rig.state.asleepOccupantIds.isEmpty())
    }

    @Test
    fun sayingTheChildFellAsleepMarksTheOnlyChildAndSayingSheWokeClearsIt() = runTest {
        val rig = nightRig(this)
        rig.voice.listens += listOf("아이 잠들었어", "깼어")
        rig.command(UiCommand.Listen)
        rig.run(4_000)
        assertEquals(setOf("kid"), rig.state.asleepOccupantIds)
        assertTrue("무엇을 했는지 답한다: ${rig.voice.said}", rig.voice.said.any { "여유를 조금 더" in it })
        rig.command(UiCommand.Listen)
        rig.run(4_000)
        assertTrue(rig.state.asleepOccupantIds.isEmpty())
        assertTrue(rig.voice.said.any { "깬 것으로" in it })
        assertTrue("자는 중 표시는 엔진에 묻는 말이 아니다: ${rig.voice.asked}", rig.voice.asked.isEmpty())
    }

    @Test
    fun aRestroomStopClearsTheMarkWhileStillParked() = runTest {
        val rig = nightRig(this)
        rig.command(UiCommand.SetAsleep("kid", true))
        rig.park()
        rig.run(10_000)
        rig.hub.markDoorOpened()
        rig.run(2 * min)
        assertEquals("3분 전에는 그대로", setOf("kid"), rig.state.asleepOccupantIds)
        rig.run(60_000)
        assertTrue("문 열림 + 3분: 화장실 정차다 (엔진도 이때 시계를 돌린다)", rig.state.asleepOccupantIds.isEmpty())
    }

    @Test
    fun theMarkIsClearedWhenTheOccupantLeavesAndWhenTheWorldSwitches() = runTest {
        val rig = nightRig(this)
        rig.command(UiCommand.SetAsleep("kid", true))
        rig.command(UiCommand.SetOccupants(listOf(DRIVER)))
        assertTrue("내린 사람의 표시는 남지 않는다", rig.state.asleepOccupantIds.isEmpty())

        rig.command(UiCommand.SetOccupants(listOf(DRIVER, KID)))
        rig.command(UiCommand.SetAsleep("kid", true))
        rig.command(UiCommand.Demo("holiday"))
        assertTrue("실제 세계의 표시는 시연에 넘어가지 않는다", rig.state.asleepOccupantIds.isEmpty())
        rig.command(UiCommand.SetAsleep("kid", true)) // 시연의 아이
        assertEquals(setOf("kid"), rig.state.asleepOccupantIds)
        rig.command(UiCommand.Demo(null))
        assertTrue("시연의 표시도 실제 세계에 넘어오지 않는다", rig.state.asleepOccupantIds.isEmpty())
    }

    @Test
    fun theMarkEndsAtSevenInTheMorning() = runTest {
        // 2026-11-13 06:59:00 KST
        val rig = nightRig(this, wallBase = 1_762_909_200_000L + (20 * 60 + 59) * min)
        rig.command(UiCommand.SetAsleep("kid", true))
        rig.run(30_000)
        assertEquals(setOf("kid"), rig.state.asleepOccupantIds)
        rig.run(40_000)
        assertTrue("아침 7시가 지나면 풀린다", rig.state.asleepOccupantIds.isEmpty())
    }
}
