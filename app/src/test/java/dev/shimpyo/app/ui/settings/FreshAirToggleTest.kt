package dev.shimpyo.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 외기 전환 설정 (UI 감사 #16, 재검토 #8) */
class FreshAirToggleTest {
    @Test
    fun unsupportedCarCannotTurnOnButCanTurnOff() {
        assertTrue(freshAirToggle(cabinSupported = true, on = false).enabled)
        assertFalse(freshAirToggle(cabinSupported = false, on = false).enabled)
        // 예전 설정으로 켜져 있으면 끌 수 있어야 한다 (꺼진 채 잠기면 옛 서비스가 계속 외기로 바꾼다)
        assertTrue(freshAirToggle(cabinSupported = false, on = true).enabled)
    }
}
