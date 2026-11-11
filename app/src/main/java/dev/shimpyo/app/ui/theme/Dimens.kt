package dev.shimpyo.app.ui.theme

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/*
 * 크기·간격 토큰. 단위는 모두 dp다.
 *
 * [가이드]  = Pleos Connect UI Design Guide의 수치, 또는 가이드 도식(1:1 dp로 그려진 SVG)에서 잰 값
 * [제안]    = 가이드에 값이 없어 정한 값
 * 출처 파일과 측정 방법: docs/design/pleos-design-tokens.md 3~6장
 * (가이드 폴더: docs/contest/02_Pleos_개발가이드/connect-guide/docs-design/)
 */
object Dimens {

    /** 간격. 4dp 배수 스케일은 [제안], 24·40·48은 가이드 도식에 나오는 값이다. */
    object Space {
        val xxs = 4.dp
        val xs = 8.dp
        val s = 12.dp
        val m = 16.dp
        val l = 24.dp // 토스트 여백, 팝업 버튼 간격 [가이드]
        val xl = 32.dp
        val xxl = 40.dp // Page Navigation 여백 [가이드]
        val xxxl = 48.dp // 팝업 안쪽 여백, 설정 화면 콘텐츠 여백 [가이드]
        val huge = 64.dp
    }

    /** 앱 윈도와 화면 여백 */
    object Layout {
        /** 앱 윈도 모서리. 콘텐츠를 이 반경 안쪽에 둔다 [가이드: Toast·Theme 도식의 앱 패널 rx=40] */
        val windowCorner = 40.dp
        /** 세로(portrait) 윈도 좌우 여백 [가이드: Page Navigation 왼쪽 여백 40] */
        val marginPortrait = 40.dp
        /** 가로(landscape) 윈도 좌우 여백 [가이드: 설정 화면 도식 콘텐츠 좌우 여백 48] */
        val marginLandscape = 48.dp
        /** 윈도 위쪽 여백 [가이드: Page Navigation 위 여백 40] */
        val marginTop = 40.dp
        /** 아래쪽 여백 [제안: 토스트 하단 여백 24보다 넓게] */
        val marginBottom = 40.dp
        /** 토스트와 윈도 가장자리 사이 [가이드: Toast side 24, bottom 24] */
        val toastInset = 24.dp
        /** Page Navigation의 앱 아이콘 [가이드] */
        val appIcon = 80.dp
        /** Page Navigation 높이 = 위 여백 40 + 아이콘 80 + 아래 40 [가이드 도식에서 계산] */
        val pageNavigationHeight = 160.dp
        /** 카드 사이 간격 [제안] */
        val gutter = 24.dp
        /** 구역 사이 간격 [제안] */
        val sectionGap = 40.dp
        /** 가로 화면 2단 구성의 왼쪽 목록 폭 [가이드: Theme 설정 화면 도식 약 430] */
        val listPaneWidth = 428.dp
    }

    /** 앱 윈도 크기. Preview와 레이아웃 분기에 쓴다 [가이드: Display Layout 표, Landscape/Portrait (dp)] */
    object Window {
        val s17Landscape = DpSize(1700.dp, 1184.dp) // Connect-S 17" (기본, 에뮬레이터)
        val s17Portrait = DpSize(840.dp, 1184.dp)
        val s146Landscape = DpSize(1360.dp, 948.dp) // Connect-S 14.6" (200dpi)
        val s146Portrait = DpSize(672.dp, 948.dp)
        val l129Landscape = DpSize(1274.dp, 855.dp) // Connect-L 12.9"
        val l129Portrait = DpSize(628.dp, 855.dp)
        val wStandardLandscape = DpSize(1640.dp, 810.dp)
        val wStandardPortrait = DpSize(800.dp, 810.dp)
        val wExtendedLandscape = DpSize(1640.dp, 1400.dp)
        val wExtendedPortrait = DpSize(800.dp, 1400.dp)
        val wfLandscape = DpSize(1256.dp, 800.dp) // Connect-WF (200dpi)
        val wfPortrait = DpSize(648.dp, 800.dp)

        /** 이 폭 이상이면 가로(2단) 배치 [제안: 가장 넓은 portrait 840과 가장 좁은 landscape 1256 사이] */
        val landscapeBreakpoint = 1000.dp
        /**
         * 이 높이 미만이면 간격·게이지를 줄인 compact 배치 [제안].
         * 기본 배치 세로 합(988)이 들어가는 17"(1184)·W-Extended(1400)만 기본, 14.6"(948)·12.9"(855)·W(810)·WF(800)는 compact
         */
        val compactHeightBreakpoint = 1000.dp
    }

    object Radius {
        val xs = 8.dp // 작은 태그 [제안]
        val s = 16.dp // Small 버튼, 토스트, 스위치 트랙 [가이드]
        val m = 24.dp // Medium 버튼, 텍스트 필드, 드롭다운, 목록 항목, 스테퍼 [가이드]
        val l = 32.dp // Large 버튼, 컨테이너(드롭다운 목록, 위젯, 피커, 슬라이더) [가이드]
        val xl = 48.dp // 시스템 팝업 [가이드]
        val window = 40.dp // 앱 윈도 [가이드]
        val iconButton = 20.dp // 아이콘 버튼 80x80 [가이드]
        val checkbox = 12.dp // [가이드]
        val switchKnob = 8.dp // [가이드]
        val gaugeBar = 12.dp // 게이지 막대 높이의 절반 [제안]
    }

    /** 터치 영역 */
    object Touch {
        /** 어떤 조작 요소도 이보다 작게 만들지 않는다 [가이드: 가장 작은 버튼(Small) 높이 64] */
        val min = 64.dp
        /** 일반 권장 [가이드: Medium 버튼, 아이콘 버튼 80] */
        val recommended = 80.dp
        /** 주행 중 조작 요소 최소 [가이드 값 선택: Large 버튼 100. 17"에서 약 14.7mm] */
        val driving = 100.dp
        /** 급해 버튼 높이 [제안] */
        val urgent = 120.dp
        /** 터치 요소 사이 최소 간격 [가이드: 팝업 버튼 간격 24] */
        val gap = 24.dp
    }

    /** Basic button [가이드: basic_size 도식] */
    object Button {
        val heightLarge = 100.dp // radius 32
        val heightMedium = 80.dp // radius 24
        val heightSmall = 64.dp // radius 16
        val paddingHorizontalLarge = 40.dp // 도식 실측 근사
        val paddingHorizontalMedium = 32.dp
        val paddingHorizontalSmall = 24.dp
        val iconLarge = 48.dp // 도식 실측 근사
        val iconMedium = 40.dp
        val iconSmall = 32.dp
        val iconLabelGap = 16.dp // 도식 실측 근사(14~20)
        /** Icon button (Basic/Plain/Circle) [가이드: icon_type 도식 80x80, radius 20] */
        val iconButton = 80.dp
    }

    object Icon {
        /** 아이콘 그리드 기본 크기 [가이드: Iconography 48x48dp] */
        val base = 48.dp
        val small = 32.dp // 칩·목록 보조 아이콘 [제안]
        val large = 64.dp // 급해 버튼, 빈 상태 [제안]
    }

    object Component {
        val listItemHeight = 100.dp // 드롭다운 목록 항목, 설정 메뉴 [가이드]
        val dropdownHeightLarge = 100.dp // [가이드]
        val dropdownHeightMedium = 80.dp // [가이드]
        val textFieldHeightLarge = 100.dp // [가이드]
        val textFieldHeightMedium = 80.dp // [가이드]
        val stepperWidth = 400.dp // [가이드]
        val stepperHeight = 100.dp // [가이드]
        val sliderHeight = 100.dp // Floating 슬라이더 컨테이너 [가이드]
        val switchTrackWidth = 80.dp // [가이드]
        val switchTrackHeight = 48.dp // [가이드]
        val switchKnob = 32.dp // [가이드]
        val checkbox = 48.dp // [가이드]
        val radioDot = 24.dp // [가이드]
        val pickerWidth = 600.dp // Spinner [가이드]
        val pickerHeight = 384.dp // [가이드]
        val pickerRowHeight = 88.dp // [가이드]
        val toastMinHeight = 80.dp // [가이드]
        val toastMaxWidth = 792.dp // [가이드]
        val toastTextMaxWidth = 712.dp // [가이드]
        val toastPaddingHorizontal = 40.dp // [가이드: (792-712)/2]
        val toastPaddingVertical = 24.dp // [가이드]
        val popupWidthMedium = 808.dp // [가이드]
        val popupWidthLarge = 1200.dp // [가이드]
        val popupPadding = 48.dp // [가이드]
        val popupButtonHeight = 100.dp // [가이드]
        val popupButtonGap = 24.dp // [가이드]
        val widgetWidth = 792.dp // [가이드]
        val drivingNotificationWidth = 426.dp // Vehicle notification [가이드]
        val scrollbarWidth = 6.dp // [가이드]
        val scrollbarHandleLength = 150.dp // [가이드]
        val strokeWidth = 2.dp // 컨테이너 테두리 [가이드: 설정 화면 도식]
        val dividerThickness = 2.dp // [가이드: 설정 화면 도식]
        val focusRingWidth = 4.dp // CCP 포커스 링 [제안]
        val chipHeight = 60.dp // 이유 칩(글자 30/38). 누를 수 없는 정보 표시 [제안]
        val chipPaddingHorizontal = 20.dp // [제안]
    }

    /** 사람 게이지 [제안]. 가장 좁은 세로 윈도(12.9" 628dp)에서 두 장이 나란히 들어가는 폭 */
    object Gauge {
        val barHeight = 24.dp
        val thresholdTickWidth = 4.dp
        val thresholdTickHeight = 40.dp
        val minWidth = 260.dp
        val cardPadding = 32.dp
        val cardPaddingCompact = 24.dp
        val cardMinHeight = 240.dp
        val cardMinHeightCompact = 176.dp
    }

    /**
     * 주행 화면 [제안]. 세로 배치 높이 합:
     * 기본(높이 1000 이상) 헤더 112 + 게이지 240 + 추천 280~ + 행동 100 + 급해 120 + 간격 24×4 + 아래 40 = 988~
     * compact(1000 미만) 헤더 112 + 게이지 176 + 추천 200~ + 행동 100 + 급해 100 + 간격 16×4 + 아래 16 = 768~ (WF 세로 800에 들어간다)
     */
    object Drive {
        /** Page Navigation을 주행 중에는 위아래 여백 16으로 줄여 쓴다 (16 + 아이콘 80 + 16) */
        val headerHeight = 112.dp
        val sectionGap = 24.dp
        val sectionGapCompact = 16.dp
        val primaryActionHeight = 100.dp
        val urgentHeight = 120.dp
        val urgentHeightCompact = 100.dp
        val stopCardPadding = 32.dp
        val stopCardMinHeight = 280.dp
        val stopCardMinHeightCompact = 200.dp
        /** 급해 결과 카드. 카드 전체가 하나의 터치 영역이다 */
        val urgentResultCardMinHeight = 220.dp
    }

    /**
     * 그림자 [가이드: Shadow Level 1~3의 Y 오프셋 6/8/20, blur 24/32/40].
     * Compose shadow(elevation)는 Y·blur를 따로 지정할 수 없어 Y 오프셋을 elevation 근사로 쓴다.
     */
    object Elevation {
        val level1 = 6.dp
        val level2 = 8.dp
        val level3 = 20.dp
    }
}

/** 주행 중 화면에 올리는 양의 상한 [제안]. 근거: pleos-design-tokens.md 7장 */
object DriveLimits {
    /** 동시에 보이는 게이지 */
    const val MAX_GAUGES = 2
    /** 추천 카드의 이유 칩 */
    const val MAX_REASON_CHIPS = 2
    /** 급해 결과 */
    const val MAX_URGENT_RESULTS = 2
    /** 한 화면의 터치 요소 (들르기, 괜찮아요, 급해, 마이크) */
    const val MAX_TOUCH_TARGETS = 4
    /** 한 줄 문구 최대 글자 수. CarUxRestrictions.getMaxRestrictedStringLength()가 오면 그 값을 쓴다 */
    const val MAX_STRING_LENGTH = 120
    /** 문단 최대 줄 수 [가이드: 3줄] */
    const val MAX_LINES = 3
    /** 이유 칩 문구 최대 글자 수 (한글) */
    const val MAX_CHIP_CHARS = 12
}
