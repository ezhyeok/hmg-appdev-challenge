package dev.shimpyo.app.ui.occupants

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import dev.shimpyo.app.ui.Labels.isHealth
import dev.shimpyo.app.ui.PrefsState
import dev.shimpyo.app.ui.components.LocalOverlays
import dev.shimpyo.app.ui.components.PleosSwitch
import dev.shimpyo.app.ui.components.PopupSpec
import dev.shimpyo.app.ui.components.SettingRow
import dev.shimpyo.app.ui.components.ShimpyoIcons
import dev.shimpyo.engine.Occupant

/*
 * 건강 관련 탑승자 정보(임신부, 과민성 방광, 과민성 장)는 개인정보 보호법 제23조의 민감정보일 수 있다.
 * 그래서 이 유형을 더하기 전에 따로 동의를 받는다. 동의는 SharedPreferences "shimpyo"의 "healthConsent"에 둔다
 * (Platform.kt). 동의를 철회하면 그 유형의 탑승자를 목록에서 지운다.
 */

const val HEALTH_CONSENT_TITLE = "건강 관련 정보 처리에 동의합니다"
const val HEALTH_CONSENT_DETAIL = "이 정보는 기기에만 저장되고 서버로 보내지 않습니다. 언제든 지울 수 있습니다."
const val HEALTH_CONSENT_TEXT = "$HEALTH_CONSENT_TITLE — $HEALTH_CONSENT_DETAIL"

/**
 * 동의 스위치 행. 켤 때는 확인 팝업으로 명시적으로 동의를 받고,
 * 끌 때 건강 관련 탑승자가 있으면 지운다는 확인을 받는다.
 * 철회 확인은 누르는 순간의 탑승자 목록으로 지운다: 팝업이 떠 있는 동안 음성으로 탑승자가 바뀌어도 그 변경을 되돌리지 않게
 * (UI 감사 #23: 팝업을 연 때의 목록으로 덮어썼다).
 */
@Composable
fun HealthConsentRow(prefs: PrefsState, occupants: List<Occupant>, onSetOccupants: (List<Occupant>) -> Unit) {
    val overlays = LocalOverlays.current
    val latest = rememberUpdatedState(occupants)
    SettingRow(
        title = HEALTH_CONSENT_TITLE,
        subtitle = HEALTH_CONSENT_DETAIL,
        icon = ShimpyoIcons.Lock,
        onClick = {
            if (!prefs.healthConsent) {
                overlays.confirm(
                    PopupSpec(
                        title = "건강 관련 정보 처리 동의",
                        description = "$HEALTH_CONSENT_TEXT\n\n임신부·과민성 방광·과민성 장 탑승자를 고르면 화장실 기준 시간을 맞춥니다.",
                        confirmText = "동의",
                        onConfirm = { prefs.updateHealthConsent(true) },
                    ),
                )
            } else if (occupants.any { it.type.isHealth }) {
                overlays.confirm(
                    PopupSpec(
                        title = "동의를 철회할까요?",
                        description = "임신부·민감 탑승자 정보를 목록에서 지워요.",
                        confirmText = "철회하고 지우기",
                        onConfirm = {
                            prefs.updateHealthConsent(false)
                            onSetOccupants(withoutHealth(latest.value))
                        },
                    ),
                )
            } else {
                prefs.updateHealthConsent(false)
            }
        },
        trailing = { PleosSwitch(prefs.healthConsent) },
    )
}

/** RED 단계 자리 표시: 팝업을 연 때의 목록으로 지운다 (UI 감사 #23의 옛 동작) */
fun onHealthConsentTap(prefs: PrefsState, overlays: dev.shimpyo.app.ui.components.OverlayState, occupants: () -> List<Occupant>, onSetOccupants: (List<Occupant>) -> Unit) {
    val atOpen = occupants()
    if (!prefs.healthConsent) {
        prefs.updateHealthConsent(true)
    } else if (atOpen.any { it.type.isHealth }) {
        overlays.confirm(
            PopupSpec(
                title = "동의를 철회할까요?",
                confirmText = "철회하고 지우기",
                onConfirm = {
                    prefs.updateHealthConsent(false)
                    onSetOccupants(withoutHealth(atOpen))
                },
            ),
        )
    } else {
        prefs.updateHealthConsent(false)
    }
}

/** 동의 철회 때 남길 탑승자 */
fun withoutHealth(occupants: List<Occupant>): List<Occupant> = occupants.filterNot { it.type.isHealth }
