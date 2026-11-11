package dev.shimpyo.app.ui

import dev.shimpyo.app.ui.theme.DriveLimits
import dev.shimpyo.engine.CoachTexts
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.Reason
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면 문구. 엔진의 실제 문장(CoachTexts)으로 짧은 문구를 검사한다 (UI 감사 #17) */
class LabelsTest {
    private val t = CoachTexts()

    /** 주행 화면 칩·게이지 한 줄에 나올 수 있는 이유 (엔진이 실제로 내는 문장) */
    private fun driveReasons(): List<Reason> = listOf(
        Reason("RESTROOM_ELAPSED", t.reasonRestroomElapsed("7살 아이", 46)),
        Reason("RESTROOM_VOICED", t.reasonRestroomVoiced("아이")),
        Reason("RESTROOM_PERSONAL", t.reasonRestroomPersonal("운전자", 3)),
        Reason("RESTROOM_CAP", t.reasonRestroomCap(Occupant("e", OccupantType.ELDERLY), 90)),
        Reason("RESTROOM_CAP", t.reasonRestroomCap(Occupant("b", OccupantType.INFANT), 90)),
        Reason("FATIGUE_VOICED", t.reasonFatigueVoiced()),
        Reason("FATIGUE_STRONG", t.reasonFatigueStrong()),
        Reason("FATIGUE_DRIVING", t.reasonFatigueDriving(108)),
        Reason("FATIGUE_NIGHT", t.reasonFatigueNight()),
        Reason("FATIGUE_AFTERNOON", t.reasonFatigueAfternoon()),
        Reason("FATIGUE_BODY_CLOCK", t.reasonFatigueBodyClock(9)),
        Reason("FATIGUE_AWAKE_LONG", t.reasonFatigueAwakeLong(16)),
        Reason("FATIGUE_SHORT_SLEEP", t.reasonFatigueShortSleep(5f)),
        Reason("FATIGUE_STYLE", t.reasonFatigueStyle()),
        Reason("FATIGUE_MONOTONY", t.reasonFatigueMonotony()),
        Reason("CABIN_RECIRC", t.reasonCabinRecirc(30)),
        Reason("LAST_CHANCE_CONGESTION", t.reasonLastChanceCongestion(60)),
        Reason("LAST_CHANCE_GAP", t.reasonLastChanceGap(50)),
        Reason("LAST_CHANCE_PREFERENCE", t.reasonLastChancePreference(50, 45)),
        Reason("NEXT_ACCEPTED", t.reasonNextAccepted()),
        Reason("NEXT_NEAREST_RESTROOM", t.reasonNextNearestRestroom()),
        Reason("NEXT_REST", t.reasonNextRest()),
        Reason("NEXT_MEAL", t.reasonNextMeal()),
        Reason("URGENT_OPEN_24H", t.reasonUrgentOpen24h()),
        Reason("URGENT_SAFETY", t.reasonUrgentSafety(bell = true, cctv = true)),
        Reason("URGENT_DIAPER", t.reasonUrgentDiaper()),
    )

    @Test
    fun everyDriveReasonHasAShortLabelWithinTheChipLimit() {
        driveReasons().forEach { r ->
            val s = Labels.shortReason(r, "아이")
            assertNotNull("${r.code} has no short label", s)
            assertTrue("${r.code} → '$s'", s!!.length <= DriveLimits.MAX_CHIP_CHARS)
        }
    }

    @Test
    fun longDurationsAndLongNamesStillFitChipLimit() {
        listOf(90, 70, 125, 600 + 35).forEach { min ->
            listOf(
                Reason("LAST_CHANCE_CONGESTION", t.reasonLastChanceCongestion(min)),
                Reason("LAST_CHANCE_GAP", t.reasonLastChanceGap(min)),
                Reason("FATIGUE_DRIVING", t.reasonFatigueDriving(min)),
                Reason("RESTROOM_CAP", t.reasonRestroomCap(Occupant("e", OccupantType.ELDERLY), min)),
                Reason("RESTROOM_CAP", t.reasonRestroomCap(Occupant("b", OccupantType.INFANT), min)),
            ).forEach { r ->
                val s = Labels.shortReason(r)!!
                assertTrue("${r.code} → '$s' (${s.length})", s.length <= DriveLimits.MAX_CHIP_CHARS)
            }
        }
        // 긴 이름이면 '탑승자 기준'으로 줄인다 (칩이 잘리지 않게)
        assertEquals("탑승자 기준", Labels.shortReason(Reason("RESTROOM_ELAPSED", "…"), "우리집 둘째 꼬마 손님"))
    }

    @Test
    fun durationChipsPreferWordsAndFallBackToClock() {
        assertEquals("정체 1시간", Labels.shortReason(Reason("LAST_CHANCE_CONGESTION", t.reasonLastChanceCongestion(60))))
        assertEquals("정체 1시간 30분", Labels.shortReason(Reason("LAST_CHANCE_CONGESTION", t.reasonLastChanceCongestion(90))))
        assertEquals("공백 50분", Labels.shortReason(Reason("LAST_CHANCE_GAP", t.reasonLastChanceGap(50))))
        assertEquals("1시간 30분마다 걷기", Labels.shortReason(Reason("RESTROOM_CAP", t.reasonRestroomCap(Occupant("e", OccupantType.ELDERLY), 90))))
        assertEquals("카시트 1:30 상한", Labels.shortReason(Reason("RESTROOM_CAP", t.reasonRestroomCap(Occupant("b", OccupantType.INFANT), 90))))
        assertEquals("깬 지 16시간+", Labels.shortReason(Reason("FATIGUE_AWAKE_LONG", t.reasonFatigueAwakeLong(16))))
    }

    @Test
    fun noDiseaseNameLeaks() {
        val oab = Occupant("x", OccupantType.OAB, label = "과민성 방광")
        assertEquals("민감 탑승자", Labels.publicName(oab)) // 유형을 먼저 본다 (label이 질환 이름이어도)
        assertEquals("민감 탑승자", Labels.companionChip(oab))
        // 상한 이유 원문에는 탑승자 이름(질환 이름)이 들어간다: 칩은 시간만 쓴다
        val cap = Reason("RESTROOM_CAP", t.reasonRestroomCap(oab, 45))
        assertTrue(cap.text.contains("방광"))
        assertFalse(Labels.shortReason(cap)!!.contains("방광"))
        // 모르는 코드는 원문을 내보내지 않는다 (예전에는 12자 이하 원문을 그대로 칩에 넣었다)
        assertNull(Labels.shortReason(Reason("SOMETHING_NEW", "과민성 방광")))
    }

    @Test
    fun durationParsing() {
        assertEquals(108, Labels.durationIn(t.reasonFatigueDriving(108)))
        assertEquals(60, Labels.durationIn(t.reasonLastChanceCongestion(60)))
        assertEquals(40, Labels.durationIn("40분 정체"))
        assertEquals(125, Labels.durationIn("2시간 5분째"))
        assertNull(Labels.durationIn("정체"))
        // 나이 숫자("7살")는 시간으로 읽지 않는다
        assertEquals(46, Labels.durationIn(t.reasonRestroomElapsed("7살 아이", 46)))
    }

    @Test
    fun shortReasonValues() {
        assertEquals("연속 1:48", Labels.shortReason(Reason("FATIGUE_DRIVING", t.reasonFatigueDriving(108))))
        assertEquals("연속 45분", Labels.shortReason(Reason("FATIGUE_DRIVING", t.reasonFatigueDriving(45))))
        assertEquals("기록 3번 반영", Labels.shortReason(Reason("RESTROOM_PERSONAL", t.reasonRestroomPersonal("7살 아이", 3))))
        assertEquals("비상벨·CCTV", Labels.shortReason(Reason("URGENT_SAFETY", t.reasonUrgentSafety(true, true))))
        assertEquals("CCTV", Labels.shortReason(Reason("URGENT_SAFETY", t.reasonUrgentSafety(false, true))))
        // 들르기를 받은 것과 경유지가 들어간 것은 다르다 (UI 감사 #3)
        assertEquals("들르기로 함", Labels.shortReason(Reason("NEXT_ACCEPTED", t.reasonNextAccepted())))
    }

    @Test
    fun readoutAndParticles() {
        assertEquals(Labels.Readout("--", "분"), Labels.readout(null))
        assertEquals(Labels.Readout("119", "분"), Labels.readout(119))
        assertEquals(Labels.Readout("2", "시간+"), Labels.readout(120))
        assertEquals(Labels.Readout("0", "분"), Labels.readout(0))
        assertEquals("안성휴게소를", Labels.withObject("안성휴게소"))
        assertEquals("옥산휴게소(부산방향)를", Labels.withObject("옥산휴게소(부산방향)"))
        assertEquals("죽암을", Labels.withObject("죽암"))
        assertEquals("", Labels.withObject(""))
    }

    private fun cand(kind: StopKind, route: Double? = null, straight: Double? = null, dir: Boolean? = null, detour: Double = 0.0) =
        StopCandidate("s", "s", kind, 0.0, 0.0, Facilities(), true, routeDistanceM = route, straightDistanceM = straight, directionOk = dir, detourS = detour)

    @Test
    fun positionAndDistance() {
        assertEquals("유턴 필요", Labels.stopPosition(cand(StopKind.GAS_STATION, dir = false, detour = 300.0)))
        assertEquals("+5분 우회", Labels.stopPosition(cand(StopKind.GAS_STATION, dir = true, detour = 300.0)))
        assertEquals("경로 위", Labels.stopPosition(cand(StopKind.HIGHWAY_REST_AREA, dir = true)))
        assertEquals("진행 방향", Labels.stopPosition(cand(StopKind.PUBLIC_RESTROOM, dir = true)))
        assertNull(Labels.stopPosition(cand(StopKind.PUBLIC_RESTROOM)))
        assertEquals("4.2km", Labels.stopDistance(cand(StopKind.HIGHWAY_REST_AREA, route = 4_200.0)))
        assertTrue(Labels.stopDistance(cand(StopKind.HIGHWAY_REST_AREA, straight = 3_000.0))!!.startsWith("약 "))
        assertNull(Labels.stopDistance(cand(StopKind.HIGHWAY_REST_AREA)))
    }

    @Test
    fun baselineUsesPersonalIntervalThenTypePrior() {
        assertEquals(Labels.config.priorChildYoungMin.toInt(), Labels.baselineMin(Occupant("c", OccupantType.CHILD, ageYears = 6)))
        assertEquals(Labels.config.priorChildOlderMin.toInt(), Labels.baselineMin(Occupant("c", OccupantType.CHILD, ageYears = 7)))
        assertEquals(Labels.config.priorAdultMin.toInt(), Labels.baselineMin(null))
        assertEquals(75, Labels.baselineMin(Occupant("me", OccupantType.ADULT, intervalOverrideMin = 75.0)))
    }
}
