package dev.shimpyo.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 넘치면 덜 중요한 줄부터 뺀다 (UI 감사 #10: compact 세로 윈도에서 급해 결과 카드 아래 줄이 잘렸다) */
class FitColumnTest {

    @Test fun compactEnergyCardKeepsContextAndNameBeforeDistanceAndChips() {
        // WF portrait 800: header112 + margins/gaps80 + gauges210 + energy50 + actions200 = 652.
        // stop card148 - padding48 = 100. Context40 + gap8 + name52 must remain intact.
        val rows = listOf(100, 46, 72) // identity; distance incl. gap; chips incl. gap
        val keep = fitRows(rows, 0, maxHeight = 100, required = 1)
        assertEquals(listOf(true, false, false), keep)
        assertEquals(100, stackHeight(rows, keep, 0))
        assertEquals(listOf(true, true, false), fitRows(rows, 0, maxHeight = 150, required = 1))
    }

    @Test fun portrait17EnergyCardFitsEvenTwoChipRows() {
        // 840x1184: stop card376 - padding64 = 312. Two chip rows132 + leading gap24.
        val rows = listOf(104, 46, 156)
        val keep = fitRows(rows, 0, maxHeight = 312, required = 1)
        assertTrue(keep.all { it })
        assertTrue(stackHeight(rows, keep, 0) <= 312)
    }

    // 급해 결과 카드의 줄 높이(dp ≈ px, 1x): 이름 48, 시간 40, 소요 구성 38, 배지·여기로 60. 간격 8 (compact)
    private val card = listOf(48, 40, 38, 60)
    private val urgentPriority = listOf(0, 1, 3, 2)

    @Test
    fun keepsEverythingWhenItFits() {
        assertEquals(listOf(true, true, true, true), fitRows(card, 8, maxHeight = 300, required = 2, priority = urgentPriority))
    }

    @Test
    fun dropsBreakdownBeforeBadgesAndNeverOverflowsWhileOptionalRowsRemain() {
        // compact 세로 칸: 카드 236dp - 안쪽 여백 48 = 188
        val keep = fitRows(card, 8, maxHeight = 188, required = 2, priority = urgentPriority)
        assertEquals(listOf(true, true, false, true), keep)
        assertTrue(stackHeight(card, keep, 8) <= 188)
        // 더 좁으면 배지 줄도 빼고 이름·시간만
        val tight = fitRows(card, 8, maxHeight = 120, required = 2, priority = urgentPriority)
        assertEquals(listOf(true, true, false, false), tight)
        assertTrue(stackHeight(card, tight, 8) <= 120)
    }

    @Test
    fun defaultPriorityIsTopDownAndRequiredRowsStay() {
        assertEquals(listOf(true, false), fitRows(listOf(200, 60), 24, maxHeight = 220, required = 1))
        // 필수 줄만으로 넘쳐도 필수 줄은 남긴다
        assertEquals(listOf(true, false), fitRows(listOf(300, 60), 24, maxHeight = 220, required = 1))
        // 높이 0(빈 칩 묶음)은 늘 빠진다
        assertEquals(listOf(true, false), fitRows(listOf(100, 0), 24, maxHeight = null, required = 1))
    }
}
