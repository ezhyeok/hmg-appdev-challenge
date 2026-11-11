package dev.shimpyo.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.shimpyo.app.coach.UiCommand

/**
 * 화면이 바깥(서비스, 설정 저장소, 액티비티)과 주고받는 통로.
 * 실제 구현은 [rememberAndroidHost](Platform.kt), 미리보기·시험은 [PreviewHost].
 * 화면 코드는 이 인터페이스만 보므로 안드로이드 없이 그려 볼 수 있다.
 */
@Stable
interface AppHost {
    val prefs: PrefsState
    /** 서비스로 명령을 보낸다 ([dev.shimpyo.app.coach.CoachStore.send]) */
    fun send(command: UiCommand)
    /** 정차 기록(stops.json)과 학습 상태(memory.json)를 지운다. 둘 다 지웠으면 true */
    fun clearRecords(): Boolean
    /** 최상위 화면의 뒤로: 앱을 끄지 않고 뒤로 보낸다 (CCP 가이드 필수 항목) */
    fun moveToBack()
    val versionName: String
}

/** 화면이 읽고 쓰는 사용자 설정. 값을 바꾸면 화면이 다시 그려진다 */
@Stable
interface PrefsState {
    /** 피로가 오르면 묻지 않고 외기로 바꿀지 */
    val autoFreshAir: Boolean
    val voiceEnabled: Boolean
    /** 지난밤 수면 시간. 0이면 모름 */
    val driverSleepHours: Float
    /** 건강 관련 탑승자 정보(임신부, 과민성 방광, 과민성 장) 처리 동의 */
    val healthConsent: Boolean
    fun updateAutoFreshAir(value: Boolean)
    fun updateVoiceEnabled(value: Boolean)
    fun updateDriverSleepHours(value: Float)
    fun updateHealthConsent(value: Boolean)
}

/** 메모리에만 두는 설정 (미리보기·시험용) */
class MemoryPrefs(
    autoFreshAir: Boolean = false,
    voiceEnabled: Boolean = true,
    driverSleepHours: Float = 0f,
    healthConsent: Boolean = false,
) : PrefsState {
    override var autoFreshAir by mutableStateOf(autoFreshAir)
        private set
    override var voiceEnabled by mutableStateOf(voiceEnabled)
        private set
    override var driverSleepHours by mutableStateOf(driverSleepHours)
        private set
    override var healthConsent by mutableStateOf(healthConsent)
        private set

    override fun updateAutoFreshAir(value: Boolean) { autoFreshAir = value }
    override fun updateVoiceEnabled(value: Boolean) { voiceEnabled = value }
    override fun updateDriverSleepHours(value: Float) { driverSleepHours = value }
    override fun updateHealthConsent(value: Boolean) { healthConsent = value }
}

/** 미리보기용 호스트. 명령은 버린다 */
class PreviewHost(override val prefs: PrefsState = MemoryPrefs()) : AppHost {
    override fun send(command: UiCommand) = Unit
    override fun clearRecords(): Boolean = true
    override fun moveToBack() = Unit
    override val versionName: String = "0.1"
}
