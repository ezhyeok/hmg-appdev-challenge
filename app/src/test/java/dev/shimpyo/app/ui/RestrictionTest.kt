package dev.shimpyo.app.ui

import dev.hmg.pleoskit.UxRestrictionFlags
import dev.hmg.pleoskit.UxRestrictions
import dev.shimpyo.app.ui.theme.DriveLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 주행 제한은 모르면 제한이다 (UI 감사 #2), 기능별 비트와 최대 글자 수를 읽는다 (#26), 권한은 정차 중에 한 번만 묻는다 (#14) */
class RestrictionTest {

    private fun known(requiresDo: Boolean, active: Int = 0, maxLen: Int = 120, maxItems: Int = 21) =
        UxRestrictions.Known(requiresDo, active, maxLen, maxItems, maxContentDepth = 3)

    @Test
    fun unknownIsRestricted() {
        // 연결 전·구독 실패: 서비스도 모르면(기본 drivingRestricted = true, 서비스 꺼짐) 제한
        assertTrue(driveRestriction(UxRestrictions.NOT_CONNECTED).driving)
        assertTrue(driveRestriction(UxRestrictions.Unknown("구독 실패")).driving)
        assertFalse(driveRestriction(UxRestrictions.NOT_CONNECTED).known)
        assertTrue(UxRestrictions.NOT_CONNECTED.restricted)
    }

    @Test
    fun screenSubscriptionIsTheOnlySource() {
        // 화면 구독만 본다. 모르면 서비스 값(CoachUiState.drivingRestricted)이 false여도 제한이다: 새로 뜬 서비스가 자기
        // 구독을 내기 전에는 죽은 서비스의 옛 false가 남아 있어서 믿을 수 없다 (재검토 #12). 그래서 함수가 상태를 받지 않는다
        assertTrue(driveRestriction(known(true)).driving)
        assertFalse(driveRestriction(known(false)).driving)
        assertTrue(driveRestriction(UxRestrictions.Unknown("연결 해제")).driving)
    }

    @Test
    fun perFeatureLimitsApplyOnlyWhenFlagged() {
        val plain = driveRestriction(known(true, active = UxRestrictionFlags.NO_SETUP or UxRestrictionFlags.NO_VIDEO, maxLen = 20, maxItems = 1))
        assertEquals(DriveLimits.MAX_STRING_LENGTH, plain.maxChars)
        assertEquals(DriveLimits.MAX_URGENT_RESULTS, plain.maxItems)
        val limited = driveRestriction(known(true, active = UxRestrictionFlags.LIMIT_STRING_LENGTH or UxRestrictionFlags.LIMIT_CONTENT, maxLen = 20, maxItems = 1))
        assertEquals(20, limited.maxChars)
        assertEquals(1, limited.maxItems)
        // 플랫폼 상한이 앱 상한보다 크면 앱 상한
        assertEquals(DriveLimits.MAX_URGENT_RESULTS, driveRestriction(known(true, UxRestrictionFlags.LIMIT_CONTENT, maxItems = 10)).maxItems)
    }

    @Test
    fun limitCharsKeepsShortTextAndEllipsizesLong() {
        assertEquals("안성휴게소", limitChars("안성휴게소", 10))
        assertEquals("가나다라…", limitChars("가나다라마바", 5))
    }

    @Test
    fun permissionsAskedOnceOnlyWhenKnownParked() {
        val missing = listOf("android.car.permission.CAR_SPEED")
        assertTrue(PermissionPolicy.shouldAsk(false, missing, DriveRestriction.PARKED))
        assertFalse(PermissionPolicy.shouldAsk(true, missing, DriveRestriction.PARKED))
        assertFalse(PermissionPolicy.shouldAsk(false, missing, DriveRestriction.UNKNOWN))
        assertFalse(PermissionPolicy.shouldAsk(false, missing, DriveRestriction(driving = true, known = true)))
        assertFalse(PermissionPolicy.shouldAsk(false, emptyList(), DriveRestriction.PARKED))
        assertTrue(PermissionPolicy.newlyGranted(missing, emptyList()))
        assertFalse(PermissionPolicy.newlyGranted(missing, missing))
        assertFalse(PermissionPolicy.newlyGranted(emptyList(), emptyList()))
    }
}
