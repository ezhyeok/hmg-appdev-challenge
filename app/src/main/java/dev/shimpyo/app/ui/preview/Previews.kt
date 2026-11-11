package dev.shimpyo.app.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.shimpyo.app.coach.CoachUiState
import dev.shimpyo.app.ui.MemoryPrefs
import dev.shimpyo.app.ui.PreviewHost
import dev.shimpyo.app.ui.Screen
import dev.shimpyo.app.ui.ShimpyoContent
import dev.shimpyo.app.ui.theme.ShimpyoTheme

/*
 * 화면별 미리보기 (Android Studio). 크기는 Pleos Connect 앱 윈도 기준:
 * 세로 840×1184(17" 1:1 분할, 에뮬레이터 기본), 가로 1700×1184, compact 648×800(WF 세로).
 * 기획서 스크린샷은 이 미리보기를 그대로 캡처하면 된다.
 */

@Composable
fun SampleFrame(
    state: CoachUiState,
    screen: Screen = Screen.DRIVE,
    dark: Boolean = false,
    urgentPending: Boolean = false,
    healthConsent: Boolean = false,
) {
    ShimpyoTheme(darkTheme = dark) {
        ShimpyoContent(
            state = state,
            host = PreviewHost(MemoryPrefs(healthConsent = healthConsent)),
            initialScreen = screen,
            initialUrgentPending = urgentPending,
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

// ------------------------------------------------------------ 3. 급해 결과

@Preview(name = "급해 · 주행 중 (2곳)", widthDp = 840, heightDp = 1184)
@Composable
private fun UrgentDrivingPreview() = SampleFrame(Samples.urgentDriving)

@Preview(name = "급해 · 주행 중 가로", widthDp = 1700, heightDp = 1184)
@Composable
private fun UrgentDrivingLandscapePreview() = SampleFrame(Samples.urgentDriving)

@Preview(name = "급해 · 정차 중 목록", widthDp = 840, heightDp = 1184)
@Composable
private fun UrgentParkedPreview() = SampleFrame(Samples.urgentDriving.copy(drivingRestricted = false, parked = true, prompt = null))

@Preview(name = "급해 · 찾는 중", widthDp = 840, heightDp = 1184)
@Composable
private fun UrgentLoadingPreview() = SampleFrame(Samples.cruising, urgentPending = true)

// ------------------------------------------------------------ 2. 계획

@Preview(name = "계획", widthDp = 840, heightDp = 1184)
@Composable
private fun PlanPreview() = SampleFrame(Samples.parkedPlan, Screen.PLAN)

@Preview(name = "계획 · 가로", widthDp = 1700, heightDp = 1184)
@Composable
private fun PlanLandscapePreview() = SampleFrame(Samples.parkedPlan, Screen.PLAN)

@Preview(name = "계획 · 계획 전", widthDp = 840, heightDp = 1184)
@Composable
private fun PlanEmptyPreview() = SampleFrame(Samples.parkedPlan.copy(plan = null), Screen.PLAN)

// ------------------------------------------------------------ 4~6. 탑승자, 기록, 설정, 시연

@Preview(name = "탑승자", widthDp = 840, heightDp = 1184)
@Composable
private fun OccupantsPreview() = SampleFrame(Samples.parkedPlan, Screen.OCCUPANTS)

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
