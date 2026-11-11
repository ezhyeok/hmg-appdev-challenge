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

/** TrafficCongestionInfo. offset의 기준(출발지/현재 위치)은 미확인이라 CongestionBasis가 주행 중에 판정한다 */
data class NaviCongestion(
    val offsetM: Double,
    val lengthM: Double,
    /** trafficCongestionTime (초) */
    val durationS: Double,
    val type: String,
    val roadName: String,
    val startName: String,
    val endName: String,
)

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
