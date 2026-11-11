package dev.shimpyo.app.coach

import dev.shimpyo.coach.DemoScenario

/**
 * 인텐트 extras로 들어온 요청 가운데 이 빌드가 받아들이는 것을 정한다 (순수 함수, DevHooksTest).
 *
 * MainActivity는 런처라 exported이고 받은 extras를 서비스로 그대로 넘긴다. 그래서 헤드유닛의 다른 앱이나 adb가
 * `am start -n dev.shimpyo.app/.MainActivity --ez selftest true`를 보내면 서비스까지 온다. 2026-11-12 닫힘 검증(N1)에서
 * 제출용(release) 빌드도 이 요청으로 kit 점검(운전석 등받이·창문·조명·설정 온도를 움직인다)과 설계 확인(부산역으로 길안내를
 * 새로 시작한다)을 돌린다는 것을 찾았다. 빌드 종류도 주행 여부도 보지 않았다.
 *
 * 규칙
 * - `selftest`·`route`·`verify`(개발용 점검)는 debug 빌드에서만 받는다. release에는 점검 코드도 없다
 *   (app/src/debug의 devtools, release는 아무것도 하지 않는 DevToolRunner).
 * - `demo`(시연 전환)는 release에서도 받는다: 2차 PT 시연에 쓴다. 하는 일은 화면 시연 패널과 같은 UiCommand.Demo 하나뿐이고,
 *   시연 이름([DemoScenario.NAMES])이 아닌 값은 모두 '끄기'다. 받은 문자열을 그대로 넘기지 않는다.
 * - 그 밖의 extra는 읽지 않는다.
 */
object DevHooks {
    const val EXTRA_DEMO = "demo"
    const val EXTRA_SELFTEST = "selftest"
    const val EXTRA_ROUTE = "route"
    const val EXTRA_VERIFY = "verify"

    /** 서비스가 읽는 extra 전부. 여기 없는 이름은 어느 빌드에서도 읽지 않는다 */
    val EXTRAS = listOf(EXTRA_DEMO, EXTRA_SELFTEST, EXTRA_ROUTE, EXTRA_VERIFY)

    /** 개발용 점검 (debug 빌드에만 있다) */
    enum class Tool {
        /** 쉼표 설계 확인 (SdkVerify): 길안내를 새로 시작한다 */
        VERIFY,
        /** pleos-kit 점검 (KitSelfTest): 좌석·창문·조명·온도를 움직인다 */
        SELFTEST,
    }

    /** 시연 전환 요청. [scenario]가 null이면 끄기 */
    data class DemoSwitch(val scenario: String?)

    /**
     * 인텐트 하나에서 받아들인 것. 아무것도 없으면 [tool]·[demo] 모두 null.
     * [refused]: 점검을 요청했지만 이 빌드에서는 받지 않았다 (로그용)
     */
    data class Request(val tool: Tool? = null, val withRoute: Boolean = false, val demo: DemoSwitch? = null, val refused: Boolean = false)

    /** [extra]를 이 빌드에서 읽어도 되는지 */
    fun allowed(extra: String, debugBuild: Boolean): Boolean = when (extra) {
        EXTRA_DEMO -> true
        EXTRA_SELFTEST, EXTRA_ROUTE, EXTRA_VERIFY -> debugBuild
        else -> false
    }

    /**
     * 인텐트에서 읽은 값(없으면 false·null)으로 할 일을 정한다. 점검은 한 번에 하나: verify가 selftest보다 먼저다.
     * `route`는 selftest에 딸린 옵션이라 혼자서는 아무것도 하지 않는다.
     */
    fun request(verify: Boolean, selftest: Boolean, route: Boolean, demo: String?, debugBuild: Boolean): Request {
        val tool = when {
            verify && allowed(EXTRA_VERIFY, debugBuild) -> Tool.VERIFY
            selftest && allowed(EXTRA_SELFTEST, debugBuild) -> Tool.SELFTEST
            else -> null
        }
        return Request(
            tool = tool,
            withRoute = tool == Tool.SELFTEST && route && allowed(EXTRA_ROUTE, debugBuild),
            demo = demo?.takeIf { allowed(EXTRA_DEMO, debugBuild) }?.let { DemoSwitch(it.takeIf { name -> name in DemoScenario.NAMES }) },
            refused = tool == null && (verify || selftest),
        )
    }
}
