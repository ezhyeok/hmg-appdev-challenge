package dev.shimpyo.app.ui

import dev.shimpyo.coach.DemoScenario
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 제출용(release) 빌드가 밖에 내놓는 것: 권한, 내보낸 컴포넌트, 실행 인텐트에서 받는 값 (닫힘 검증 UI-N1).
 *
 * MainActivity는 런처라 exported이고, 예전에는 받은 인텐트의 extras를 통째로 서비스에 넘겼다. 그래서 헤드유닛의 다른 앱이나 adb가
 * `am start -n dev.shimpyo.app/.MainActivity --ez selftest true`를 보내면 제출용 빌드도 kit 점검(운전석 등받이·창문·실내등·설정 온도를
 * 움직인다)을 돌렸다. Manifest의 좌석·창문·조명 제어 권한은 그 점검만 썼다. 시험은 app 모듈 폴더에서 돈다(소스를 읽는다).
 */
class ReleaseSurfaceTest {

    private fun read(path: String): String {
        val f = File(path)
        if (!f.isFile) fail("찾지 못했다: ${f.absolutePath}")
        return f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    private fun permissions(manifest: String): Set<String> =
        Regex("<uses-permission\\s+android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()

    private val main by lazy { read("src/main/AndroidManifest.xml") }
    private val debug by lazy { read("src/debug/AndroidManifest.xml") }

    /** 제품이 쓰는 권한과 쓰는 곳. 권한을 더하려면 여기에 쓰는 곳을 적는다 (앱 보안 가이드: 필요한 권한만 요청) */
    private val product = mapOf(
        "pleos.car.permission.CAR_DOORS" to "문 열림 (화장실 정차 판정): CarSignals → VehicleKit.doorPosition",
        "pleos.car.permission.CAR_LIGHTS" to "방향지시등 (운전 패턴, 묻는 때): CarSignals → VehicleKit.turnSignal",
        "pleos.car.permission.CAR_CLIMATE" to "내기순환·공조 전원·설정 온도 읽기 (환기 권유): KitCabin, CarSignals",
        "pleos.car.permission.CONTROL_CAR_CLIMATE" to "외기 전환 (동의한 자동 환기, '외기로 전환'): KitCabin.freshAir/recirculate",
        "pleos.car.permission.CAR_DRIVE" to "조향각·브레이크·크루즈 (운전 패턴, 단조로운 주행): CarSignals",
        "pleos.car.permission.NAVI_ROUTE" to "길안내 상태·TBT·정체·목적지 읽기: NaviTracker → NaviKit",
        "pleos.car.permission.NAVI_ROUTE_SEARCH" to "경유지 추가·길안내 시작·requestTrip: KitNaviPort",
        "pleos.car.permission.TTS_SERVICE" to "질문과 안내 음성: VoiceAgent → TtsKit",
        "pleos.car.permission.STT_SERVICE" to "응·아니, 말로 한 요청: VoiceAgent → SttKit",
        "pleos.car.permission.FUSED_LOCATION" to "지금 위치 (앞쪽 후보, 급해): LocationKit",
        "pleos.car.permission.LLM_SERVICE" to "규칙으로 못 알아들은 말 해석 (ON_DEVICE): PleosLlmInterpreter",
        "android.car.permission.CAR_POWERTRAIN" to "기어 (정차·주행 판정): CarState",
        "android.car.permission.CAR_SPEED" to "차속 (주행 거리, 운전 패턴): CarState",
    )

    /** 개발용 점검(KitSelfTest)만 쓰는 권한: debug 빌드에만 */
    private val devOnly = setOf(
        "pleos.car.permission.CAR_SEATS",
        "pleos.car.permission.CONTROL_CAR_SEATS",
        "pleos.car.permission.CONTROL_CAR_LIGHTS",
        "pleos.car.permission.CAR_WINDOWS",
        "pleos.car.permission.CONTROL_CAR_WINDOWS",
        "pleos.car.permission.CAR_ENERGY",
    )

    @Test
    fun releaseDeclaresOnlyThePermissionsTheProductUses() {
        assertEquals(product.keys, permissions(main))
        val control = permissions(main).filter { "CONTROL_CAR_" in it }.toSet()
        assertEquals("제품이 차를 움직이는 것은 내기·외기 전환뿐이다", setOf("pleos.car.permission.CONTROL_CAR_CLIMATE"), control)
    }

    @Test
    fun devToolPermissionsLiveOnlyInTheDebugManifest() {
        assertEquals(devOnly, permissions(debug))
        assertTrue("debug Manifest는 권한만 더한다 (컴포넌트를 내보내지 않는다)", "<activity" !in debug && "<service" !in debug && "<receiver" !in debug && "<provider" !in debug)
    }

    @Test
    fun onlyTheLauncherIsExportedAndCcpIsDeclaredUnsupported() {
        assertEquals("내보낸 컴포넌트는 런처 액티비티 하나", 1, Regex("android:exported=\"true\"").findAll(main).count())
        assertTrue(Regex("<service[^>]*android:name=\"\\.coach\\.CoachService\"[^>]*android:exported=\"false\"", RegexOption.DOT_MATCHES_ALL).containsMatchIn(main))
        // CCP(로터리) 미지원 선언과 주행 중 화면 유지 (UI 감사 #13, CCP 개발 가이드 'CCP 미지원 선언 방법')
        assertTrue(Regex("android:name=\"com\\.hmg\\.rotary\\.ROTARY_SUPPORTED\"\\s+android:value=\"false\"").containsMatchIn(main))
        assertTrue(Regex("android:name=\"distractionOptimized\"\\s+android:value=\"true\"").containsMatchIn(main))
    }

    // ------------------------------------------------------------ 실행 인텐트에서 받는 값

    @Test
    fun aReleaseBuildForwardsOnlyTheDemoSwitch() {
        val everything = { _: String -> true }
        val r = LaunchExtras.pick(demo = "holiday", flag = everything, debugBuild = false)
        assertEquals("holiday", r.demo)
        assertTrue("제출용 빌드는 개발용 점검 옵션을 넘기지 않는다", r.devFlags.isEmpty())
        // 시연 전환은 화면 시연 패널과 같은 일만 한다: 시연 이름이 아니면 끄기다. 받은 문자열을 그대로 넘기지 않는다
        DemoScenario.NAMES.forEach { assertEquals(it, LaunchExtras.pick(it, everything, debugBuild = false).demo) }
        assertEquals("off", LaunchExtras.pick("off", everything, debugBuild = false).demo)
        assertEquals("off", LaunchExtras.pick("selftest", everything, debugBuild = false).demo)
        assertEquals("off", LaunchExtras.pick("", everything, debugBuild = false).demo)
        assertNull(LaunchExtras.pick(null, everything, debugBuild = false).demo)
        assertTrue(LaunchExtras.pick(null, everything, debugBuild = false).isEmpty)
        // 제출용 빌드는 다른 extra를 읽지도 않는다
        val asked = ArrayList<String>()
        LaunchExtras.pick(null, { asked += it; true }, debugBuild = false)
        assertTrue(asked.toString(), asked.isEmpty())
    }

    @Test
    fun aDebugBuildAlsoForwardsTheDevToolFlags() {
        // debug 빌드의 점검(run-app.ps1 -SelfTest 등)은 그대로 쓴다. 정해진 이름만 읽는다
        val r = LaunchExtras.pick(demo = null, flag = { it == "selftest" || it == "route" || it == "anything" }, debugBuild = true)
        assertEquals(listOf("selftest", "route"), r.devFlags)
        assertNull(r.demo)
        val asked = ArrayList<String>()
        LaunchExtras.pick(null, { asked += it; false }, debugBuild = true)
        assertEquals(listOf("selftest", "route", "verify"), asked)
    }

    @Test
    fun mainActivityBuildsTheServiceIntentFromThePickedValuesOnly() {
        // 소스 지킴이: 받은 extras 묶음을 통째로 넘기는 코드(putExtras)가 다시 생기지 않게
        val src = File("src/main/java/dev/shimpyo/app/MainActivity.kt")
        if (!src.isFile) fail("찾지 못했다: ${src.absolutePath}")
        val text = src.readText()
        assertFalse("putExtras(" in text)
        assertTrue("LaunchExtras.pick(" in text)
    }
}
