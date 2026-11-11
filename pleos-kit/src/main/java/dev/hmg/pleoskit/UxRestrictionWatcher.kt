package dev.hmg.pleoskit

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * 이미 연결한 [Car]에서 UX 제한을 구독해 [state]로 낸다. [CarState]와 [UxRestrictionWatcher]가 같이 쓴다.
 *
 * 리스너(메인 스레드)를 먼저 걸고 현재 값을 읽는다. 둘 사이에 온 변경을 놓치지 않으려는 순서이고, 현재 값은
 * 아직 리스너 값이 없을 때만(Unknown일 때만) 쓴다. 그렇지 않으면 늦게 읽은 옛 값이 새 리스너 값을 덮을 수 있다.
 */
internal class UxRestrictionTracker {
    private val _state = MutableStateFlow(UxRestrictions.NOT_CONNECTED)
    private val _restricted = MutableStateFlow(true)
    val state: StateFlow<UxRestrictions> = _state
    /** [state]의 [restricted]. 기존 `CarState.drivingRestricted`(Boolean) 사용처를 위해 같이 낸다 */
    val restricted: StateFlow<Boolean> = _restricted
    private var manager: CarUxRestrictionsManager? = null

    private fun set(value: UxRestrictions) {
        _state.value = value
        _restricted.value = value.restricted
    }

    fun attach(car: Car) {
        runCatching {
            val ux = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            manager = ux
            ux.registerListener { r -> set(r.toKnown()) }
            val before = _state.value
            val current = ux.currentCarUxRestrictions.toKnown()
            if (before is UxRestrictions.Unknown && _state.compareAndSet(before, current)) _restricted.value = current.restricted
        }.onFailure {
            KitLog.w("ux restriction subscription failed: ${it.message}")
            set(UxRestrictions.Unknown("구독 실패: ${it.message}"))
        }
    }

    /** Car 연결 실패처럼 붙이기 전에 끝난 경우 */
    fun fail(cause: String) = set(UxRestrictions.Unknown(cause))

    fun detach() {
        runCatching { manager?.unregisterListener() }
        manager = null
        set(UxRestrictions.Unknown("연결 해제"))
    }
}

private fun CarUxRestrictions.toKnown() = UxRestrictions.Known(
    requiresDistractionOptimization = isRequiresDistractionOptimization,
    activeRestrictions = activeRestrictions,
    maxStringLength = maxRestrictedStringLength,
    maxContentItems = maxCumulativeContentItems,
    maxContentDepth = maxContentDepth,
)

/**
 * UX 제한만 구독하는 Car 연결. 화면(Activity)이 서비스 없이 주행 제한을 알아야 할 때 쓴다
 * (서비스가 늦게 뜨거나 죽어도 화면은 제한을 스스로 안다). 권한이 필요 없다.
 *
 * `Car.createCar(Context)`는 연결될 때까지 막으므로 [connect]는 IO 스레드에서 연결한다.
 * Car 서비스가 죽으면 플랫폼이 이 연결을 쓰는 프로세스를 함께 끝낸다(상태 리스너 없이 만든 Car의 기본 동작).
 */
class UxRestrictionWatcher(context: Context) {
    private val appContext = context.applicationContext
    private val tracker = UxRestrictionTracker()
    private var car: Car? = null

    val state: StateFlow<UxRestrictions> = tracker.state

    suspend fun connect() = withContext(Dispatchers.IO) {
        if (car != null) return@withContext
        val c = runCatching { Car.createCar(appContext) }.getOrElse {
            KitLog.w("Car connect failed: ${it.message}")
            tracker.fail("Car 연결 실패: ${it.message}")
            return@withContext
        }
        car = c
        tracker.attach(c)
    }

    fun release() {
        tracker.detach()
        runCatching { car?.disconnect() }
        car = null
    }
}
