package dev.shimpyo.app.ui

/**
 * 실행 중 권한(차속·위치)을 언제 물을지. 시스템 권한 창은 화면을 덮으므로 주행 중(모를 때 포함)에는 묻지 않고,
 * 정차 중임을 실제로 읽었을 때 한 번 묻는다. 액티비티가 다시 만들어져도(창 분할·야간 모드) 다시 묻지 않는다
 * (UI 감사 #14: 다시 만들어질 때마다, 주행 중에도 권한 창을 띄웠다). 코치 서비스는 권한과 관계없이 먼저 시작하고,
 * 나중에 권한이 생기면 UiCommand.PermissionsChanged로 신호를 다시 연결하게 한다.
 */
object PermissionPolicy {
    fun shouldAsk(alreadyAsked: Boolean, missing: Collection<String>, restriction: DriveRestriction): Boolean =
        !alreadyAsked && missing.isNotEmpty() && restriction.known && !restriction.driving

    /** 빠져 있던 권한 중 새로 허용된 것이 있는지 (권한 창 결과, 또는 설정 앱에서 허용하고 돌아왔을 때) */
    fun newlyGranted(missingBefore: Collection<String>, missingNow: Collection<String>): Boolean =
        missingBefore.any { it !in missingNow }
}
