package dev.shimpyo.engine

import java.time.ZoneId

/**
 * 판단에 쓰는 기본값. 출처는 docs/research/ai-science.md(아래 'AI §n'은 그 문서의 절 번호)와 docs/product/SPEC.md 4장.
 * 값을 바꾸면 SPEC의 표도 같이 고친다.
 *
 * 표기: `근거: AI §9-1` = 연구 문서 파라미터 표, `근거: SPEC v0` = SPEC의 v0 숫자 그대로,
 * `근거: 엔진 v0` = 두 문서에 없어 엔진이 정한 설계값. 대부분 문헌을 참고한 설계값이고 실차 검증 전이다.
 */
data class CoachConfig(
    // 화장실 게이지 단계 경계 (남은 분). 근거: SPEC v0
    val restroomWatchMin: Int = 45,
    val restroomSoonMin: Int = 20,
    val restroomNowMin: Int = 10,
    /** 게이지(화면)의 '바닥': 필요 확률이 이 값에 닿는 시점. 근거: AI §9-1 (게이지 = 필요 확률 0.5) */
    val restroomNeedProbability: Double = 0.5,

    // 피로 게이지: 휴식 리듬 시계의 단계 (연속 주행 분). NOW = '지금 쉬기 권고'(AI §8-3의 L1)
    val fatigueWatchMin: Int = 90,
    val fatigueSoonMin: Int = 100,
    /** 기본 휴식 리듬: 2시간마다 15분. 근거: AI §2, §9-2 (Highway Code 91조, 국내 화물차 규정, 도로공사 권고) */
    val fatigueNowMin: Int = 120,
    /** 02~06시에 운전한 1분을 이만큼으로 센다. 2.0이면 그 시간대 연속 1시간에 NOW. 근거: AI §8-3 L1 ("02~06시 + 연속 1시간") */
    val nightCircadianFactor: Double = 2.0,
    /** 14~16시 가중 ('중'). 근거: SPEC v0, AI §1 (Horne & Reyner 1995: 16시 무렵 사고 정점) */
    val afternoonCircadianFactor: Double = 1.2,
    /** 지난밤 수면이 이보다 짧으면 휴식 리듬에 shortSleepFactor를 한 번 곱한다. 근거: AI §9-2 (Tefft 2016) */
    val shortSleepHours: Float = 6f,
    /** 한 '단계' 가중. 6시간 미만 ×0.8, 5시간 미만 ×0.8². 근거: AI §9-2 */
    val shortSleepFactor: Double = 0.8,
    /** 운전 패턴 편차가 이 값 이상이고 '편차 지속'이면 피로 단계를 한 단계 올리고 상태 확인(CHECK_IN)을 묻는다 */
    val styleAdvanceDeviation: Double = 0.6,
    /** 이 값 이상의 편차 지속이 피로 NOW와 겹치면 '강한 권고'(커피·짧은 낮잠·교대 안내). 근거: AI §8-3 L2 */
    val styleCheckInDeviation: Double = 0.8,
    /** 기준선을 만드는 정속 주행 시간 (분, 준비 5분 뒤). 근거: AI §9-2 (누적 10~20분) */
    val styleBaselineMin: Int = 20,
    /** 내기순환이 이만큼 이어지면 환기를 권한다 (분). 편의 기능이며 피로를 올리지 않는다. 근거: AI §1, §9-2 */
    val recirculationFreshAirMin: Int = 30,

    // 정차 판정
    /** 휴식 완료: 기어 P가 이만큼 이어지면 휴식 리듬을 0으로. 근거: AI §9-2 (도로공사 휴식포인트 15분) */
    val restMinParkedMin: Int = 15,
    /** 화장실 정차: 기어 P 이만큼 + 문 열림. 근거: SPEC v0, AI §8-1 */
    val restroomStopMinParkedMin: Int = 3,

    // 마지막 기회. 근거: SPEC v0 (AI §9-3은 출구 3~5km 전 첫 가용 창, 1.5km 안이면 포기)
    val congestionAlertMin: Int = 30,
    val congestionAlertSensitiveMin: Int = 20,
    val askWindowNearM: Double = 2_000.0,
    val askWindowFarM: Double = 5_000.0,

    // 묻는 타이밍
    /** 다음 회전이 이 거리 안이면 묻지 않는다. 근거: SPEC v0 */
    val maneuverQuietM: Double = 500.0,
    /** 제동·방향지시등·큰 조향 뒤 이만큼 묻지 않는다 (초). 근거: AI §9-3 (5~10초) */
    val quietAfterBrakeOrSteerS: Int = 10,
    /** 같은 종류 질문의 기본 쉼 (분). 거절하면 2배씩, 최대 declineCooldownMaxMin. 단계가 오르면 해제. 근거: AI §9-3 */
    val declineCooldownMin: Int = 20,
    /** 엔진이 먼저 거는 질문 상한 (시간당). 근거: AI §9-3 */
    val maxPromptsPerHour: Int = 3,

    // 급해 모드 (분). 근거: SPEC v0
    val parkWalkGasStationMin: Double = 1.0,
    val parkWalkRestAreaMin: Double = 2.0,
    val parkWalkParkingMin: Double = 3.0,
    val uTurnPenaltyMin: Double = 5.0,
    /** 경로 거리를 모를 때 직선거리에 곱하는 도로 계수 */
    val roadFactor: Double = 1.3,
    /** 경로 시간을 모를 때 쓰는 평균 속도 (km/h) */
    val cityAvgSpeedKph: Double = 25.0,
    val highwayAvgSpeedKph: Double = 80.0,

    // 피트스톱 (분). 근거: AI §9-3 (플래너 체류시간, 설계값)
    val mealWindows: List<IntRange> = listOf(11 * 60 + 30..13 * 60 + 30, 17 * 60 + 30..19 * 60 + 30),
    val restroomDwellMin: Int = 7,
    val restDwellMin: Int = 15,
    val mealDwellMin: Int = 35,

    // ---------------- v0.2 추가 (모두 기본값이 있어 기존 호출은 그대로 동작) ----------------

    /** 시간대 판단(새벽·오후, 야간 가점, 식사 시간)에 쓰는 기기 시간대 */
    val zoneId: ZoneId = ZoneId.of("Asia/Seoul"),

    // 화장실 필요 곡선: 와이블(형상 k 고정) + 척도의 감마 켤레 사전분포. 근거: AI §8-1, §9-1
    /** 와이블 형상 k. 근거: AI §9-1 */
    val restroomWeibullShape: Double = 2.5,
    /** 사전 강도 a0 (가상 관측 3회). 사건 3회면 개인 기록이 사전값과 같은 무게. 근거: AI §8-1, §9-1 */
    val restroomPriorStrength: Double = 3.0,
    /**
     * 게이지 규칙의 '공백'으로 볼 최소 간격 (분). 휴게시설 간격 기준이 최대 25km(고속 주행 약 15분)이므로
     * 이보다 짧은 간격은 공백이 아니다 (화면 게이지와 NOW 알림이 맡는다). 근거: AI §3-C
     */
    val lastChanceMinGapMin: Int = 15,
    /** 마지막 기회·피트스톱 판단에 쓰는 필요 확률 (다음 기회까지 버티지 못할 확률이 이 값 이상이면 그 앞에서 묻는다). 근거: AI §8-2, §9-1 */
    val restroomDecisionProbability: Double = 0.3,
    /** 앱이 권해서 들른 정차를 사건 몇 회로 셀지. 근거: AI §8-1 (a += 0.5) */
    val restroomSuggestedWeight: Double = 0.5,
    /** 탑승자별로 기억하는 관측 간격 수 (최근 것부터) */
    val restroomMemoryMax: Int = 20,
    /** 이보다 짧은 간격은 관측으로 쓰지 않는다 (연달아 선 정차 등). 근거: 엔진 v0 */
    val restroomMinObservedMin: Double = 20.0,
    /** 음성으로 알린 필요를 화장실 정차 없이 유지하는 최대 시간 (분). 근거: 엔진 v0 */
    val voicedNeedHoldMin: Int = 120,
    /** 유형별 '필요해짐' 중앙값 (분). 근거: AI §3-A, §9-1 (배뇨일지·임상시험 기준선·AAP·AKC에서 파생한 설계값) */
    val priorAdultMin: Double = 150.0,
    val priorElderlyMin: Double = 120.0,
    val priorOabMin: Double = 90.0,
    /** IBS: 장 절박감은 시간으로 예측하지 않는다(커버리지 규칙). 방광은 성인 값. 근거: AI §9-1 */
    val priorIbsMin: Double = 150.0,
    /** 임신부: 3기 1.5시간(1·2기 2시간). 주수를 모르므로 보수적으로 3기 값. 근거: AI §9-1 */
    val priorPregnantMin: Double = 90.0,
    /** 아이 3~5세 (나이를 모르거나 3세 미만도 이 값). 근거: AI §9-1 */
    val priorChildYoungMin: Double = 60.0,
    /** 아이 6~9세. 10세 이상은 성인 값. 근거: AI §9-1 */
    val priorChildOlderMin: Double = 105.0,
    /** 영아: 수유 간격 (1~6개월 3시간). 카시트 상한이 따로 걸린다. 근거: AI §9-1 (AAP 2024) */
    val priorInfantMin: Double = 180.0,
    /** 반려견 (성견). 근거: AI §9-1 (AKC) */
    val priorDogMin: Double = 150.0,
    /** 어르신·임신부 정차 간 상한 (걷기 권고, 분). 근거: AI §3-A, §9-1 (CDC 2024, 캐나다 정부) */
    val walkCapMin: Double = 120.0,
    /** 영아 카시트 연속 상한 (분). 근거가 제한적인 관행 권고. 근거: AI §3-A, §9-1 */
    val infantSeatCapMin: Double = 120.0,
    /** 과민성 방광 커버리지: 화장실 기회 간격이 이보다 길면 그 앞에서 묻는다 (분). 근거: AI §9-1 */
    val oabMaxOpportunityGapMin: Int = 20,
    /** 과민성장증후군 커버리지 (분). 근거: AI §9-1 (15~20분) */
    val ibsMaxOpportunityGapMin: Int = 15,

    // 운전 패턴 편차 (보조 신호). 근거: AI §1, §9-2
    /** 특징을 계산하는 창 길이 (초). 근거: AI §9-2 */
    val styleWindowS: Int = 60,
    /** 이 속도 이상, 제동·방향지시등이 없고 그 뒤 10초가 지난 표본만 '정속 주행'. 근거: AI §9-2 (EU DDAW 70km/h) */
    val styleSteadyMinKph: Double = 70.0,
    /** 출발 뒤 정속 주행 첫 몇 분은 기준선에서 빼고 이번 차의 조향 변화 크기만 잰다. 근거: AI §9-2 */
    val styleWarmupMin: Int = 5,
    /** 조향 반전 gap = max(이 하한, steeringGapFactor × 준비 구간 조향 변화 중앙값). 단위를 모르는 앞바퀴각이라 차마다 상대값으로 정한다 */
    val steeringReversalGapDeg: Double = 0.1,
    val steeringGapFactor: Double = 2.0,
    /** 급제동으로 볼 감속 (km/h/s). 8 ≈ 0.23g. 근거: 엔진 v0 */
    val hardBrakeKphPerS: Double = 8.0,
    /** 편차 지수이동평균 λ. 근거: AI §9-2 (NIST, λ = 0.2) */
    val styleEwmaAlpha: Double = 0.2,
    /** 크루즈 작동 중 조향 특징 가중치 배수 (차로 유지 보조 개입을 알 수 없다). 근거: AI §9-2 */
    val cruiseSteeringWeight: Double = 0.5,
    /** '큰 조향'(묻지 않는 조건): 1초 사이 조향 변화 (도). 근거: 엔진 v0 */
    val largeSteerRateDeg: Double = 15.0,
    /** 상태 확인에 "아니"라고 하면 운전 패턴 편차를 이만큼 피로에 반영하지 않는다 (분) */
    val styleMuteAfterDeclineMin: Int = 20,

    // 피로: 위험 시간대 (자정부터 분, 끝 포함). 근거: AI §1, §9-2 (Horne & Reyner 1995: 02~06시 강, 14~16시 중)
    /** 새벽 졸음 시간대. 02:00~05:59 */
    val nightCircadianWindow: IntRange = 2 * 60 until 6 * 60,
    /** 오후 졸음 시간대. 14:00~15:59 */
    val afternoonCircadianWindow: IntRange = 14 * 60 until 16 * 60,

    // 피로: 졸림 사전 곡선 (Three-Process Model). 근거: AI §1, §9-2 (Åkerstedt 2004, Ingre 2014)
    /** 항상성 S: 깨어 있는 동안 시간당 지수 감소율 */
    val tpmDecayPerHour: Double = 0.0353,
    val tpmLowerAsymptote: Double = 2.4,
    /** 충분히 자고 일어났을 때의 S (상한) */
    val tpmUpperAsymptote: Double = 14.3,
    /** 생체리듬 C: 진폭과 정점 시각 */
    val tpmCircadianAmplitude: Double = 2.5,
    val tpmCircadianPeakHour: Double = 16.8,
    /** KSS 사전값이 이 값에 닿으면 NOW (AI §8-3 L1 '권고') */
    val tpmNowKss: Double = 6.0,
    /** 이 값 이상이면 '강한 권고' (AI §8-3 L2) */
    val tpmStrongKss: Double = 7.0,
    /** 기상 시각을 모를 때 쓰는 기상 시각 (자정부터 분) */
    val defaultWakeMinuteOfDay: Int = 7 * 60,
    /** 깨어 있은 지 이 시간 이상이면 한 단계 가중. 근거: AI §9-2 (Williamson & Feyer 2000) */
    val awakeLongHours: Double = 17.0,
    val awakeLongFactor: Double = 0.8,
    /** 지난밤 수면이 이보다 짧으면 두 단계. 근거: AI §9-2 */
    val veryShortSleepHours: Float = 5f,
    /** 크루즈 작동 + 이 속도 이상이면 단조로움으로 보고 약하게 가중 (×monotonyFactor). 근거: AI §1 (약한 문맥 보정) */
    val monotonyMinKph: Double = 70.0,
    val monotonyFactor: Double = 0.9,
    /** 휴식 뒤 졸림 사전 곡선을 다시 볼 때까지 (분). 카페인·짧은 낮잠 효과 약 1시간. 근거: AI §2, §9-2 */
    val countermeasureReevalMin: Int = 60,
    /** update 사이 간격이 이보다 길면 그 이상은 주행 시간으로 세지 않는다 (앱 정지·시계 점프 대비, 초) */
    val maxUpdateGapS: Int = 120,
    /** 피로 SOON 이상이고 설정 온도가 이 값 이상이면 COOL_DOWN을 권한다 (°C). 근거: 엔진 v0 */
    val coolDownAboveC: Float = 24f,
    /** 환기 권유에 필요한 탑승 인원 (피로 SOON 이상이면 1명이어도). 근거: AI §1, §9-2 (Hudda & Fruin 2018) */
    val freshAirMinOccupants: Int = 2,

    // 묻는 타이밍
    /** 질문을 화면·음성에 유지하는 시간 (초). 지나면 대답 없음으로 본다. 근거: 엔진 v0 */
    val promptTtlS: Int = 45,
    /** 휴식 제안을 묻는 가장 먼 거리 (m). 톰프슨 샘플링 구간: 2~5, 5~10, 10~20km */
    val restAskFarM: Double = 20_000.0,
    /** 질문·음성 대화 뒤 이만큼은 먼저 말을 걸지 않는다 (초). 근거: AI §5, §9-3 (AAA 2015: 잔여 분산 최대 27초) */
    val quietAfterInteractionS: Int = 30,
    /** 지금 속도로 다음 회전까지 이 시간 안이면 묻지 않는다 (초). 근거: AI §9-3 */
    val maneuverQuietS: Int = 30,
    /** 거절이 이어질 때 쉼의 상한 (분). 근거: AI §9-3 */
    val declineCooldownMaxMin: Int = 60,
    /** 환기 권유의 쉼 (분). 근거: AI §9-2 */
    val freshAirCooldownMin: Int = 60,

    // 급해 모드 (분)
    val parkWalkShelterMin: Double = 1.0,
    /** 공중화장실·기타 장소의 주차·도보 */
    val parkWalkOtherMin: Double = 2.0,
    /** 공영주차장에서 도보 거리를 모를 때 더하는 시간 */
    val defaultWalkMin: Double = 2.0,
    /** 도보 속도 (m/분) */
    val walkSpeedMPerMin: Double = 70.0,
    /** 영업 여부를 모를 때 순위 감점 (분 환산) */
    val unknownOpenPenaltyMin: Double = 3.0,
    /** 진행 방향인지 모를 때 기대 유턴 비용 (분) */
    val uTurnUnknownPenaltyMin: Double = 1.0,
    /** 야간 비상벨·CCTV 가점 (분 환산). 고속도로 졸음쉼터는 규정상 기본 시설이라 데이터가 없어도 준다. 근거: AI §3-C */
    val nightSafetyBonusMin: Double = 2.0,
    /** 영아 동승 시 기저귀 교환대 가점 (분 환산) */
    val diaperTableBonusMin: Double = 3.0,
    /** 야간 시간대 [nightStartHour, 24) ∪ [0, nightEndHour) */
    val nightStartHour: Int = 22,
    val nightEndHour: Int = 6,
    /** 정차 중 보여 줄 급해 결과 수 */
    val urgentMaxResults: Int = 5,
    /** 주행 중 보여 줄 급해 결과 수 (SPEC: 1~2곳) */
    val urgentMaxResultsDriving: Int = 2,

    // 피트스톱 (분). 근거: AI §9-3
    val feedingDwellMin: Int = 25,
    val fuelDwellMin: Int = 10,
    val petDwellMin: Int = 10,
    /** 정차마다 드는 진출입 손실. 한곳에 목적을 몰면 줄어들어 묶음 정차가 나온다 */
    val stopOverheadMin: Int = 5,
    /** 남은 주행가능거리에서 남겨 둘 여유 (km). 근거: 엔진 v0 */
    val fuelReserveKm: Double = 50.0,

    // ---------------- 화장실 문맥 보정 (모두 기본값이 있다. 배수 1.0이면 그 보정을 끈다) ----------------
    // 근거: docs/research/need-covariates.md ('NC §n'). 배수 m은 '필요해지는 시간'에 곱한다(m < 1이면 더 빨리).
    // 넣지 않는 것(NC §9): 깨어 있는 사람의 시간대 배수, 기온·계절, 카페인, 주행 신호로 요의를 안다는 주장.

    /**
     * 식사 정차 뒤 구간의 배수. 마신 물의 82%가 2시간 안에 나온다(Maughan 2016). 크기는 파생값이라 범위 0.6~1.0 중 0.8.
     * '정차 시작 3분에 시계를 돌리는' 규칙(restroomStopMinParkedMin) 위에서 정한 값이라 그 규칙을 바꾸면 다시 정한다.
     * 근거: NC §2 6행, §8
     */
    val mealIntervalFactor: Double = 0.8,
    /** 저녁([mealEveningStartHour]시 이후)에 시작한 식사 정차의 배수. 저녁에 마신 물은 아침의 0.3~0.6배만 나온다. 근거: NC §1 6행 (Claybaugh 2000), §8 */
    val mealIntervalFactorEvening: Double = 0.9,
    /** 식사 정차로 볼 최소 정차 시간 (분, 기어 P). 식사 1회 섭취 약 0.4L를 '음식 있는 곳에서 20분 이상'으로 추정한다. 근거: NC §2 5행, §8 */
    val mealStopMinMin: Int = 20,
    /** 선 곳이 음식 있는 곳인지 볼 때, 후보가 이 거리 안이면 그곳에 선 것으로 본다 (m). 정차 기록의 장소 이름 반경과 같다. 근거: NC §8 */
    val mealPlaceRadiusM: Double = 300.0,
    /** 이 시각(현지 시) 이후에 시작한 식사 정차는 [mealIntervalFactorEvening]을 쓴다. 근거: NC §1 6행 (20시 측정), §8 */
    val mealEveningStartHour: Int = 20,
    /**
     * 밤에 잠든 아이의 배수 (화면 게이지에만). 잠든 아이의 소변 생성은 낮의 0.51배(Mahler 2013), 담는 양은 1.43배(Rittig 2010)라
     * 범위 1.3~2.0 중 보수적인 1.5. 판단 마감에는 쓰지 않는다(깨면 바로 화장실을 찾을 수 있다). 근거: NC §1 4·5행, §8, §10-4
     */
    val asleepIntervalFactor: Double = 1.5,
    /** 잠든 아이 배수를 거는 밤 시간대 [asleepNightStartHour, asleepNightEndHour) (현지 시, 자정을 넘는다). 낮잠은 1.0. 근거: NC §8 */
    val asleepNightStartHour: Int = 20,
    val asleepNightEndHour: Int = 7,
)
