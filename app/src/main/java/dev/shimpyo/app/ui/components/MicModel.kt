package dev.shimpyo.app.ui.components

import androidx.compose.runtime.Immutable
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.VoiceState

/*
 * 마이크 버튼 (screens.md 0.4). 말하거나 듣거나 생각하는 중에 누르면 멈추고(UiCommand.StopVoice), 쉬는 중이면 듣는다
 * (UiCommand.Listen). 듣기를 쓸 수 없으면 누를 때 이유를 알려 준다(예전에는 조용히 아무 일도 없었다).
 * "음성 안내"(설정, TTS)를 꺼도 듣기는 된다: 꺼진 것은 말하기뿐이다 (UI 감사 #12: '음성 꺼짐'이라고 보이면서 들었다).
 */

enum class MicAction { LISTEN, STOP, EXPLAIN }

enum class MicIcon { MIC, MIC_ACTIVE, SPEAKER, MIC_OFF }

/**
 * @property status 버튼 옆 상태 글자. null이면 숨긴다
 * @property active 듣는 중 (고정 링, 움직이지 않음)
 * @property explain [MicAction.EXPLAIN]일 때 토스트 문구
 */
@Immutable
data class MicModel(
    val status: String?,
    val icon: MicIcon,
    val action: MicAction,
    val active: Boolean,
    val description: String,
    val explain: String? = null,
)

fun micModel(voice: VoiceState, ttsAvailable: Boolean, sttAvailable: Boolean, voiceGuidance: Boolean): MicModel {
    val speaks = ttsAvailable && voiceGuidance
    return when {
        voice == VoiceState.LISTENING -> MicModel("듣는 중", MicIcon.MIC_ACTIVE, MicAction.STOP, active = true, description = "듣기 멈추기")
        voice == VoiceState.SPEAKING -> MicModel("말하는 중", MicIcon.SPEAKER, MicAction.STOP, active = false, description = "말하기 멈추기")
        voice == VoiceState.THINKING -> MicModel("생각하는 중", MicIcon.MIC, MicAction.STOP, active = false, description = "멈추기")
        // 인식을 쓸 수 없을 때만 막는다. 음성 상태 UNAVAILABLE만으로는 막지 않는다: VoiceAgent는 TTS만 안 붙어도 UNAVAILABLE을
        // 올리는데 코어는 STT만 되면 듣는다. 예전에는 그 조합에서 사선 마이크를 보이고 Listen을 보내지 않았다 (닫힘 검증 UI-12)
        !sttAvailable -> MicModel(
            status = if (speaks) "듣기 불가" else "음성 불가",
            icon = MicIcon.MIC_OFF,
            action = MicAction.EXPLAIN,
            active = false,
            description = "음성 인식을 쓸 수 없음",
            explain = "지금은 음성 인식을 쓸 수 없어요",
        )
        // 듣기는 되지만 대답을 소리로 하지 않는다 (설정에서 껐거나 TTS 연결 실패)
        !speaks -> MicModel("음성 안내 꺼짐", MicIcon.MIC, MicAction.LISTEN, active = false, description = "말하기 (대답은 화면으로)")
        else -> MicModel(null, MicIcon.MIC, MicAction.LISTEN, active = false, description = "말하기")
    }
}

/**
 * 탑승자 화면의 [말로 설정] (UiCommand.DescribeOccupants).
 * @property reason 쓸 수 없는 이유. null이면 쓸 수 있다. 버튼 아래에 보이고, 눌러도 토스트로 알린다
 */
@Immutable
data class DescribeModel(val label: String, val action: MicAction, val reason: String? = null) {
    val available: Boolean get() = reason == null
}

/**
 * [말로 설정]은 코치가 "누가 함께 타나요?"라고 묻고(TTS) 듣는다(STT): 음성 안내가 켜져 있고 둘 다 돼야 한다.
 * 예전에는 안 될 때 눌러도 조용히 아무 일도 없었다 (닫힘 검증 UI-12). 묻거나 듣거나 해석하는 중에 누르면 멈춘다 (마이크와 같은 규칙).
 */
fun describeModel(voice: VoiceState, ttsAvailable: Boolean, sttAvailable: Boolean, voiceGuidance: Boolean): DescribeModel = when {
    voice == VoiceState.LISTENING -> DescribeModel("듣는 중", MicAction.STOP)
    voice == VoiceState.SPEAKING -> DescribeModel("말하는 중", MicAction.STOP)
    voice == VoiceState.THINKING -> DescribeModel("생각하는 중", MicAction.STOP)
    !sttAvailable -> DescribeModel(DESCRIBE_LABEL, MicAction.EXPLAIN, "지금은 음성 인식을 쓸 수 없어요")
    !voiceGuidance -> DescribeModel(DESCRIBE_LABEL, MicAction.EXPLAIN, "설정에서 음성 안내를 켜면 말로 설정할 수 있어요")
    !ttsAvailable -> DescribeModel(DESCRIBE_LABEL, MicAction.EXPLAIN, "지금은 음성 안내를 쓸 수 없어요")
    else -> DescribeModel(DESCRIBE_LABEL, MicAction.LISTEN)
}

private const val DESCRIBE_LABEL = "말로 설정"

fun micModel(state: CoachUiState, voiceGuidance: Boolean): MicModel =
    micModel(state.voice, state.ttsAvailable, state.sttAvailable, voiceGuidance)
