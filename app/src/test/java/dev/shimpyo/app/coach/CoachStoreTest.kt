package dev.shimpyo.app.coach

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 서비스가 명령을 받기 시작하기 전에 보낸 명령이 사라지지 않아야 한다.
 * 회귀: 첫 실행 때 `--es demo holiday`가 서비스의 collect보다 먼저 와서 버려졌다(SharedFlow, 2026-11-11).
 */
class CoachStoreTest {

    @Test
    fun commandsSentBeforeCollectorStartsAreDelivered() = runBlocking {
        CoachStore.send(UiCommand.Demo("holiday"))
        CoachStore.send(UiCommand.DismissUrgent)
        val got = withTimeout(2_000) { CoachStore.commands.take(2).toList() }
        assertEquals(listOf(UiCommand.Demo("holiday"), UiCommand.DismissUrgent), got)
    }

    @Test
    fun eachCommandIsDeliveredOnce() = runBlocking {
        CoachStore.send(UiCommand.Demo("urgent"))
        val first = withTimeout(2_000) { CoachStore.commands.take(1).toList() }
        assertEquals(listOf(UiCommand.Demo("urgent")), first)
        CoachStore.send(UiCommand.Demo(null))
        val next = withTimeout(2_000) { CoachStore.commands.take(1).toList() }
        assertEquals(listOf(UiCommand.Demo(null)), next)
    }
}
