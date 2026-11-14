package dev.shimpyo.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 졸음의 마지막 기회 (SPEC 4.3a). 화장실의 마지막 기회와 같은 계산(GapAnalyzer)을 피로 게이지에 한다: 피로 게이지가 바닥나기 전에
 * 닿는 마지막 쉼터 뒤로, 다음 쉼터(또는 정체 끝)까지가 게이지보다 길면 그 쉼터에서 쉬자고 미리 묻는다.
 * 예전에는 피로가 바닥난(NOW) 뒤에야 가장 가까운 쉼터를 권해서, 쉼터가 없는 긴 구간이나 긴 정체 한가운데서 한계가 왔다.
 */
class FatigueLastChanceTest {
    private val afternoon = kst(2026, 11, 14, 15, 0)

    private fun engine(start: Long, continuousMin: Double) = CoachEngine(
        profile = TripProfile(listOf(adult("driver")), tripStart = start - (continuousMin * 60_000).toLong(), continuousDrivingMin = continuousMin),
        random = Random(7),
    )

    private fun restArea(id: String, posM: Double) = RouteStop(posM, stop(id, posM, facilities = Facilities(restroom = true)))

    private fun firstEnginePrompt(sim: HighwaySim, untilM: Double): Prompt? {
        sim.driveUntilPos(untilM)
        return sim.prompts.map { it.third }.firstOrNull { !it.userRequested }
    }

    @Test
    fun restBeforeALongStretchWithoutRestStopsWhenFatigueRunsOutFirst() {
        // 오후 3시, 쉬지 않고 70분 달렸다 (피로 게이지 약 30분). 6km 앞 휴게소를 지나면 다음 쉼터는 100km(약 57분) 뒤
        val e = engine(afternoon, 70.0)
        val sim = HighwaySim(e, afternoon, routeLengthM = 200_000.0, stops = listOf(restArea("A", 6_000.0), restArea("B", 106_000.0)))
        val p = assertNotNull(firstEnginePrompt(sim, 5_400.0), "A 앞에서 물어야 한다")
        assertEquals(PromptKind.LAST_CHANCE, p.kind, p.speech)
        assertEquals("A", p.stop?.id)
        assertTrue(p.reasons.any { it.code == "FATIGUE_LAST_CHANCE_GAP" }, p.reasons.toString())
        assertTrue(p.speech.contains("쉬어"), p.speech)
        val fatigueMin = assertNotNull(sim.last!!.fatigue.minutes)
        assertTrue(sim.last!!.fatigue.level < Level.NOW && fatigueMin > 15, "아직 피로가 바닥나지 않았을 때 미리 묻는다: $fatigueMin 분")
    }

    @Test
    fun theNextCardBeforeTheQuestionAlreadySaysWhy() {
        // 질문 전(진입로 5km 밖)에도 다음 추천 카드가 그 쉼터와 졸음의 이유를 보인다 (2026-11-15 에뮬레이터: 화장실 이유만 보였다)
        val e = engine(afternoon, 70.0)
        val sim = HighwaySim(e, afternoon, routeLengthM = 200_000.0, stops = listOf(restArea("A", 12_000.0), restArea("B", 112_000.0)))
        val out = sim.driveUntilPos(1_000.0)
        val next = assertNotNull(out.next, "다음 추천")
        assertEquals("A", next.stop.id)
        assertTrue(next.reasons.any { it.code == "FATIGUE_LAST_CHANCE_GAP" }, next.reasons.toString())
        assertTrue(Purpose.REST in next.purposes, next.purposes.toString())
    }

    @Test
    fun restBeforeALongJamWhenFatigueRunsOutInsideIt() {
        // 오후 3시, 70분 달렸다. 6km 앞 휴게소 바로 뒤에 60분짜리 정체, 정체 안에 쉼터가 없다. 다음 쉼터는 정체 끝 뒤
        val e = engine(afternoon, 70.0)
        val sim = HighwaySim(
            e, afternoon, routeLengthM = 200_000.0,
            stops = listOf(restArea("A", 6_000.0), restArea("B", 40_000.0)),
            jams = listOf(Jam(8_000.0, 20_000.0, 3_600.0)),
        )
        val p = assertNotNull(firstEnginePrompt(sim, 5_400.0))
        assertEquals(PromptKind.LAST_CHANCE, p.kind, p.speech)
        assertEquals("A", p.stop?.id)
        assertTrue(p.reasons.any { it.code == "FATIGUE_LAST_CHANCE_CONGESTION" }, p.reasons.toString())
    }

    @Test
    fun noFatigueLastChanceWhereRestStopsAreDense() {
        // 같은 피로인데 쉼터가 10km마다 있으면 미리 묻지 않는다 (바닥나면 그때 가까운 곳을 권한다)
        val e = engine(afternoon, 70.0)
        val stops = (1..12).map { restArea("S$it", it * 10_000.0) }
        val sim = HighwaySim(e, afternoon, routeLengthM = 130_000.0, stops = stops)
        sim.driveUntilPos(9_400.0)
        assertTrue(sim.prompts.none { it.third.kind == PromptKind.LAST_CHANCE }, sim.prompts.map { it.third.speech }.toString())
    }

    @Test
    fun aRestedDriverIsNotAskedBeforeAGapShorterThanTheGauge() {
        // 오전 10시, 막 출발했다 (피로 게이지 약 2시간). 6km 앞 휴게소 뒤 57분 공백, 목적지는 그 쉼터 직후
        val start = kst(2026, 11, 14, 10, 0)
        val e = engine(start, 0.0)
        val sim = HighwaySim(e, start, routeLengthM = 110_000.0, stops = listOf(restArea("A", 6_000.0), restArea("B", 106_000.0)))
        sim.driveUntilPos(5_400.0)
        assertTrue(sim.prompts.none { it.third.kind == PromptKind.LAST_CHANCE }, sim.prompts.map { it.third.speech }.toString())
    }

    @Test
    fun restroomAndFatigueAtTheSamePlaceAreOneQuestionWithBothReasons() {
        // 7살 아이와 피로한 운전자: 화장실의 마지막 기회와 졸음의 마지막 기회가 같은 휴게소면 질문은 하나, 이유는 둘
        val e = CoachEngine(
            profile = TripProfile(
                listOf(adult("driver"), child("kid", age = 7)), tripStart = afternoon - 70 * MIN, continuousDrivingMin = 70.0,
            ),
            random = Random(7),
        )
        val sim = HighwaySim(e, afternoon, routeLengthM = 250_000.0, stops = listOf(restArea("A", 6_000.0), restArea("B", 156_000.0)))
        val p = assertNotNull(firstEnginePrompt(sim, 5_400.0))
        assertEquals(PromptKind.LAST_CHANCE, p.kind)
        assertEquals("A", p.stop?.id)
        assertTrue(p.reasons.any { it.code.startsWith("LAST_CHANCE_") }, p.reasons.toString())
        assertTrue(p.reasons.any { it.code.startsWith("FATIGUE_LAST_CHANCE") }, p.reasons.toString())
    }
}
