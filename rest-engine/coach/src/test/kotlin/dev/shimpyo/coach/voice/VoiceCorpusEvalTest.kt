package dev.shimpyo.coach.voice

import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 발화 묶음으로 잰 말 해석 정확도 (src/test/resources/voice-corpus).
 *
 * 묶음은 해석기 코드와 표(IntentParserTest)를 보지 않고 docs/design/coach-layer/voice.md만 보고 따로 쓴 말이다 (2026-11-13).
 * 실제 차에서 할 법한 말, 부정·되묻기·언급, STT 손상(띄어쓰기, 끝 글자 잘림, 잘못 들음)을 섞었다. 앱과 같은 길로 해석한다:
 * 마이크 버튼은 [IntentParser.parseSpoken], 질문 카드의 대답은 [IntentParser.replyTo]. 반은 dev(틀린 것을 고치는 데 쓴다),
 * 반은 test(재기만 한다: 고칠 때 보지 않아야 일반화를 잰다).
 *
 * 지키는 것
 * 1. 위험한 오답 0: 카드 대답을 잘못 '응'으로 읽기(경유지가 내비에 들어간다), 마이크 말을 잘못 급해로 읽기(급해 모드가 뜬다).
 * 2. 정확도가 기준(측정값에서 정한 하한) 아래로 떨어지지 않기.
 * 보고서: build/reports/voice-corpus-mic.md, voice-corpus-card.md (분할·종류·정답별 정확도와 틀린 말 목록). LLM 해석기도 같은 묶음으로 잴 수 있게
 * 분류 함수만 바꿔 끼운다 ([evaluate]).
 */
class VoiceCorpusEvalTest {

    data class Row(val split: String, val category: String, val text: String, val expected: String, val note: String)

    data class Result(val row: Row, val got: String) {
        val ok: Boolean get() = matches(row.expected, got)
    }

    private val parser = IntentParser()
    private val people = listOf(
        Occupant("driver", OccupantType.ADULT, label = "운전자"),
        Occupant("kid", OccupantType.CHILD, ageYears = 7, label = "첫째"),
        Occupant("kid2", OccupantType.CHILD, ageYears = 4, label = "둘째"),
        Occupant("baby", OccupantType.INFANT, label = "아기"),
        Occupant("grandma", OccupantType.ELDERLY, label = "할머니"),
        Occupant("dog", OccupantType.DOG, label = "반려견"),
    )
    private val cardStop = "옥산휴게소"

    // ------------------------------------------------------------ 분류 (앱과 같은 길)

    private fun micLabel(text: String): String = when (val s = parser.parseSpoken(text, people, activePromptId = null)) {
        null -> "NONE"
        is Spoken.Asleep -> (if (s.asleep) "ASLEEP" else "AWAKE") + (s.occupantId?.let { ":$it" } ?: "")
        is Spoken.Intent -> intentLabel(s.intent)
    }

    private fun cardLabel(text: String): String = parser.replyTo(text, people, cardStop).answer.name

    // ------------------------------------------------------------ 시험

    @Test
    fun micUtterances() {
        val rows = load("mic.tsv")
        val results = evaluate(rows, ::micLabel)
        val dangerous = results.filter { it.got.kind() == "URGENT" && it.row.expected.kind() != "URGENT" }
        report("mic", "마이크 (parseSpoken)", results, dangerous, "급해가 아닌 말을 급해로 읽음")
        assertTrue("급해가 아닌 말을 급해로 읽었다 (급해 모드가 뜬다):\n${dangerous.lines()}", dangerous.isEmpty())
        assertAtLeast("마이크 dev", results.filter { it.row.split == "dev" }, MIC_DEV_MIN)
        assertAtLeast("마이크 test", results.filter { it.row.split == "test" }, MIC_TEST_MIN)
    }

    @Test
    fun cardAnswers() {
        val rows = load("card.tsv")
        val results = evaluate(rows, ::cardLabel)
        val dangerous = results.filter { it.got == "YES" && it.row.expected != "YES" }
        report("card", "카드 대답 (replyTo)", results, dangerous, "수락이 아닌 말을 수락으로 읽음")
        assertTrue("수락이 아닌 대답을 '응'으로 읽었다 (경유지가 들어간다):\n${dangerous.lines()}", dangerous.isEmpty())
        assertAtLeast("카드 dev", results.filter { it.row.split == "dev" }, CARD_DEV_MIN)
        assertAtLeast("카드 test", results.filter { it.row.split == "test" }, CARD_TEST_MIN)
    }

    @Test
    fun theCorpusIsWellFormed() {
        for (file in listOf("mic.tsv", "card.tsv")) {
            val rows = load(file)
            assertTrue("$file: 너무 적다 (${rows.size})", rows.size >= 100)
            assertEquals("$file: dev와 test가 모두 있다", setOf("dev", "test"), rows.map { it.split }.toSet())
            val allowed = if (file == "card.tsv") CARD_LABELS else MIC_LABELS
            val bad = rows.filter { it.expected.kind() !in allowed }
            assertTrue("$file: 모르는 정답 ${bad.map { it.expected }.distinct()}", bad.isEmpty())
            val dup = rows.groupBy { it.text }.filter { it.value.size > 1 }.keys
            assertTrue("$file: 같은 말이 두 번 ${dup.take(5)}", dup.isEmpty())
        }
    }

    // ------------------------------------------------------------ 도구

    private fun evaluate(rows: List<Row>, classify: (String) -> String): List<Result> = rows.map { Result(it, classify(it.text)) }

    private fun assertAtLeast(name: String, results: List<Result>, min: Double) {
        val acc = accuracy(results)
        assertTrue("$name 정확도 ${pct(acc)} < 기준 ${pct(min)}. build/reports/voice-corpus-*.md의 틀린 말을 볼 것", acc >= min)
    }

    private fun load(name: String): List<Row> {
        val text = checkNotNull(javaClass.getResourceAsStream("/voice-corpus/$name")) { "묶음이 없다: $name" }
            .bufferedReader(Charsets.UTF_8).readText()
        return text.lineSequence().drop(1).map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val f = line.split('\t')
            require(f.size >= 4) { "$name: 칸이 모자란 줄: $line" }
            Row(f[0].trim(), f[1].trim(), f[2].trim(), f[3].trim(), f.getOrElse(4) { "" }.trim())
        }.toList()
    }

    private fun report(file: String, title: String, results: List<Result>, dangerous: List<Result>, dangerName: String) {
        val sb = StringBuilder()
        sb.append("## $title\n\n")
        sb.append("| 분할 | 말 수 | 정확도 |\n|---|---:|---:|\n")
        for (split in listOf("dev", "test")) {
            val r = results.filter { it.row.split == split }
            sb.append("| $split | ${r.size} | ${pct(accuracy(r))} |\n")
        }
        sb.append("| 전체 | ${results.size} | ${pct(accuracy(results))} |\n\n")
        sb.append("$dangerName (위험한 오답): ${dangerous.size}\n\n")
        sb.append("| 종류 | dev | test |\n|---|---:|---:|\n")
        for (cat in results.map { it.row.category }.distinct().sorted()) {
            val d = results.filter { it.row.category == cat && it.row.split == "dev" }
            val t = results.filter { it.row.category == cat && it.row.split == "test" }
            sb.append("| $cat | ${pct(accuracy(d))} (${d.size}) | ${pct(accuracy(t))} (${t.size}) |\n")
        }
        sb.append("\n| 정답 | 맞음 / 전체 |\n|---|---:|\n")
        for ((label, r) in results.groupBy { it.row.expected.kind() }.toSortedMap()) {
            sb.append("| $label | ${r.count { it.ok }} / ${r.size} |\n")
        }
        sb.append("\n### 틀린 말 (dev)\n\n")
        results.filter { !it.ok && it.row.split == "dev" }.forEach { sb.append("- `${it.row.text}` → ${it.got} (정답 ${it.row.expected}, ${it.row.category})\n") }
        sb.append("\n### 틀린 말 (test, 고칠 때 보지 않는다)\n\n")
        sb.append("${results.count { !it.ok && it.row.split == "test" }}개\n\n")
        val out = File("build/reports/voice-corpus-$file.md")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
        println(sb)
    }

    private fun List<Result>.lines() = joinToString("\n") { "  `${it.row.text}` → ${it.got} (정답 ${it.row.expected})" }

    companion object {
        /**
         * 하한 (2026-11-13). 처음 잰 값: 마이크 dev 88.4%·test 90.2%, 카드 dev 87.7%·test 90.1%, 위험한 오답 카드 3개.
         * dev의 틀린 말을 규칙으로 고친 뒤: dev 100%·100%, test 90.8%·91.4%, 위험한 오답 0. test는 고칠 때 보지 않은 말이라
         * 일반화를 잰다. 내려가면 회귀다. 올라가면 함께 올린다. docs/eval/voice-corpus.md
         */
        const val MIC_DEV_MIN = 1.0
        const val MIC_TEST_MIN = 0.90
        const val CARD_DEV_MIN = 1.0
        const val CARD_TEST_MIN = 0.91

        val MIC_LABELS = setOf("URGENT", "RESTROOM", "FEEDING", "PET", "FUEL", "HUNGRY", "SLEEPY", "REST", "HOWLONG", "ASLEEP", "AWAKE", "NONE")
        val CARD_LABELS = setOf("YES", "NO", "UNKNOWN")

        fun intentLabel(i: VoiceIntent): String = when (i) {
            VoiceIntent.UrgentRestroom -> "URGENT"
            VoiceIntent.WantRest -> "REST"
            VoiceIntent.Sleepy -> "SLEEPY"
            VoiceIntent.Hungry -> "HUNGRY"
            VoiceIntent.HowLong -> "HOWLONG"
            is VoiceIntent.Accept, is VoiceIntent.Decline -> "NONE"
            is VoiceIntent.OccupantNeed -> when (i.purpose) {
                Purpose.RESTROOM -> "RESTROOM"
                Purpose.FEEDING -> "FEEDING"
                Purpose.PET -> "PET"
                Purpose.FUEL -> "FUEL"
                Purpose.REST -> "REST"
                Purpose.MEAL -> "HUNGRY"
            } + (i.occupantId?.let { ":$it" } ?: "")
        }

        /** "RESTROOM:kid" → "RESTROOM" */
        fun String.kind(): String = substringBefore(':')

        /** 종류가 같고, 정답이 사람을 정했으면 그 사람이어야 한다 (정하지 않았으면 누구든 된다) */
        fun matches(expected: String, got: String): Boolean {
            if (expected.kind() != got.kind()) return false
            val who = expected.substringAfter(':', "")
            return who.isEmpty() || who == got.substringAfter(':', "")
        }

        fun accuracy(r: List<Result>): Double = if (r.isEmpty()) 1.0 else r.count { it.ok }.toDouble() / r.size

        fun pct(v: Double) = "%.1f%%".format(v * 100)
    }
}
