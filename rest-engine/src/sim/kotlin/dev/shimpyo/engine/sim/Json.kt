package dev.shimpyo.engine.sim

import kotlin.math.abs
import kotlin.math.round

/** 의존성 없이 쓰는 작은 JSON 읽기·쓰기. 시뮬레이션 입력(analysis.json)과 출력에만 쓴다. */
internal object Json {

    // ------------------------------------------------------------ 읽기

    fun parse(text: String): Any? = Parser(text).run { val v = value(); ws(); require(i == s.length) { "trailing data at $i" }; v }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                require(s[i] == ':') { "':' expected at $i" }
                i++
                m[k] = value()
                ws()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> error("',' or '}' expected at $i")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value()
                ws()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> error("',' or ']' expected at $i")
                }
            }
        }

        fun str(): String {
            require(s[i] == '"') { "string expected at $i" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> error("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }

    // ------------------------------------------------------------ 쓰기

    /** 숫자 배열·짧은 배열은 한 줄로, 나머지는 들여쓰기. 실수는 소수 4자리까지(결정적 출력) */
    fun write(v: Any?): String = StringBuilder().also { w(v, it, 0) }.append('\n').toString()

    private fun w(v: Any?, sb: StringBuilder, level: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> str(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Number -> num(v.toDouble(), sb)
            is DoubleArray -> w(v.toList(), sb, level)
            is IntArray -> w(v.toList(), sb, level)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    indent(sb, level + 1)
                    str(k.toString(), sb)
                    sb.append(": ")
                    w(x, sb, level + 1)
                }
                sb.append('\n')
                indent(sb, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                val flat = list.all { it == null || it is Number || it is Boolean || (it is String && it.length < 40) }
                if (flat) {
                    sb.append('[')
                    list.forEachIndexed { idx, x -> if (idx > 0) sb.append(", "); w(x, sb, level + 1) }
                    sb.append(']')
                } else {
                    sb.append("[\n")
                    list.forEachIndexed { idx, x ->
                        if (idx > 0) sb.append(",\n")
                        indent(sb, level + 1)
                        w(x, sb, level + 1)
                    }
                    sb.append('\n')
                    indent(sb, level)
                    sb.append(']')
                }
            }
            else -> str(v.toString(), sb)
        }
    }

    private fun indent(sb: StringBuilder, level: Int) { repeat(level) { sb.append("  ") } }

    private fun num(d: Double, sb: StringBuilder) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        val r = round(d * 10_000.0) / 10_000.0
        if (abs(r - Math.rint(r)) < 1e-12 && abs(r) < 1e15) { sb.append(Math.rint(r).toLong()); return }
        var t = String.format(java.util.Locale.ROOT, "%.4f", r)
        t = t.trimEnd('0').trimEnd('.')
        sb.append(t)
    }

    private fun str(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

/** LinkedHashMap 빌더 (키 순서 유지) */
internal fun obj(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> = linkedMapOf(*pairs)
