package dev.shimpyo.app.coach

import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.UiCommand
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/** 서비스와 화면이 공유하는 상태 저장소. 프로세스 안에서만 쓴다 */
object CoachStore {
    private val _state = MutableStateFlow(CoachUiState())
    val state: StateFlow<CoachUiState> = _state.asStateFlow()

    /**
     * 화면·인텐트가 서비스에 보내는 명령. 받는 쪽은 CoachService 하나다.
     * Channel이라 서비스가 받기 시작하기 전에 보낸 명령도 버퍼에 남는다. SharedFlow(replay 0)는 구독자가 없을 때
     * 보낸 값을 버려서, 첫 실행 때 `--es demo` 명령이 서비스의 collect보다 먼저 와서 사라졌다(2026-11-11 에뮬레이터).
     * CoachStoreTest가 이 동작을 지킨다.
     */
    private val _commands = Channel<UiCommand>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val commands: Flow<UiCommand> = _commands.receiveAsFlow()

    fun update(transform: CoachUiState.() -> CoachUiState) = _state.update(transform)

    fun send(command: UiCommand) {
        _commands.trySend(command)
    }
}
