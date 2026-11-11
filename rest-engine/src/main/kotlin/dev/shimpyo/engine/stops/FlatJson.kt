package dev.shimpyo.engine.stops

import java.io.Reader

/**
 * tools/data가 만드는 JSON(평평한 객체의 배열)만 읽는 작은 스트리밍 파서. stops.bin을 만드는 도구와 테스트에서 쓴다.
 * 값은 앱이 예전에 android.util.JsonReader로 읽던 것과 같게 만든다: 문자열 → String, 숫자 → Double,
 * 불리언 → Boolean, null → null, 배열·객체(이 데이터에는 없다) → null.
 */
internal object FlatJson {

    fun forEachObject(reader: Reader, action: (Map<String, Any?>) -> Unit) {
        val p = Parser(reader.buffered(1 shl 16))
        p.expect('[')
        if (p.peekNonWs() == ']') { p.next(); return }
        val obj = HashMap<String, Any?>(32)
        while (true) {
            obj.clear()
            p.expect('{')
            if (p.peekNonWs() == '}') {
                p.next()
            } else {
                while (true) {
                    val key = p.string()
                    p.expect(':')
                    obj[key] = p.value()
                    when (p.nextNonWs()) {
                        ',' -> continue
                        '}' -> break
                        else -> p.fail("',' or '}'")
                    }
                }
            }
            action(obj)
            when (p.nextNonWs()) {
                ',' -> continue
                ']' -> return
                else -> p.fail("',' or ']'")
            }
        }
    }

    private class Parser(private val r: Reader) {
        private var pushed = -1
        private var offset = 0L

        fun next(): Char {
            val c = if (pushed >= 0) pushed.also { pushed = -1 } else r.read()
            if (c < 0) throw IllegalArgumentException("unexpected end of JSON at $offset")
            offset++
            return c.toChar()
        }

        private fun unread(c: Char) { pushed = c.code; offset-- }

        fun nextNonWs(): Char {
            while (true) {
                val c = next()
                if (!c.isWhitespace()) return c
            }
        }

        fun peekNonWs(): Char = nextNonWs().also(::unread)

        fun expect(c: Char) { if (nextNonWs() != c) fail("'$c'") }

        fun fail(what: String): Nothing = throw IllegalArgumentException("expected $what at $offset")

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> sb.append(CharArray(4) { next() }.concatToString().toInt(16).toChar())
                        else -> fail("escape")
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun value(): Any? = when (val c = peekNonWs()) {
            '"' -> string()
            't' -> { literal("true"); true }
            'f' -> { literal("false"); false }
            'n' -> { literal("null"); null }
            '[', '{' -> { skipNested(); null }
            else -> if (c == '-' || c.isDigit()) number() else fail("value")
        }

        private fun literal(word: String) { for (ch in word) if (next() != ch) fail(word) }

        private fun number(): Double? {
            val sb = StringBuilder()
            while (true) {
                val c = next()
                if (c.isDigit() || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') sb.append(c) else { unread(c); break }
            }
            return sb.toString().toDoubleOrNull()
        }

        private fun skipNested() {
            var depth = 0
            do {
                when (nextNonWs()) {
                    '"' -> { unread('"'); string() }
                    '[', '{' -> depth++
                    ']', '}' -> depth--
                }
            } while (depth > 0)
        }
    }
}
