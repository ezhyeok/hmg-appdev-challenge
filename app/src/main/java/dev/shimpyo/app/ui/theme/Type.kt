package dev.shimpyo.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Pleos Connect 타이포그래피.
 *
 * 출처: docs/contest/02_Pleos_개발가이드/connect-guide/docs-design/foundations/typography.md
 * 크기·행간·굵기는 이 문서의 Font Hierarchy 표 이미지(headline/title/body/label)에서 읽었다.
 * 가이드 값의 단위는 dp다(Connect-S 17" 160dpi 기준 1dp = 1px). Compose에서는 같은 숫자를 sp로 쓴다(글꼴 배율 1.0이면 같다).
 * 정리: docs/design/pleos-design-tokens.md 2장.
 */

// TODO(font): 가이드가 지정한 시스템 서체는 Asta Sans(Google Fonts, 한글·영문 지원)다. 저장소에 번들되어 있지 않아
//  기본 서체로 대체한다. 번들하려면 Asta Sans TTF(Regular/SemiBold/Bold/ExtraBold)를 app/src/main/res/font/에 넣고
//  FontFamily(Font(R.font.asta_sans_regular, FontWeight.Normal), ...)로 바꾼다. 라이선스(OFL 예상)를 확인하고 출처를 표기할 것.
val PleosFontFamily: FontFamily = FontFamily.Default

/** 가이드의 굵기 이름. Body의 Strong은 가이드에 수치가 없어 SemiBold(600)로 정했다(제안). */
object PleosWeight {
    val ExtraBold = FontWeight.ExtraBold // 800
    val Bold = FontWeight.Bold // 700
    val Strong = FontWeight.SemiBold // 600, 제안
    val Regular = FontWeight.Normal // 400
}

private fun pleosStyle(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = PleosFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

/**
 * 가이드 스케일 그대로의 토큰. 기본 굵기는 각 단계의 첫 번째 굵기(Headline=ExtraBold, Title=Bold, Body=Normal)다.
 * 다른 허용 굵기가 필요하면 아래 변형을 쓰거나 copy(fontWeight = PleosWeight.X)로 바꾼다.
 *
 * | 단계 | 크기/행간 | 허용 굵기 |
 * | Headline L/M/S | 60/80, 56/72, 48/64 | Extra Bold, Bold |
 * | Title L/M/S | 40/52, 36/44, 32/40 | Extra Bold, Bold, Regular |
 * | Body L/M/S | 30/38, 28/36, 26/34 | Strong, Normal |
 * | Label M/S | 24/28, 20/24 | Normal |
 */
object PleosType {
    val HeadlineLarge = pleosStyle(60, 80, PleosWeight.ExtraBold)
    val HeadlineMedium = pleosStyle(56, 72, PleosWeight.ExtraBold)
    val HeadlineSmall = pleosStyle(48, 64, PleosWeight.ExtraBold)

    val TitleLarge = pleosStyle(40, 52, PleosWeight.Bold)
    val TitleMedium = pleosStyle(36, 44, PleosWeight.Bold)
    val TitleSmall = pleosStyle(32, 40, PleosWeight.Bold)
    val TitleLargeRegular = TitleLarge.copy(fontWeight = PleosWeight.Regular)
    val TitleMediumRegular = TitleMedium.copy(fontWeight = PleosWeight.Regular)
    val TitleSmallRegular = TitleSmall.copy(fontWeight = PleosWeight.Regular)

    val BodyLarge = pleosStyle(30, 38, PleosWeight.Regular)
    val BodyMedium = pleosStyle(28, 36, PleosWeight.Regular)
    val BodySmall = pleosStyle(26, 34, PleosWeight.Regular)
    val BodyLargeStrong = BodyLarge.copy(fontWeight = PleosWeight.Strong)
    val BodyMediumStrong = BodyMedium.copy(fontWeight = PleosWeight.Strong)
    val BodySmallStrong = BodySmall.copy(fontWeight = PleosWeight.Strong)

    /** Label은 주차 중 화면의 보조 정보에만 쓴다. 주행 화면 최소 글자는 DriveType.Minimum(28)이다. */
    val LabelMedium = pleosStyle(24, 28, PleosWeight.Regular)
    val LabelSmall = pleosStyle(20, 24, PleosWeight.Regular)
}

/**
 * Basic button 라벨. 가이드 표에는 없고 basic_size 도식에서 실측했다(라벨 'L' 대문자 높이 22.6 / 21.2 / 18.4dp
 * → 글자 크기 약 32 / 30 / 26, 획 굵기로 보아 Strong). 버튼 높이 100 / 80 / 64와 짝을 이룬다.
 */
object PleosButtonType {
    val Large = pleosStyle(32, 40, PleosWeight.Strong)
    val Medium = PleosType.BodyLargeStrong
    val Small = PleosType.BodySmallStrong
}

/**
 * 주행 화면 역할별 글자 (제안). 가이드의 "중요 정보 글자 높이 5mm 이상, 보조 3mm 이상"을
 * 가장 촘촘한 화면인 Connect-S 17"(1dp ≈ 0.147mm)에서, 글자 크기가 아니라 실제 글자 높이
 * (한글 ≈ 0.9em, 숫자 ≈ 0.72em)로 엄격하게 계산했다. 계산표: pleos-design-tokens.md 7.3.
 */
object DriveType {
    /** 게이지 남은 분 (60: 숫자 높이 6.4mm). 숫자 폭이 바뀌어 흔들리지 않게 고정폭 숫자(tnum) */
    val GaugeValue = PleosType.HeadlineLarge.copy(fontFeatureSettings = "tnum")
    /** compact 배치(윈도 높이 1000dp 미만)용 (48: 숫자 높이 5.1mm) */
    val GaugeValueCompact = PleosType.HeadlineSmall.copy(fontFeatureSettings = "tnum")
    /** "분" (32: 4.2mm, 보조) */
    val GaugeUnit = PleosType.TitleSmall
    /** "화장실 예상", "휴식 권고까지" (28: 3.7mm, 보조) */
    val GaugeLabel = PleosType.BodyMediumStrong
    /** 추천 장소 이름 (40: 한글 5.3mm, 중요) */
    val StopName = PleosType.TitleLarge
    /** 거리·시간 (30: 숫자 3.2mm, 보조) */
    val StopMeta = PleosType.BodyLargeStrong.copy(fontFeatureSettings = "tnum")
    /** 이유 칩. 숫자가 들어가므로 30 (숫자 3.2mm) */
    val ReasonChip = PleosType.BodyLargeStrong
    /** 들르기·괜찮아요 (40: 한글 5.3mm) */
    val ActionLabel = PleosType.TitleLarge
    /** 급해 버튼 (48: 한글 6.4mm) */
    val UrgentLabel = PleosType.HeadlineSmall
    /** 주행 화면에서 쓸 수 있는 가장 작은 글자 (28: 한글 3.7mm, 숫자 3.0mm). Label 스타일은 쓰지 않는다 */
    val Minimum = PleosType.BodyMedium
}

/**
 * Material3 Typography 매핑. M3 컴포넌트를 그대로 쓸 때의 기본값이다.
 * display=Headline, headline=Title, title=Body Strong, body=Body, label=버튼·Label.
 * labelLarge(M3 버튼 라벨)는 Medium 버튼 도식의 라벨 크기(약 30)에 맞춰 Body Large Strong으로 둔다.
 */
val ShimpyoTypography = Typography(
    displayLarge = PleosType.HeadlineLarge,
    displayMedium = PleosType.HeadlineMedium,
    displaySmall = PleosType.HeadlineSmall,
    headlineLarge = PleosType.TitleLarge,
    headlineMedium = PleosType.TitleMedium,
    headlineSmall = PleosType.TitleSmall,
    titleLarge = PleosType.BodyLargeStrong,
    titleMedium = PleosType.BodyMediumStrong,
    titleSmall = PleosType.BodySmallStrong,
    bodyLarge = PleosType.BodyLarge,
    bodyMedium = PleosType.BodyMedium,
    bodySmall = PleosType.BodySmall,
    labelLarge = PleosType.BodyLargeStrong,
    labelMedium = PleosType.LabelMedium,
    labelSmall = PleosType.LabelSmall,
)
