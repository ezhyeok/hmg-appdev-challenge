package dev.shimpyo.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.shimpyo.app.BuildConfig
import dev.shimpyo.app.ShimpyoApp
import dev.shimpyo.app.coach.CoachStore
import dev.shimpyo.app.coach.UiCommand
import dev.shimpyo.app.data.Settings
import java.io.File

/*
 * 안드로이드에 기대는 코드는 이 파일에만 둔다 (설정 저장소, 파일, 액티비티, 뒤로 키).
 * 나머지 화면 코드는 AppHost만 보므로 미리보기와 데스크톱 검사에서 그대로 그릴 수 있다.
 */

/** 실제 앱의 [AppHost]. 설정은 ShimpyoApp.settings, 건강 정보 동의는 SharedPreferences "shimpyo"에 둔다 */
@Composable
fun rememberAndroidHost(): AppHost {
    val context = LocalContext.current
    return remember(context) {
        val app = context.applicationContext as ShimpyoApp
        AndroidHost(context.findActivity(), app)
    }
}

/** 시스템 뒤로(CCP Back 포함) */
@Composable
fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit) = BackHandler(enabled, onBack)

private class AndroidHost(private val activity: Activity?, private val app: ShimpyoApp) : AppHost {
    override val prefs: PrefsState = AndroidPrefs(app.settings, app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    override fun send(command: UiCommand) = CoachStore.send(command)

    override fun clearRecords(): Boolean {
        // 서비스가 엔진의 학습 상태까지 새로 만들고 파일을 지운다 (화면에서 파일만 지우면 엔진이 다시 써 버린다)
        CoachStore.send(UiCommand.ClearRecords)
        return true
    }

    override fun moveToBack() {
        activity?.moveTaskToBack(true)
    }

    override val versionName: String = BuildConfig.VERSION_NAME
}

private class AndroidPrefs(private val settings: Settings, private val prefs: SharedPreferences) : PrefsState {
    override var autoFreshAir by mutableStateOf(settings.autoFreshAir)
        private set
    override var voiceEnabled by mutableStateOf(settings.voiceEnabled)
        private set
    override var driverSleepHours by mutableStateOf(settings.driverSleepHours)
        private set
    override var healthConsent by mutableStateOf(prefs.getBoolean(KEY_HEALTH_CONSENT, false))
        private set

    override fun updateAutoFreshAir(value: Boolean) {
        settings.autoFreshAir = value
        autoFreshAir = value
    }

    override fun updateVoiceEnabled(value: Boolean) {
        settings.voiceEnabled = value
        voiceEnabled = value
    }

    override fun updateDriverSleepHours(value: Float) {
        settings.driverSleepHours = value
        driverSleepHours = value
        CoachStore.send(UiCommand.ProfileChanged)
    }

    override fun updateHealthConsent(value: Boolean) {
        prefs.edit().putBoolean(KEY_HEALTH_CONSENT, value).apply()
        healthConsent = value
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val PREFS_NAME = "shimpyo"
private const val KEY_HEALTH_CONSENT = "healthConsent"
