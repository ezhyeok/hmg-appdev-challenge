package dev.shimpyo.app.ui.occupants

import dev.shimpyo.app.ui.MemoryPrefs
import dev.shimpyo.app.ui.components.OverlayState
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 건강 관련 정보 동의 스위치 (HealthConsent.onHealthConsentTap). UI 감사 #23: 철회 확인 팝업이 떠 있는 동안 탑승자가 바뀌면
 * (음성 "아이 탔어") 팝업을 연 때의 목록으로 덮어써서 그 변경이 사라졌다. 고친 코드에 시험이 없었다(닫힘 검증).
 */
class HealthConsentTest {
    private val driver = Occupant("driver", OccupantType.ADULT, label = "운전자")
    private val pregnant = Occupant("p", OccupantType.PREGNANT)
    private val kid = Occupant("k", OccupantType.CHILD, ageYears = 5)
    private val oab = Occupant("o", OccupantType.OAB)

    private class Rig(consent: Boolean, var current: List<Occupant>) {
        val prefs = MemoryPrefs(healthConsent = consent)
        val overlays = OverlayState()
        val sent = ArrayList<List<Occupant>>()
        fun tap() = onHealthConsentTap(prefs, overlays, occupants = { current }, onSetOccupants = { sent += it })
        fun confirm() {
            val popup = checkNotNull(overlays.popup) { "확인 팝업이 뜨지 않았다" }
            overlays.dismissPopup()
            popup.onConfirm()
        }
    }

    @Test
    fun withdrawingRemovesHealthOccupantsFromTheListAsItIsWhenConfirmed() {
        val rig = Rig(consent = true, current = listOf(driver, pregnant))
        rig.tap()
        assertTrue("확인 전에는 아무것도 바꾸지 않는다", rig.prefs.healthConsent && rig.sent.isEmpty())
        // 팝업이 떠 있는 동안 음성으로 아이가 더해지고 민감 탑승자도 더해졌다
        rig.current = listOf(driver, pregnant, kid, oab)
        rig.confirm()
        assertFalse(rig.prefs.healthConsent)
        assertEquals("지금 목록에서 건강 유형만 뺀다 (아이는 남는다)", listOf(listOf(driver, kid)), rig.sent)
    }

    @Test
    fun cancellingTheWithdrawalChangesNothing() {
        val rig = Rig(consent = true, current = listOf(driver, pregnant))
        rig.tap()
        rig.overlays.dismissPopup()
        assertTrue(rig.prefs.healthConsent)
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun withoutHealthOccupantsConsentTurnsOffAtOnce() {
        val rig = Rig(consent = true, current = listOf(driver, kid))
        rig.tap()
        assertNull("지울 사람이 없으면 묻지 않는다", rig.overlays.popup)
        assertFalse(rig.prefs.healthConsent)
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun turningConsentOnAsksFirst() {
        val rig = Rig(consent = false, current = listOf(driver))
        rig.tap()
        assertFalse("동의는 팝업에서 [동의]를 눌러야 켜진다", rig.prefs.healthConsent)
        rig.confirm()
        assertTrue(rig.prefs.healthConsent)
        assertTrue(rig.sent.isEmpty())
    }
}
