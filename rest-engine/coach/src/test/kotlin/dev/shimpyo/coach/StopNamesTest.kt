package dev.shimpyo.coach

import dev.shimpyo.engine.StopKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TBT 이름 ↔ 공공데이터 이름 (데이터 감사 #2, #5). 예전 정규화는 방향을 괄호 안에 쓴 경우만 지웠다:
 * "안성휴게소 부산방향" → ""(아무것도 안 맞음), "오산졸음쉼터 상행" → "오산졸음쉼터", "서울방향 안성휴게소" → "서울방향안성".
 */
class StopNamesTest {

    @Test
    fun directionsAreRemovedWhereverTheyAreWritten() {
        val cases = mapOf(
            "안성휴게소(부산방향)" to "안성",
            "안성휴게소 부산방향" to "안성",
            "안성휴게소부산방향" to "안성",
            "안성휴게소-부산방향" to "안성",
            "안성휴게소 서울방면" to "안성",
            "서울방향 안성휴게소" to "안성",
            "서울방향안성휴게소" to "안성",
            "안성(부산)휴게소" to "안성",
            "안성 휴게소" to "안성",
            "안성휴게소 부산 방향" to "안성",
            "망향휴게소[부산]" to "망향",
            "오산졸음쉼터" to "오산",
            "오산 졸음쉼터" to "오산",
            "오산쉼터" to "오산",
            "오산졸음쉼터(상행)" to "오산",
            "오산졸음쉼터 상행" to "오산",
            "오산졸음쉼터 하행선" to "오산",
            "청주(서울)휴게소" to "청주",
            "천안삼거리휴게소(부산방향)" to "천안삼거리",
            "서울만남의광장휴게소" to "서울만남의광장",
            "입장거봉포도휴게소" to "입장거봉포도",
            "대신졸음쉼터 광주방향" to "대신",
        )
        for ((name, key) in cases) assertEquals(key, StopNames.key(name), name)
    }

    @Test
    fun aNameThatIsOnlyAFacilityWordOrADirectionIdentifiesNothing() {
        // 결정: 구별되는 부분이 없으면 null. 빈 문자열 키로 '모두와 맞음'이나 '조용히 아무것도 안 맞음'이 되지 않게
        // 부르는 쪽이 이유를 안다 (matchByName은 빈 목록, 데이터의 이름 없는 OSM 쉼터 4곳은 이름으로 찾지 않는다)
        for (n in listOf("졸음쉼터", "휴게소", "쉼터", "  졸음쉼터 ", "졸음쉼터(부산방향)", "부산방향", "상행", "", "(부산)")) {
            assertNull(StopNames.key(n), n)
        }
        assertEquals("", StopNames.normalizeName("졸음쉼터"), "예전 API는 빈 문자열")
    }

    @Test
    fun facilityKindComesFromTheName() {
        assertEquals(StopKind.DROWSY_SHELTER, StopNames.facilityKind("옥산졸음쉼터(서울방향)"))
        assertEquals(StopKind.DROWSY_SHELTER, StopNames.facilityKind("오산쉼터"))
        assertEquals(StopKind.HIGHWAY_REST_AREA, StopNames.facilityKind("옥산휴게소"))
        assertEquals(StopKind.HIGHWAY_REST_AREA, StopNames.facilityKind("덕평휴게시설"))
        assertNull(StopNames.facilityKind("하이패스센터"))
        // TBT가 쉼터를 SERVICEAREA로 보내도 이름의 시설어를 따른다 (옥산졸음쉼터 ↔ 옥산휴게소를 섞지 않게)
        assertEquals(StopKind.DROWSY_SHELTER, StopNames.kindFor("옥산졸음쉼터(서울방향)", StopKind.HIGHWAY_REST_AREA))
        assertEquals(StopKind.HIGHWAY_REST_AREA, StopNames.kindFor("안성", StopKind.HIGHWAY_REST_AREA))
        assertEquals(StopKind.DROWSY_SHELTER, StopNames.kindFor("오산쉼터", StopKind.HIGHWAY_REST_AREA))
        assertEquals(StopKind.HIGHWAY_REST_AREA, StopNames.kindFor("망향휴게소", StopKind.DROWSY_SHELTER))
        assertEquals(StopKind.DROWSY_SHELTER, StopNames.kindFor("서울만남의광장", StopKind.DROWSY_SHELTER), "이름으로 모르면 TBT 종류")
    }

    @Test
    fun restStopFilterKeepsTheOldRule() {
        assertTrue(StopNames.isRestStop("안성휴게소", StopKind.HIGHWAY_REST_AREA))
        assertTrue(StopNames.isRestStop("옥산졸음쉼터", StopKind.HIGHWAY_REST_AREA))
        assertTrue(StopNames.isRestStop("간이휴게시설", StopKind.HIGHWAY_REST_AREA), "휴게시설도 정차 시설 (facilityKind와 같은 규칙)")
        assertFalse(StopNames.isRestStop("서울하이패스센터", StopKind.HIGHWAY_REST_AREA))
        assertTrue(StopNames.isRestStop("오산졸음쉼터", StopKind.DROWSY_SHELTER))
        assertTrue(StopNames.isRestStop("간이휴게시설", StopKind.DROWSY_SHELTER))
        assertFalse(StopNames.isRestStop("안성휴게소", StopKind.PUBLIC_RESTROOM))
    }

    @Test
    fun routeOpportunitiesDelegates() {
        assertEquals("안성", RouteOpportunities.normalizeName("안성휴게소 부산방향"))
    }
}
