package dev.hmg.pleosapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 지금 차량 상태를 한데 모은 값. null은 아직 값을 받지 못했다는 뜻이다. */
data class DriveSnapshot(
    val gear: Int? = null,
    val speedKph: Float? = null,
    val drivingRestricted: Boolean = false,
    val recirculation: Boolean? = null,
    val hvacPower: Boolean? = null,
    val cabinTargetTempC: Float? = null,
    val cruise: String? = null,
    val tripDrivingMin: Int? = null,
    val brakePressed: Boolean? = null,
    val driverDoorOpen: Boolean? = null,
    val stateOfCharge: Int? = null,
    val naviMode: String? = null,
)

/**
 * 서비스(DriveService)가 채우고 화면과 기능 로직이 읽는 상태 저장소.
 * 서비스와 Activity가 같은 프로세스에 있으므로 바인딩 없이 싱글턴으로 공유한다.
 */
object DriveState {
    private val _snapshot = MutableStateFlow(DriveSnapshot())
    val snapshot: StateFlow<DriveSnapshot> = _snapshot

    fun update(change: DriveSnapshot.() -> DriveSnapshot) = _snapshot.update(change)
}
