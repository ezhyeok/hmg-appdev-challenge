package dev.shimpyo.app.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.shimpyo.app.ui.DriveRestriction
import dev.shimpyo.app.ui.MemoryPrefs
import dev.shimpyo.app.ui.PreviewHost
import dev.shimpyo.app.ui.Screen
import dev.shimpyo.app.ui.ShimpyoContent
import dev.shimpyo.app.ui.theme.ShimpyoTheme
import dev.shimpyo.coach.CoachUiState

/*
 * 화면별 미리보기 (Android Studio). 크기는 Pleos Connect 앱 윈도 기준:
 * 세로 840×1184(17" 1:1 분할, 에뮬레이터 기본), 가로 1700×1184, compact 648×800(WF 세로).
 * 기획서 스크린샷은 이 미리보기를 그대로 캡처하면 된다.
 */

/**
 * 미리보기 한 장. 주행 제한은 상태의 drivingRestricted로 정한다: 미리보기에는 플랫폼 구독이 없어 그대로 두면 '모름 = 제한'이라
 * 정차 화면(계획·탑승자·설정)이 모두 주행 화면으로 그려진다
 */
@Composable
fun SampleFrame(
    state: CoachUiState,
    screen: Screen = Screen.DRIVE,
    dark: Boolean = false,
    urgentRequest: Long? = null,
    healthConsent: Boolean = false,
    voiceEnabled: Boolean = true,
) {
    ShimpyoTheme(darkTheme = dark) {
        ShimpyoContent(
            state = state,
            host = PreviewHost(MemoryPrefs(healthConsent = healthConsent, voiceEnabled = voiceEnabled)),
            restriction = if (state.drivingRestricted) DriveRestriction(driving = true, known = true) else DriveRestriction.PARKED,
            initialScreen = screen,
            initialUrgentRequest = urgentRequest,
        )
    }
}

// ------------------------------------------------------------ 1. 주행

@Preview(name = "주행 · 마지막 기회 질문", widthDp = 840, heightDp = 1184)
@Composable
private fun DrivePromptPreview() = SampleFrame(Samples.holidayPrompt)

@Preview(name = "주행 · 마지막 기회 질문 (다크)", widthDp = 840, heightDp = 1184)
@Composable
private fun DrivePromptDarkPreview() = SampleFrame(Samples.holidayPrompt, dark = true)

@Preview(name = "주행 · 평소", widthDp = 840, heightDp = 1184)
@Composable
private fun DriveCruisingPreview() = SampleFrame(Samples.cruising)

@Preview(name = "주행 · 휴식 권유", widthDp = 840, heightDp = 1184)
@Composable
private fun DriveFatiguePreview() = SampleFrame(Samples.fatiguePrompt)

@Preview(name = "주행 · 가로 1700", widthDp = 1700, heightDp = 1184)
@Composable
private fun DriveLandscapePreview() = SampleFrame(Samples.holidayPrompt)

@Preview(name = "주행 · compact 648×800", widthDp = 648, heightDp = 800)
@Composable
private fun DriveCompactPreview() = SampleFrame(Samples.holidayPrompt)

@Preview(name = "주행 · 정차 중", widthDp = 840, heightDp = 1184)
@Composable
private fun DriveParkedPreview() = SampleFrame(Samples.parkedPlan)

@Preview(name = "주행 · 시작 전", widthDp = 840, heightDp = 1184)
@Composable
private fun DriveStartingPreview() = SampleFrame(Samples.starting)

@Preview(name = "주행 · 경유지 실패", widthDp = 840, heightDp = 1184)
@Composable
private fun DriveGoToFailedPreview() = SampleFrame(Samples.goToFailed)

@Preview(name = "주행 · 내비에 넣는 중", widthDp = 840, heightDp = 1184)
@Composable
private fun DriveGoToPendingPreview() = SampleFrame(Samples.goToPending)

@Preview(name = "주행 · 코치 알림 (음성 안내 끔)", widthDp = 840, heightDp = 1184)
@Composable
private fun DriveNoticePreview() = SampleFrame(Samples.notice, voiceEnabled = false)

@Preview(name = "주행 · 위치 확인 중 (후보 없음)", widthDp = 840, heightDp = 1184)
@Composable
private fun DriveNoLocationPreview() = SampleFrame(Samples.cruising.copy(next = null, ahead = emptyList(), candidatesState = dev.shimpyo.coach.CandidatesState.NO_LOCATION))

// ------------------------------------------------------------ 3. 급해 결과

@Preview(name = "급해 · 주행 중 (2곳)", widthDp = 840, heightDp = 1184)
@Composable
private fun UrgentDrivingPreview() = SampleFrame(Samples.urgentDriving)

@Preview(name = "급해 · 주행 중 compact 628×855", widthDp = 628, heightDp = 855)
@Composable
private fun UrgentDrivingCompactPreview() = SampleFrame(Samples.urgentDriving)

@Preview(name = "급해 · 주행 중 가로", widthDp = 1700, heightDp = 1184)
@Composable
private fun UrgentDrivingLandscapePreview() = SampleFrame(Samples.urgentDriving)

@Preview(name = "급해 · 정차 중 목록", widthDp = 840, heightDp = 1184)
@Composable
private fun UrgentParkedPreview() = SampleFrame(Samples.urgentDriving.copy(drivingRestricted = false, parked = true, prompt = null))

@Preview(name = "급해 · 찾는 중", widthDp = 840, heightDp = 1184)
@Composable
private fun UrgentLoadingPreview() = SampleFrame(Samples.cruising, urgentRequest = 1L)

// ------------------------------------------------------------ 2. 계획

@Preview(name = "계획", widthDp = 840, heightDp = 1184)
@Composable
private fun PlanPreview() = SampleFrame(Samples.parkedPlan, Screen.PLAN)

@Preview(name = "계획 · 가로", widthDp = 1700, heightDp = 1184)
@Composable
private fun PlanLandscapePreview() = SampleFrame(Samples.parkedPlan, Screen.PLAN)

@Preview(name = "계획 · 짧은 느린 구간은 정체로 그리지 않음", widthDp = 840, heightDp = 1184)
@Composable
private fun PlanSlowPatchesPreview() = SampleFrame(Samples.slowPatches, Screen.PLAN)

@Preview(name = "계획 · 내비에 넣는 중", widthDp = 840, heightDp = 1184)
@Composable
private fun PlanGoToPendingPreview() = SampleFrame(Samples.parkedPlan.copy(goToPending = Samples.parkedPlan.plan!!.stops.first().stop), Screen.PLAN)

@Preview(name = "계획 · 계획 전", widthDp = 840, heightDp = 1184)
@Composable
private fun PlanEmptyPreview() = SampleFrame(Samples.parkedPlan.copy(plan = null), Screen.PLAN)

// ------------------------------------------------------------ 4~6. 탑승자, 기록, 설정, 시연

@Preview(name = "탑승자", widthDp = 840, heightDp = 1184)
@Composable
private fun OccupantsPreview() = SampleFrame(Samples.parkedPlan, Screen.OCCUPANTS)

@Preview(name = "탑승자 · 평소 간격, 자는 중", widthDp = 840, heightDp = 1184)
@Composable
private fun OccupantsFamilyPreview() = SampleFrame(Samples.family, Screen.OCCUPANTS)

@Preview(name = "탑승자 · 좁은 세로 628×855", widthDp = 628, heightDp = 855)
@Composable
private fun OccupantsNarrowPreview() = SampleFrame(Samples.family, Screen.OCCUPANTS)

@Preview(name = "탑승자 · 말로 설정 불가 (음성 안내 끔)", widthDp = 840, heightDp = 1184)
@Composable
private fun OccupantsVoiceOffPreview() = SampleFrame(Samples.family, Screen.OCCUPANTS, voiceEnabled = false)

@Preview(name = "탑승자 · 추가 (동의 전)", widthDp = 840, heightDp = 1184)
@Composable
private fun OccupantsAddPreview() = SampleFrame(Samples.onlyDriver, Screen.OCCUPANTS)

@Preview(name = "탑승자 · 가로", widthDp = 1700, heightDp = 1184)
@Composable
private fun OccupantsLandscapePreview() = SampleFrame(Samples.parkedPlan, Screen.OCCUPANTS, healthConsent = true)

@Preview(name = "기록", widthDp = 840, heightDp = 1184)
@Composable
private fun HistoryPreview() = SampleFrame(Samples.parkedPlan, Screen.HISTORY)

@Preview(name = "설정", widthDp = 840, heightDp = 1184)
@Composable
private fun SettingsPreview() = SampleFrame(Samples.parkedPlan, Screen.SETTINGS)

@Preview(name = "시연 패널", widthDp = 840, heightDp = 1184)
@Composable
private fun DemoPreview() = SampleFrame(Samples.parkedPlan.copy(demo = "holiday"), Screen.DEMO)
