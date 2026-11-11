package dev.shimpyo.app.ui

import dev.shimpyo.coach.DemoScenario

/**
 * 실행 인텐트에서 코치 서비스로 넘기는 값. MainActivity는 런처라 exported이고, 예전에는 받은 extras를 통째로 서비스에 넘겼다:
 * 헤드유닛의 다른 앱이나 adb가 `am start -n dev.shimpyo.app/.MainActivity --ez selftest true`를 보내면 제출용(release) 빌드도
 * kit 점검(운전석 등받이·창문·실내등·설정 온도를 움직인다)을 돌렸다 (닫힘 검증 UI-N1).
 *
 * - `demo`(시연 전환)만 어느 빌드에서나 넘긴다: 2차 PT 시연과 demo-smoke.sh가 쓴다. 하는 일은 화면 시연 패널과 같고,
 *   시연 이름([DemoScenario.NAMES])이 아닌 값은 모두 끄기([DEMO_OFF])로 바꾼다. 받은 문자열을 그대로 넘기지 않는다.
 * - 개발용 점검 옵션([DEV_FLAGS], [DEV_TEXTS])은 debug 빌드에서만 넘긴다(run-app.ps1 -SelfTest 등). release에서는 읽지도 않는다.
 * - 그 밖의 extra는 어느 빌드에서도 읽지 않는다.
 *
 * 서비스 쪽도 같은 이름을 빌드 종류로 다시 거른다(코치 서비스의 개발용 옵션 처리). 이름이 어긋나면 debug 점검이 조용히 안 돌므로
 * ReleaseSurfaceTest가 둘을 견준다.
 */
object LaunchExtras {
    const val DEMO = "demo"
    const val DEMO_OFF = "off"

    /** debug 빌드에서만 넘기는 개발용 점검 옵션 (Boolean extra): kit 점검, 그 길안내 옵션, 설계 확인 */
    val DEV_FLAGS: List<String> = listOf("selftest", "route", "verify")

    /** debug 빌드에서만 넘기는 개발용 점검 옵션 (문자열 extra): SDK 동작 실험의 이름 */
    val DEV_TEXTS: List<String> = listOf("probe")

    /** 문자열 옵션의 최대 길이 (이름 하나면 충분하다) */
    const val MAX_TEXT = 64

    /** 서비스로 넘길 것. [demo]는 시연 이름이나 "off", [devFlags]는 켜진 점검 옵션 이름, [devTexts]는 문자열 점검 옵션 */
    data class Forwarded(val demo: String?, val devFlags: List<String>, val devTexts: Map<String, String> = emptyMap()) {
        val isEmpty: Boolean get() = demo == null && devFlags.isEmpty() && devTexts.isEmpty()
    }

    /**
     * @param demo 인텐트의 `demo` 문자열 extra (없으면 null)
     * @param flag 이름을 주면 그 Boolean extra가 켜져 있는지 답한다. 넘길 수 있는 이름만 묻는다
     * @param text 이름을 주면 그 문자열 extra를 준다(없으면 null). 넘길 수 있는 이름만 묻는다
     */
    fun pick(demo: String?, flag: (String) -> Boolean, debugBuild: Boolean, text: (String) -> String? = { null }): Forwarded = Forwarded(
        demo = demo?.let { if (it in DemoScenario.NAMES) it else DEMO_OFF },
        devFlags = if (debugBuild) DEV_FLAGS.filter(flag) else emptyList(),
        devTexts = if (debugBuild) {
            DEV_TEXTS.mapNotNull { name -> text(name)?.takeIf { it.isNotBlank() }?.let { name to it.take(MAX_TEXT) } }.toMap()
        } else {
            emptyMap()
        },
    )
}
