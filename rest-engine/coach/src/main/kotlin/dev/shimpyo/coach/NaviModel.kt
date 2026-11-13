package dev.shimpyo.coach

/*
 * 내비 도메인 타입. Pleos NaviHelper의 데이터 클래스를 앱(KitNaviPort)이 이 타입으로 바꿔 넘긴다.
 * 코치 계층이 SDK 타입을 몰라야 안드로이드 없이 테스트할 수 있다. 필드 뜻은 SDK 레퍼런스와 같고 단위만 정리했다.
 */

/** NaviHelper NaviMode와 같은 값. 모르는 값은 UNKNOWN */
enum class NaviMode {
    /** 내비 초기화·지도 내려받기 중 */
    NOT_WORKING,
    /** 경로 없는 안전운전 모드 (경로 전 상태, 에뮬레이터 확인) */
    SAFE_GUIDING,
    ROUTE_GUIDING,
    /** 내비의 모의 주행 (경로 있음) */
    SIMULATION_GUIDING,
    UNKNOWN;

    /** 경로가 있는 길안내인지. 길안내 여부는 이것 하나로만 판단한다 */
    val hasRoute: Boolean get() = this == ROUTE_GUIDING || this == SIMULATION_GUIDING
}

/**
 * TrafficCongestionInfo. offset의 기준(출발지/현재 위치)은 미확인이라 CongestionBasis가 주행 중에 판정한다.
 *
 * [durationS]는 **이 구간을 지나는 시간**(엔진 CongestionSegment.durationS와 같은 뜻)이다. SDK의 trafficCongestionTime은 그것이 아니라
 * 분 단위로 반올림한 늘어난 시간이다(최소 1분): 2026-11-12 에뮬레이터에서 30~100m 항목이 모두 60초였고(평균 속도로는 1~16초),
 * 요약 5,900m·35km/h가 300초였다(평균 속도로는 약 600초). 그래서 지나는 시간은 거리 ÷ 평균 속도(avrSpeed, km/h)로 구한다
 * ([crossingS]). 원래 값은 [delayS]·[avrSpeedKph]로 남긴다(로그·화면 설명용). CLAUDE.md 'API 사용 시 주의'.
 */
data class NaviCongestion(
    val offsetM: Double,
    val lengthM: Double,
    /** 구간을 지나는 시간 (초). [fromNavi]가 평균 속도로 구한다 */
    val durationS: Double,
    val type: String,
    val roadName: String,
    val startName: String,
    val endName: String,
    /** 내비가 준 trafficCongestionTime (초, 분 단위 반올림한 늘어난 시간). 모르면 null */
    val delayS: Double? = null,
    /** 내비가 준 구간 평균 속도 (km/h). 모르면 null */
    val avrSpeedKph: Double? = null,
) {
    companion object {
        /**
         * 평균 속도를 쓸 수 없을 때의 자유 흐름 속도 (km/h). 실측 요약에서 '늘어난 시간 = 지나는 시간 × (1 - 평균/자유)'로 거꾸로 구하면
         * 70~86km/h(분 반올림 오차 안)였고, 엔진이 고속도로 평균 속도로 쓰는 값(CoachConfig.highwayAvgSpeedKph)도 80이라 80으로 둔다.
         */
        const val FREE_FLOW_KPH = 80.0
        /** 이보다 느린 평균 속도는 믿지 않는다 (0이나 소수점 잡음이면 지나는 시간이 끝없이 길어진다) */
        const val MIN_SPEED_KPH = 1.0
        /** 지나는 시간의 상한 (6시간). 명절 귀성길 정체도 이 안이다. 깨진 값이 엔진의 도착 시간을 하루 뒤로 밀지 않게 */
        const val MAX_CROSSING_S = 6 * 3_600.0

        /**
         * 구간을 지나는 시간 (초). 평균 속도를 쓸 수 있으면 거리 ÷ 평균 속도, 아니면 자유 흐름 시간 + 늘어난 시간(자유 흐름보다 짧지 않다).
         * 길이가 깨졌으면(NaN·무한·0 이하) null (항목을 버린다)
         */
        fun crossingS(lengthM: Double, avrSpeedKph: Double?, delayS: Double?, freeSpeedKph: Double = FREE_FLOW_KPH): Double? {
            if (!lengthM.isFinite() || lengthM <= 0.0) return null
            val s = if (avrSpeedKph != null && avrSpeedKph.isFinite() && avrSpeedKph >= MIN_SPEED_KPH) {
                lengthM / (avrSpeedKph / 3.6)
            } else {
                lengthM / (freeSpeedKph / 3.6) + (delayS?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0)
            }
            return s.coerceAtMost(MAX_CROSSING_S)
        }

        /** SDK 항목 하나 (KitNaviPort). 길이·위치가 깨졌으면 null */
        fun fromNavi(
            offsetM: Double, lengthM: Double, delayS: Double?, avrSpeedKph: Double?,
            type: String, roadName: String, startName: String, endName: String,
        ): NaviCongestion? {
            if (!offsetM.isFinite()) return null
            val crossing = crossingS(lengthM, avrSpeedKph, delayS) ?: return null
            return NaviCongestion(
                offsetM, lengthM, crossing, type, roadName, startName, endName,
                delayS = delayS?.takeIf { it.isFinite() }, avrSpeedKph = avrSpeedKph?.takeIf { it.isFinite() },
            )
        }
    }
}

/** RouteStateInfo 중 코치가 쓰는 것 */
data class NaviRouteStatus(
    val mode: NaviMode,
    val congestion: List<NaviCongestion> = emptyList(),
)

/** DestinationInfo 중 코치가 쓰는 것 (거리 m, 시간 초) */
data class NaviDestination(
    val lat: Double,
    val lon: Double,
    val name: String,
    val remainDistanceM: Double,
    val remainTimeS: Double,
)

/** TBTInfo.type 중 코치가 구분하는 것 */
enum class TbtType {
    /** TURN_ENTER_SERVICEAREA: 휴게소 (하이패스센터 같은 서비스 시설도 온다) */
    SERVICE_AREA,
    /** TURN_RESTAREA: 졸음쉼터 */
    REST_AREA,
    OTHER,
}

/** TBTInfo. distanceM은 직전 항목부터의 거리다(첫 항목만 현재 위치부터). 누적해야 경로 거리가 된다 */
data class TbtItem(val type: TbtType, val distanceM: Double, val description: String)

/** 위치 한 점. heading은 도(북=0), 모르면 null */
data class Fix(val lat: Double, val lon: Double, val heading: Float?)

/** requestTrip 결과 (길안내를 시작하지 않는다) */
data class TripEstimate(val distanceM: Double, val etaS: Double)

/** 내비가 먼저 보내는 경로 변화 이벤트 (NaviHelper 콜백) */
enum class NaviChange {
    ROUTE_STARTED,
    ROUTING_COMPLETED,
    ROUTE_CANCELLED,
    REROUTED,
    DESTINATION_CHANGED,
    WAYPOINT_CHANGED,
    WAYPOINT_ARRIVED,
    DESTINATION_ARRIVED,
}

/**
 * 내비가 알려 준 지금 도로의 제한속도 [kph]와 받은 때 [atMs](ElapsedClock). 내비는 매초 보내므로 [FRESH_MS] 넘게 소식이 없으면
 * 모르는 것으로 본다 (내비가 멈췄거나 도로를 놓쳤다)
 */
data class RoadLimit(val kph: Int, val atMs: Long) {
    /** 지금 믿을 수 있는 제한속도. 오래됐거나 0 이하(모름)면 null */
    fun fresh(nowMs: Long): Int? = kph.takeIf { it > 0 && nowMs - atMs in 0..FRESH_MS }

    companion object {
        const val FRESH_MS = 5_000L
    }
}

/** 경유지 추가의 결과 (NaviPort.addWaypoint) */
enum class WaypointResult {
    ADDED,
    /** 경유지가 이미 둘이라 내비가 받지 않는다 */
    FULL,
    /** 밖에서 넣은 경유지보다 앞이라 순서를 맞출 수 없어 보내지 않았다 */
    AHEAD_OF_OTHER,
    /** 보냈지만 들어갔는지 확인하지 못했다 */
    NOT_CONFIRMED,
}
