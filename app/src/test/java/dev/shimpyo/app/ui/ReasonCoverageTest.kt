package dev.shimpyo.app.ui

import dev.shimpyo.engine.Reason
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 엔진·코치가 내는 이유 코드는 모두 화면 문구가 정해져 있어야 한다: 짧은 칩 문구가 있거나([Labels.shortReason]),
 * 칩으로 쓰지 않는다고 적혀 있거나([Labels.NOT_CHIP_CODES]). 예전에는 다섯 코드에 문구가 없어 게이지 이유가 엉뚱하게
 * 보였다(UI 감사 #17). 새 코드가 생기면 이 시험이 실패하므로 Labels에 문구를 더한다.
 * 저장소의 rest-engine 소스(엔진, 코치)를 읽는다. 시험은 app 모듈 폴더에서 돈다.
 */
class ReasonCoverageTest {

    private val code = Regex("\"((?:RESTROOM|FATIGUE|STYLE|LAST_CHANCE|NEXT|URGENT|PLAN|CABIN)_[A-Z0-9_]+)\"")

    private fun engineCodes(): Set<String> {
        val roots = listOf(File("../rest-engine/src/main/kotlin"), File("../rest-engine/coach/src/main/kotlin"))
        if (roots.none { it.isDirectory }) fail("rest-engine 소스를 찾지 못했다: ${roots.map { it.absolutePath }}")
        return roots.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { f -> code.findAll(f.readText()).map { it.groupValues[1] }.toList() }
            .toSet()
    }

    @Test
    fun everyEngineReasonCodeHasAScreenLabel() {
        val codes = engineCodes()
        assertTrue("코드를 하나도 못 읽었다", codes.size > 20)
        val missing = codes.filter { c -> c !in Labels.NOT_CHIP_CODES && Labels.shortReason(Reason(c, "")) == null }
        assertTrue("화면 문구가 없는 이유 코드: $missing (Labels.shortReason 또는 NOT_CHIP_CODES에 더할 것)", missing.isEmpty())
    }
}
