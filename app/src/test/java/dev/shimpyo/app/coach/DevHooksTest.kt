package dev.shimpyo.app.coach

import dev.shimpyo.app.coach.DevHooks.DemoSwitch
import dev.shimpyo.app.coach.DevHooks.Request
import dev.shimpyo.app.coach.DevHooks.Tool
import dev.shimpyo.coach.DemoScenario
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 인텐트 extras로 들어오는 개발용 요청 (2026-11-12 닫힘 검증 N1).
 * 회귀: 제출용(release) 빌드에서 `am start -n dev.shimpyo.app/.MainActivity --ez selftest true`가 KitSelfTest를 돌려
 * 운전석 등받이·창문·조명·설정 온도를 움직였고, `--ez verify true`는 부산역으로 길안내를 새로 시작했다.
 * 기기 확인은 tools/laptop/devhooks-check.sh (release APK: selftest가 아무것도 하지 않고 demo는 된다).
 */
class DevHooksTest {
    private fun release(verify: Boolean = false, selftest: Boolean = false, route: Boolean = false, demo: String? = null, probe: String? = null) =
        DevHooks.request(verify, selftest, route, demo, debugBuild = false, probe = probe)

    private fun debug(verify: Boolean = false, selftest: Boolean = false, route: Boolean = false, demo: String? = null, probe: String? = null) =
        DevHooks.request(verify, selftest, route, demo, debugBuild = true, probe = probe)

    @Test
    fun aReleaseBuildNeverRunsADevToolWhateverTheExtrasSay() {
        for (verify in listOf(false, true)) for (selftest in listOf(false, true)) for (route in listOf(false, true)) {
            for (demo in listOf(null, "holiday", "off", "selftest")) for (probe in listOf(null, "wp-rm:ALL")) {
                val r = release(verify, selftest, route, demo, probe)
                assertEquals("verify=$verify selftest=$selftest route=$route demo=$demo probe=$probe", null, r.tool)
                assertFalse(r.withRoute)
                assertEquals(null, r.probe)
                assertEquals("점검을 요청했으면 거절을 남긴다", verify || selftest || probe != null, r.refused)
            }
        }
        for (extra in listOf(DevHooks.EXTRA_SELFTEST, DevHooks.EXTRA_VERIFY, DevHooks.EXTRA_ROUTE, DevHooks.EXTRA_PROBE)) {
            assertFalse(extra, DevHooks.allowed(extra, debugBuild = false))
        }
    }

    @Test
    fun theDemoSwitchWorksInAReleaseBuildToo() {
        // 2차 PT 시연은 release APK에서 `--es demo holiday`로 켠다
        for (name in DemoScenario.NAMES) assertEquals(Request(demo = DemoSwitch(name)), release(demo = name))
        assertEquals(Request(demo = DemoSwitch(null)), release(demo = "off"))
        assertEquals("extra가 없으면 시연을 건드리지 않는다", Request(), release())
        assertTrue(DevHooks.allowed(DevHooks.EXTRA_DEMO, debugBuild = false))
    }

    @Test
    fun theDemoExtraCanOnlySwitchTheDemo() {
        // 시연 이름이 아닌 값은 모두 '끄기'다. 받은 문자열이 명령이나 로그로 그대로 가지 않는다
        for (junk in listOf("", "selftest", "verify", "HOLIDAY", "holiday ", "../x", "holiday\nselftest true", "x".repeat(10_000))) {
            for (debugBuild in listOf(false, true)) {
                val r = DevHooks.request(verify = false, selftest = false, route = false, demo = junk, debugBuild = debugBuild)
                assertEquals("demo=${junk.take(20)}", Request(demo = DemoSwitch(null)), r)
            }
        }
        // 시연과 함께 온 점검 요청은 release에서 버려지고 시연만 남는다
        assertEquals(Request(demo = DemoSwitch("holiday"), refused = true), release(selftest = true, route = true, demo = "holiday"))
    }

    @Test
    fun aDebugBuildRunsOneToolAtATime() {
        assertEquals(Request(tool = Tool.SELFTEST), debug(selftest = true))
        assertEquals(Request(tool = Tool.SELFTEST, withRoute = true), debug(selftest = true, route = true))
        assertEquals(Request(tool = Tool.VERIFY), debug(verify = true))
        assertEquals("둘 다 오면 설계 확인만 (예전과 같다)", Request(tool = Tool.VERIFY), debug(verify = true, selftest = true, route = true))
        assertEquals("route는 selftest에 딸린 옵션이다", Request(), debug(route = true))
        assertEquals(Request(tool = Tool.SELFTEST, demo = DemoSwitch("urgent")), debug(selftest = true, demo = "urgent"))
        assertEquals(Request(tool = Tool.PROBE, probe = "wp"), debug(probe = "wp"))
        assertEquals("점검이 먼저다. 밀린 실험은 버린다", Request(tool = Tool.SELFTEST), debug(selftest = true, probe = "wp"))
    }

    @Test
    fun unknownExtrasAreNeverRead() {
        for (debugBuild in listOf(false, true)) {
            assertFalse(DevHooks.allowed("anything", debugBuild))
            assertFalse(DevHooks.allowed("", debugBuild))
        }
        assertEquals(setOf("demo", "selftest", "route", "verify", "probe"), DevHooks.EXTRAS.toSet())
    }

    // ------------------------------------------------------------ 소스 구조 (시험은 app 모듈 폴더에서 돈다)

    private fun kotlinFiles(dir: String): List<File> {
        val root = File(dir)
        if (!File("src/main/java").isDirectory) fail("app 모듈 폴더에서 돌아야 한다: ${File(".").absolutePath}")
        return if (root.isDirectory) root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() else emptyList()
    }

    @Test
    fun theDevToolsAreNotCompiledIntoTheReleaseBuild() {
        // 점검 코드는 debug 소스에만 있다. main·release 소스는 그 이름을 부르지 않는다
        val shipped = kotlinFiles("src/main/java") + kotlinFiles("src/release/java")
        val callers = shipped.filter { f -> Regex("\\b(KitSelfTest|SdkVerify)\\s*\\(").containsMatchIn(f.readText()) }
        assertTrue("release에 들어가는 소스가 점검을 부른다: ${callers.map { it.path }}", callers.isEmpty())
        assertTrue("점검 코드가 main 소스에 있다", kotlinFiles("src/main/java").none { it.name in setOf("KitSelfTest.kt", "SdkVerify.kt") })
        assertTrue("debug 소스에 점검 코드가 있어야 한다", kotlinFiles("src/debug/java").any { it.name == "KitSelfTest.kt" })
        val stub = File("src/release/java/dev/shimpyo/app/devtools/DevToolRunner.kt")
        assertTrue("release용 DevToolRunner가 있어야 빌드된다", stub.isFile)
    }

    @Test
    fun onlyTheServiceReadsIntentExtrasAndOnlyThroughDevHooks() {
        val readers = Regex("\\.get(?:Boolean|String|Int|Long|Float|Double|Parcelable|Serializable|Bundle)?Extra\\(([^,)]*)")
        val found = kotlinFiles("src/main/java").flatMap { f -> readers.findAll(f.readText()).map { f.name to it.groupValues[1].trim() }.toList() }
        assertTrue("extras를 읽는 곳을 하나도 못 찾았다 (시험이 낡았다)", found.isNotEmpty())
        val outside = found.filter { (file, key) -> file != "CoachService.kt" || !key.startsWith("DevHooks.EXTRA_") }
        assertTrue("DevHooks를 거치지 않고 extras를 읽는다: $outside", outside.isEmpty())
        val service = File("src/main/java/dev/shimpyo/app/coach/CoachService.kt").readText()
        assertTrue("서비스는 빌드 종류를 DevHooks에 넘긴다", "debugBuild = BuildConfig.DEBUG" in service)
    }
}
