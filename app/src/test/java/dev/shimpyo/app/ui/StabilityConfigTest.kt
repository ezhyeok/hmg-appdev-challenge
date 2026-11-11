package dev.shimpyo.app.ui

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Compose 안정성 설정(app/compose-stability.conf)에는 값이 바뀌지 않는 타입만 적는다 (UI 감사 S4).
 * 예전에는 `dev.shimpyo.engine.*` 전체를 적어서 가변 클래스(CoachEngine, RouteContext)까지 안정으로 보였다: 값이 바뀌어도
 * 화면이 다시 그려지지 않을 수 있다. 여기 적힌 쉼표 타입은 실제로 있고(이름을 바꾸면 조용히 효과가 사라진다) 필드가 모두 final이어야 한다.
 */
class StabilityConfigTest {

    private fun entries(): List<String> {
        val f = File("compose-stability.conf")
        if (!f.isFile) fail("찾지 못했다: ${f.absolutePath}")
        return f.readLines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }
    }

    @Test
    fun onlyNamedImmutableTypesAreMarkedStable() {
        val own = entries().filter { it.startsWith("dev.shimpyo.") }
        assertTrue("엔진·코치 출력 타입이 적혀 있어야 한다", own.size >= 20)
        own.forEach { name ->
            assertTrue("묶음(*)으로 적지 않는다: $name", "*" !in name)
            val c = try {
                Class.forName(name)
            } catch (e: ClassNotFoundException) {
                fail("compose-stability.conf의 타입이 없다 (이름이 바뀌었나): $name")
                return@forEach
            }
            if (c.isEnum) return@forEach
            val mutable = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) }.map { it.name }
            assertTrue("$name 에 바뀔 수 있는 필드가 있다: $mutable", mutable.isEmpty())
        }
        // 쉼표 타입 밖에서는 읽기 전용 컬렉션만 (앱은 List·Set·Map을 바꾸지 않고 새로 만든다)
        val others = entries().filterNot { it.startsWith("dev.shimpyo.") }
        assertTrue(others.toString(), others.all { it.startsWith("kotlin.collections.") })
    }
}
