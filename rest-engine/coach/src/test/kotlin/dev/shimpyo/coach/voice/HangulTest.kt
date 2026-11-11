package dev.shimpyo.coach.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 받침에 맞는 조사. 엔진 질문·들르기 결과 문장에 정차 이름을 넣을 때 쓴다 (규칙은 Hangul 한 곳) */
class HangulTest {
    @Test
    fun particlesFollowTheFinalConsonant() {
        // 받침 없음
        assertEquals("안성휴게소를", Hangul.eulReul("안성휴게소"))
        assertEquals("안성휴게소가", Hangul.iGa("안성휴게소"))
        assertEquals("안성휴게소는", Hangul.eunNeun("안성휴게소"))
        assertEquals("안성휴게소로", Hangul.euRo("안성휴게소"))
        // 받침 있음
        assertEquals("강남역을", Hangul.eulReul("강남역"))
        assertEquals("강남역이", Hangul.iGa("강남역"))
        assertEquals("강남역은", Hangul.eunNeun("강남역"))
        assertEquals("강남역으로", Hangul.euRo("강남역"))
        // ㄹ 받침은 '로'
        assertEquals("화장실로", Hangul.euRo("화장실"))
        assertEquals("화장실은", Hangul.eunNeun("화장실"))
    }

    @Test
    fun theLastHangulSyllableDecides() {
        // 괄호·공백 뒤가 아니라 마지막 한글 음절을 본다: "옥산휴게소(부산방향)"의 마지막 음절은 '향'
        assertEquals("옥산휴게소(부산방향)은", Hangul.eunNeun("옥산휴게소(부산방향)"))
        assertEquals("칠곡휴게소(대구) 는", Hangul.eunNeun("칠곡휴게소(대구) "))
        assertTrue("숫자 앞의 마지막 한글 음절을 본다", Hangul.hasBatchim("강남역 2"))
        assertFalse(Hangul.hasBatchim("졸음쉼터 2"))
        assertFalse("한글이 없으면 받침 없음으로 본다", Hangul.hasBatchim("P3"))
        assertEquals("P3는", Hangul.eunNeun("P3"))
    }
}
