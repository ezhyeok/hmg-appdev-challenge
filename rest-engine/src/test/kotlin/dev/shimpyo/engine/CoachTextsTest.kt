package dev.shimpyo.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoachTextsTest {
    private val t = CoachTexts()

    @Test
    fun durationAndDistanceFormatting() {
        assertEquals("0분", t.duration(0))
        assertEquals("40분", t.duration(40))
        assertEquals("1시간", t.duration(60))
        assertEquals("1시간 50분", t.duration(110))
        assertEquals("0분", t.duration(-5))
        assertEquals("100m", t.distance(30.0))
        assertEquals("800m", t.distance(780.0))
        assertEquals("1km", t.distance(960.0))
        assertEquals("3.5km", t.distance(3_450.0))
        assertEquals("12km", t.distance(12_300.0))
    }

    @Test
    fun particlesFollowFinalConsonant() {
        assertFalse(t.hasFinalConsonant("안성휴게소"))
        assertTrue(t.hasFinalConsonant("화장실"))
        assertTrue(t.hasFinalConsonant("OO주유소(부산방향)").not(), "괄호 주석은 건너뛴다")
        assertTrue(t.hasFinalConsonant("40분"))
        assertFalse(t.hasFinalConsonant("12km"), "킬로미터")
        assertTrue(t.hasFinalConsonant("휴게소 3"), "삼")
        assertFalse(t.hasFinalConsonant(""))
    }

    @Test
    fun exampleSentencesMatchTheSpec() {
        assertEquals(
            "앞에 40분 정체가 있어요. 다음 안성휴게소가 정체 전 마지막 화장실이에요. 들를까요?",
            t.lastChanceCongestion("안성휴게소", 40),
        )
        assertEquals(
            "1시간 50분째 쉬지 않으셨어요. 12km 앞 졸음쉼터에서 쉬어 갈까요?",
            t.restSuggestion(110, "졸음쉼터", 12_000.0),
        )
        assertEquals("화장실을 지나면 50분 동안 화장실이 없어요. 들를까요?", t.lastChanceGap("화장실", 50))
        assertEquals("내기순환이 30분째예요. 잠깐 환기할까요?", t.freshAir(30))
        assertEquals("운전 패턴이 이번 주행 초반과 조금 달라요. 혹시 졸리세요?", t.checkIn(80, byStyle = true))
        assertEquals("1시간 40분째 운전 중이에요. 혹시 졸리세요?", t.checkIn(100, byStyle = false))
    }

    @Test
    fun sleepyAdviceMentionsNapAndOptionallySwap() {
        val solo = t.restSuggestionSleepy("망향휴게소", 5_000.0)
        assertTrue(solo.contains("15분 이내") && solo.contains("커피") && solo.endsWith("들를까요?"), solo)
        assertFalse(solo.contains("운전을 바꾸"))
        assertTrue(t.restSuggestionSleepy(null, null, canSwapDriver = true).contains("운전을 바꾸"))
    }

    @Test
    fun noDetectionClaimsInAnyText() {
        // ai-science.md 10장: "졸음을 감지한다"고 말하지 않는다
        val samples = listOf(
            t.checkIn(90, true), t.checkIn(90, false), t.freshAir(40), t.reasonFatigueStyle(), t.reasonFatigueStrong(),
            t.reasonStyleSteeringMicro(), t.reasonStyleSteeringReversal(), t.reasonStyleSpeed(), t.reasonStyleBrake(),
            t.reasonCabinRecirc(40), t.reasonFatigueBodyClock(18), t.planSleepyTime(), t.restSuggestionSleepy("a", 3_000.0),
        )
        for (s in samples) assertFalse(s.contains("감지"), s)
        assertFalse(t.freshAir(40).contains("졸"), "환기를 졸음과 엮지 않는다")
        assertFalse(t.reasonCabinRecirc(40).contains("졸"))
    }

    @Test
    fun howLongDescribesAnEstimatedRecommendationNotASafeDrivingLimit() {
        val answer = t.howLong(50, "운전자", 40, null, null)
        assertTrue(answer.contains("화장실 여유는 약 50분"))
        assertTrue(answer.contains("휴식 권고까지는 약 40분"))
        assertFalse(answer.contains("운전할 수 있는 시간"))
    }

    @Test
    fun textsCanBeOverridden() {
        val custom = object : CoachTexts() {
            override fun checkIn(drivenMin: Int, byStyle: Boolean) = "Feeling sleepy?"
        }
        assertEquals("Feeling sleepy?", custom.checkIn(10, false))
        assertEquals("40분", custom.duration(40))
    }
}
