package dev.shimpyo.app.ui

import androidx.compose.runtime.Immutable
import dev.hmg.pleoskit.UxRestrictionFlags
import dev.hmg.pleoskit.UxRestrictions
import dev.shimpyo.app.ui.theme.DriveLimits

/**
 * 화면이 지키는 주행 제한. 화면은 [UxRestrictions]를 서비스 없이 스스로 구독한다(Platform.kt의 프로세스 단위 구독).
 * 서비스가 늦게 뜨거나, 권한 창이 떠 있거나, 서비스가 죽어도 화면이 제한을 안다 (UI 감사 #2).
 *
 * @property driving 주행 화면과 급해 결과만 보인다 (모르면 true)
 * @property known 플랫폼 값을 실제로 읽었는지 (모를 때 토스트 같은 안내는 하지 않는다)
 * @property maxChars 주행 화면 한 문구 최대 글자 수 (LIMIT_STRING_LENGTH)
 * @property maxItems 주행 중 한 번에 보이는 결과 수 (LIMIT_CONTENT, 급해 결과)
 */
@Immutable
data class DriveRestriction(
    val driving: Boolean,
    val known: Boolean,
    val maxChars: Int = DriveLimits.MAX_STRING_LENGTH,
    val maxItems: Int = DriveLimits.MAX_URGENT_RESULTS,
) {
    companion object {
        val UNKNOWN = DriveRestriction(driving = true, known = false)
        val PARKED = DriveRestriction(driving = false, known = true)
    }
}

/**
 * 화면의 구독 값([ux])만 믿는다. 모르면 제한이다 (fail closed). 서비스가 읽은 값(state.drivingRestricted)은 쓰지 않는다:
 * 서비스가 다시 뜰 때 새 인스턴스가 자기 구독을 내기 전까지는 죽은 인스턴스의 옛 false가 CoachStore에 남아 있어서
 * 그 틈에 정차 화면이 열릴 수 있다 (재검토 #12). 화면 구독은 실패하면 다시 연결한다(UxRestrictionWatcher).
 * 기능별 비트는 주행 중일 때만 본다: 문구 길이(LIMIT_STRING_LENGTH)와 항목 수(LIMIT_CONTENT)는 플랫폼 값이 앱 상한보다 작을 때만 줄인다.
 * NO_SETUP·NO_KEYBOARD·NO_VIDEO는 주행 중에 정차 화면을 모두 닫는 것으로 이미 지킨다.
 */
fun driveRestriction(ux: UxRestrictions): DriveRestriction = when (ux) {
    is UxRestrictions.Known -> {
        if (!ux.requiresDistractionOptimization) {
            DriveRestriction.PARKED
        } else {
            DriveRestriction(
                driving = true,
                known = true,
                maxChars = if (ux.has(UxRestrictionFlags.LIMIT_STRING_LENGTH) && ux.maxStringLength > 0) {
                    minOf(ux.maxStringLength, DriveLimits.MAX_STRING_LENGTH)
                } else {
                    DriveLimits.MAX_STRING_LENGTH
                },
                maxItems = if (ux.has(UxRestrictionFlags.LIMIT_CONTENT) && ux.maxContentItems > 0) {
                    minOf(ux.maxContentItems, DriveLimits.MAX_URGENT_RESULTS)
                } else {
                    DriveLimits.MAX_URGENT_RESULTS
                },
            )
        }
    }
    is UxRestrictions.Unknown -> DriveRestriction.UNKNOWN
}

/** 주행 중 문구 길이 제한. 넘치면 말줄임표로 자른다 */
fun limitChars(text: String, max: Int): String = if (text.length <= max || max < 2) text else text.take(max - 1) + "…"
