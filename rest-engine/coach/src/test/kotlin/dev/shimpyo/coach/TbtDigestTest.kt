package dev.shimpyo.coach

import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TBT 응답 정리: 누적 거리, 덮는 거리, 깨진 거리 (데이터 감사 #7, 서비스 감사 P5) */
class TbtDigestTest {

    private fun item(type: TbtType, d: Double, name: String = "") = TbtItem(type, d, name)

    @Test
    fun distancesAccumulateAndCoverageReachesTheLastItem() {
        val d = TbtDigest.of(
            listOf(
                item(TbtType.OTHER, 1_200.0, "기흥IC"),
                item(TbtType.SERVICE_AREA, 20_000.0, "안성휴게소(부산방향)"),
                item(TbtType.SERVICE_AREA, 500.0, "안성하이패스센터"), // 화장실 없는 서비스 시설
                item(TbtType.REST_AREA, 10_000.0, "망향졸음쉼터"),
                item(TbtType.OTHER, 3_000.0, "목적지"),
            ),
        )
        assertEquals(
            listOf(Triple("안성휴게소(부산방향)", StopKind.HIGHWAY_REST_AREA, 21_200.0), Triple("망향졸음쉼터", StopKind.DROWSY_SHELTER, 31_700.0)),
            d.opportunities.map { Triple(it.name, it.kind, it.routeDistanceM) },
        )
        assertEquals(1_200.0, d.nextManeuverM!!, 0.0)
        assertEquals(34_700.0, d.coverageM, 1e-9)
        assertFalse(d.truncated)
    }

    @Test
    fun theNameDecidesTheKindWhenItIsClear() {
        // 데이터 감사 #5: SERVICEAREA로 온 졸음쉼터를 휴게소로 보면 같은 이름의 반대 차로 휴게소와 짝이 된다
        val kinds = RouteOpportunities.extract(
            listOf(
                item(TbtType.SERVICE_AREA, 1_000.0, "옥산졸음쉼터(서울방향)"),
                item(TbtType.SERVICE_AREA, 1_000.0, "옥산휴게소(부산방향)"),
                item(TbtType.REST_AREA, 1_000.0, "망향졸음쉼터"),
                item(TbtType.REST_AREA, 1_000.0, "죽암휴게소"),
                item(TbtType.SERVICE_AREA, 1_000.0, "안성하이패스센터"), // 화장실 없는 서비스 시설: 뺀다
                item(TbtType.REST_AREA, 1_000.0, "간이휴게시설"), // '휴게시설' = 휴게소 (StopNames.facilityKind)
                item(TbtType.OTHER, 1_000.0, "신탄진졸음쉼터 방면"), // 회전 안내는 시설이 아니다
            ),
        ).map { it.name to it.kind }
        assertEquals(
            listOf(
                "옥산졸음쉼터(서울방향)" to StopKind.DROWSY_SHELTER,
                "옥산휴게소(부산방향)" to StopKind.HIGHWAY_REST_AREA,
                "망향졸음쉼터" to StopKind.DROWSY_SHELTER,
                "죽암휴게소" to StopKind.HIGHWAY_REST_AREA,
                "간이휴게시설" to StopKind.HIGHWAY_REST_AREA,
            ),
            kinds,
        )
    }

    @Test
    fun anEmptyListCoversNothing() {
        val d = TbtDigest.of(emptyList())
        assertTrue(d.opportunities.isEmpty())
        assertNull(d.nextManeuverM)
        assertEquals(0.0, d.coverageM, 0.0)
    }

    @Test
    fun readingStopsAtABrokenDistance() {
        // 예전: NaN이 coerceAtLeast를 통과해 뒤의 모든 누적 거리가 NaN이 됐다
        val tbt = listOf(
            item(TbtType.SERVICE_AREA, 5_000.0, "옥산휴게소"),
            item(TbtType.OTHER, Double.NaN, "?"),
            item(TbtType.SERVICE_AREA, 3_000.0, "죽암휴게소"),
        )
        val d = TbtDigest.of(tbt)
        assertEquals(listOf("옥산휴게소" to 5_000.0), d.opportunities.map { it.name to it.routeDistanceM })
        assertEquals(5_000.0, d.coverageM, 0.0)
        assertTrue(d.truncated)
        assertEquals("extract도 같은 규칙", d.opportunities, RouteOpportunities.extract(tbt))
        for (bad in listOf(-1.0, Double.POSITIVE_INFINITY)) {
            assertEquals(listOf("옥산휴게소"), TbtDigest.of(tbt.toMutableList().also { it[1] = item(TbtType.OTHER, bad) }).opportunities.map { it.name })
        }
    }

    @Test
    fun aBrokenFirstDistanceLeavesNoManeuver() {
        assertNull(RouteOpportunities.nextManeuverDistance(listOf(item(TbtType.OTHER, Double.NaN), item(TbtType.OTHER, 100.0))))
        assertEquals("0은 정상 (같은 지점의 두 안내)", 0.0, RouteOpportunities.nextManeuverDistance(listOf(item(TbtType.OTHER, 0.0)))!!, 0.0)
    }
}
