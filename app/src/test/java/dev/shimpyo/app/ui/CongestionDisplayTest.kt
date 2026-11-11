package dev.shimpyo.app.ui

import dev.shimpyo.app.ui.components.StripSpanKind
import dev.shimpyo.app.ui.drive.stopCard
import dev.shimpyo.app.ui.plan.TimelineRow
import dev.shimpyo.app.ui.plan.timeline
import dev.shimpyo.coach.CandidatesState
import dev.shimpyo.coach.CoachUiState
import dev.shimpyo.coach.CongestionBasis
import dev.shimpyo.coach.NaviCongestion
import dev.shimpyo.engine.CongestionSegment
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 화면에 보이는 정체 (displayJams). 2026-11-12 에뮬레이터에서 실제 경로(판교→부산역)를 켜자 계획 타임라인에
 * "정체 0분 · 대왕판교로 · 400m 앞부터 100m"가 여덟 줄 이어지고 경로 띠에 "정체 0분"·"정체 1분"이 붙었다 (FIXLIST U1).
 * 규칙: 1km 안으로 이어진 항목은 한 정체로 묶고(길이·지나는 시간은 합, 이름은 가장 긴 항목), 지나는 데 5분 이상인 것만 보인다.
 */
class CongestionDisplayTest {

    /**
     * 그날 `congestion items:` 로그 그대로 (offset m / 길이 m / 늘어난 시간 초 / 평균 속도 km/h).
     * 코치 모듈 CongestionArrivalTest와 같은 값이다
     */
    private val measured = "400/100/60/38.3 600/40/60/35.5 1600/60/60/13.7 1600/30/60/61.3 2000/40/60/51.8 2000/100/60/54.1 2400/200/60/35.8 " +
        "2600/70/60/10.1 47000/100/60/9.0 47000/100/60/9.1 97000/40/60/81.9 98000/100/60/12.2 98000/100/60/51.9 98000/40/60/25.5 " +
        "98000/80/60/52.0 98000/100/60/27.1 98000/70/60/25.9 98000/100/60/15.8 98000/100/60/39.0 99000/100/60/26.7 99000/10/60/70.9 " +
        "99000/100/60/20.3 99000/100/60/27.5 99000/100/60/16.4 99000/100/60/60.4 100000/50/60/30.9 100000/200/60/38.5 100000/80/60/19.4 " +
        "100000/70/60/67.5 460000/1700/60/109.6 473000/30/60/13.6 473000/100/60/16.2 474000/600/60/46.5 474000/600/60/37.0 475000/300/60/30.0"

    /** 코치가 화면에 올리는 모양 그대로: 포트 경계(NaviCongestion.fromNavi, 지나는 시간 = 길이 ÷ 평균 속도) → CongestionBasis */
    private fun deviceLogged(): List<CongestionSegment> {
        val items = measured.split(' ').map { it.split('/').map(String::toDouble) }.mapNotNull { (off, len, delay, avr) ->
            NaviCongestion.fromNavi(off, len, delay, avr, "HEAVY", "", "", "")
        }
        return CongestionBasis().update(items, remainM = 383_000.0, odometerM = 0.0)
    }

    private fun opp(id: String, km: Double) = StopCandidate(
        id, id, StopKind.HIGHWAY_REST_AREA, 0.0, 0.0, Facilities(), true, routeDistanceM = km * 1_000.0, directionOk = true,
    )

    private fun jam(startM: Double, lengthM: Double, seconds: Double, road: String = "", from: String = "", to: String = "") =
        CongestionSegment(startM, lengthM, seconds, "HEAVY", road, from, to)

    @Test
    fun theDeviceLoggedSlowPatchesAreNotDrawnAsJams() {
        val raw = deviceLogged()
        assertEquals("전제: 내비가 준 35항목", 35, raw.size)
        // 1km 안으로 이어진 항목을 묶으면 다섯 덩어리이고, 가장 긴 것(97~100km, 19항목 1,640m)도 지나는 데 3분 40초다
        assertTrue(displayJams(raw).toString(), displayJams(raw).isEmpty())
        val state = CoachUiState(
            serviceRunning = true, guiding = true, candidatesState = CandidatesState.READY, destinationName = "부산역",
            remainDistanceM = 383_000.0, remainTimeS = 4 * 3_600.0 + 10 * 60.0, congestion = raw,
            ahead = listOf(opp("giheung", 20.0), opp("anseong", 45.0), opp("manghyang", 64.0)),
        )
        val rows = timeline(state)
        assertTrue("타임라인에 정체 줄이 없다: $rows", rows.none { it is TimelineRow.Congestion })
        assertTrue(stripData(state).spans.none { it.kind == StripSpanKind.CONGESTION })
        assertTrue(stripData(state, maxAxisM = 120_000.0, nearFocus = true).spans.none { it.kind == StripSpanKind.CONGESTION })
        // 2.4km 앞의 느린 200m(20초) 때문에 20km 앞 휴게소에 '정체 뒤'를 붙이지 않는다
        assertEquals(emptyList<String>(), stopCard(state, driving = true).chips)
    }

    @Test
    fun noLabelEverSaysZeroMinutes() {
        // 30~100m 항목은 지나는 데 몇 초라 분으로 반올림하면 0이 된다
        deviceLogged().forEach { c -> assertNotEquals("${c.startDistanceM}m", "정체 0분", congestionLabel(c)) }
        assertEquals("정체 1분", congestionLabel(jam(400.0, 100.0, 9.4)))
        assertEquals("정체 5분", congestionLabel(jam(400.0, 3_000.0, 300.0)))
    }

    @Test
    fun itemsWithinOneKilometreBecomeOneJamNamedAfterTheLongest() {
        // 내비는 한 정체를 도로 구간마다 쪼개 보낸다: 3km 앞부터 4km·6분, 500m 띄고 8km·14분, 바로 이어 2km·4분
        val jams = displayJams(
            listOf(
                jam(15_500.0, 2_000.0, 240.0, "경부고속도로", "남이JC", "죽암휴게소"),
                jam(3_000.0, 4_000.0, 360.0, "경부고속도로", "옥산JC", "청주IC"),
                jam(7_500.0, 8_000.0, 840.0, "경부고속도로", "청주IC", "남이JC"),
            ),
        )
        val j = jams.single()
        assertEquals(3_000.0, j.startDistanceM, 0.0)
        assertEquals("길이는 항목의 합", 14_000.0, j.lengthM, 0.0)
        assertEquals("지나는 시간은 항목의 합", 1_440.0, j.durationS, 0.0)
        assertEquals("이름은 가장 긴 항목의 것", "청주IC–남이JC", congestionPlace(j))
        assertEquals("정체 24분", congestionLabel(j))
        assertEquals("청주IC–남이JC · 3km 앞부터 14km", congestionDetail(j))
    }

    @Test
    fun itemsMoreThanOneKilometreApartStaySeparate() {
        val jams = displayJams(listOf(jam(3_000.0, 4_000.0, 600.0), jam(8_001.0, 5_000.0, 900.0)))
        assertEquals(listOf(3_000.0, 8_001.0), jams.map { it.startDistanceM })
        // 딱 1km 떨어진 것까지 묶는다 (엔진 GapAnalyzer와 같은 기준)
        assertEquals(1, displayJams(listOf(jam(3_000.0, 4_000.0, 600.0), jam(8_000.0, 5_000.0, 900.0))).size)
    }

    @Test
    fun onlyJamsThatTakeFiveMinutesOrMoreAreShown() {
        assertTrue(displayJams(listOf(jam(3_000.0, 2_000.0, 299.0))).isEmpty())
        assertEquals(1, displayJams(listOf(jam(3_000.0, 2_000.0, 300.0))).size)
        // 짧은 항목도 이어져 5분이 넘으면 한 정체로 보인다: 100m·40초짜리 8개 (5분 20초)
        val pieces = (0 until 8).map { jam(2_000.0 + it * 100.0, 100.0, 40.0) }
        val j = displayJams(pieces).single()
        assertEquals(800.0, j.lengthM, 0.0)
        assertEquals("정체 5분", congestionLabel(j))
    }

    @Test
    fun overlappingRoundedItemsAreLaidEndToEndNotCollapsed() {
        // 내비 offset은 10km 밖에서 1km 단위다: 같은 98km에 서로 다른 항목 셋. 겹치면 앞 항목 끝에 이어 놓는다 (엔진 E3과 같은 규칙)
        val j = displayJams(listOf(jam(98_000.0, 2_000.0, 200.0), jam(98_000.0, 1_500.0, 150.0), jam(98_000.0, 500.0, 100.0))).single()
        assertEquals(4_000.0, j.lengthM, 0.0)
        assertEquals(450.0, j.durationS, 0.0)
        // 똑같은 항목이 두 번 온 것은 한 번만 센다
        val twice = displayJams(listOf(jam(98_000.0, 2_000.0, 320.0), jam(98_000.0, 2_000.0, 320.0))).single()
        assertEquals(2_000.0, twice.lengthM, 0.0)
        assertEquals(320.0, twice.durationS, 0.0)
        // 겹쳐서 뒤로 밀린 끝(101.5km)에서 1km 안에 시작하는 항목(102.4km)도 같은 정체다
        assertEquals(1, displayJams(listOf(jam(98_000.0, 2_000.0, 200.0), jam(98_000.0, 1_500.0, 150.0), jam(102_400.0, 1_000.0, 100.0))).size)
    }

    @Test
    fun aJamTheCarIsInCountsOnlyWhatIsLeft() {
        // 18km·60분 정체의 6km 지점: 남은 12km·40분
        val j = displayJams(listOf(jam(-6_000.0, 18_000.0, 3_600.0))).single()
        assertEquals(0.0, j.startDistanceM, 0.0)
        assertEquals(12_000.0, j.lengthM, 0.0)
        assertEquals(2_400.0, j.durationS, 1.0)
        // 2분 남았으면 더는 그리지 않는다. 지나간 정체도 없다
        assertTrue(displayJams(listOf(jam(-17_400.0, 18_000.0, 3_600.0))).isEmpty())
        assertTrue(displayJams(listOf(jam(-20_000.0, 10_000.0, 3_600.0))).isEmpty())
    }

    @Test
    fun stripAndTimelineDrawTheMergedJamOnce() {
        val state = CoachUiState(
            serviceRunning = true, guiding = true, candidatesState = CandidatesState.READY,
            remainDistanceM = 200_000.0, remainTimeS = 9_000.0, ahead = listOf(opp("a", 2.0), opp("b", 30.0)),
            congestion = listOf(
                jam(3_000.0, 4_000.0, 360.0, "경부고속도로"),
                jam(7_500.0, 8_000.0, 840.0, "경부고속도로"),
                jam(400.0, 100.0, 9.0, "대왕판교로"),
            ),
        )
        val span = stripData(state).spans.single { it.kind == StripSpanKind.CONGESTION }
        assertEquals(3_000.0, span.startM, 0.0)
        assertEquals(15_000.0, span.endM, 0.0)
        assertEquals("정체 20분", span.label)
        val row = timeline(state).filterIsInstance<TimelineRow.Congestion>().single()
        assertEquals(3_000.0, row.distanceM, 0.0)
        assertEquals(12_000.0, row.segment.lengthM, 0.0)
    }

    @Test
    fun aGapIsCalledCongestedOnlyByAJamThatIsShown() {
        // a(10km)와 b(60km) 사이에 100m·9초 느린 구간만 있다: '정체를 지나는 동안 화장실이 없어요'가 아니고, 띠에도 공백으로 그린다
        val base = CoachUiState(guiding = true, remainDistanceM = 200_000.0, remainTimeS = 9_000.0, ahead = listOf(opp("a", 10.0), opp("b", 60.0)))
        val slow = base.copy(congestion = listOf(jam(30_000.0, 100.0, 9.0)))
        assertEquals(false, restroomGaps(slow).single { it.startM == 10_000.0 }.congested)
        assertTrue(stripData(slow).spans.any { it.kind == StripSpanKind.GAP })
        val jammed = base.copy(congestion = listOf(jam(30_000.0, 10_000.0, 2_400.0)))
        assertEquals(true, restroomGaps(jammed).single { it.startM == 10_000.0 }.congested)
    }

    @Test
    fun theJoinDistanceIsTheEnginesOwn() {
        // 엔진은 1km 안으로 이어진 항목을 한 정체로 보고 묻는다(GapAnalyzer.JAM_JOIN_M, internal이라 직접 못 본다).
        // 화면이 다른 기준으로 그리면 '정체 전 마지막 화장실'이라고 묻는데 띠에는 정체가 둘로 보인다
        val root = File("../rest-engine/src/main/kotlin")
        assertTrue("엔진 소스를 찾지 못했다: ${root.absolutePath}", root.isDirectory)
        val found = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .firstNotNullOfOrNull { Regex("const val JAM_JOIN_M = ([0-9_.]+)").find(it.readText()) }
        assertNotNull("엔진의 JAM_JOIN_M을 찾지 못했다 (이름이 바뀌었으면 RouteModel.JAM_JOIN_M과 이 시험을 함께 고칠 것)", found)
        assertEquals(JAM_JOIN_M, found!!.groupValues[1].replace("_", "").toDouble(), 0.0)
    }
}
