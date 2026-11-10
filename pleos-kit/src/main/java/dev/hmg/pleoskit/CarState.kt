package dev.hmg.pleoskit

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * AAOS 표준 Car API로 얻는 주행 상태. 차속과 기어는 Vehicle SDK에 없어서 `CarPropertyManager`를 쓴다.
 *
 * 필요한 권한
 * - 기어: `android.car.permission.CAR_POWERTRAIN` (normal)
 * - 차속: `android.car.permission.CAR_SPEED` (dangerous, 실행 중 요청 필요). 없으면 [speedMps]는 null로 남는다.
 */
class CarState(context: Context) {

    private val appContext = context.applicationContext
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null

    private val _gear = MutableStateFlow<Int?>(null)
    private val _speedMps = MutableStateFlow<Float?>(null)
    private val _drivingRestricted = MutableStateFlow(false)

    /** 현재 기어. `android.car.VehicleGear` 상수(GEAR_PARK = 4, GEAR_DRIVE = 8 등). */
    val gear: StateFlow<Int?> = _gear

    /** 차속(m/s). km/h는 3.6을 곱한다. */
    val speedMps: StateFlow<Float?> = _speedMps

    /** 주행 중 UX 제한이 걸려 있는지(`CarUxRestrictions.isRequiresDistractionOptimization`). true면 화면 조작을 줄여야 한다. */
    val drivingRestricted: StateFlow<Boolean> = _drivingRestricted

    val isParked: Boolean get() = _gear.value == VehicleGear.GEAR_PARK

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            _gear.value = value.value as? Int
        }

        override fun onErrorEvent(propId: Int, zone: Int) = KitLog.w("GEAR_SELECTION error zone=$zone")
    }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            _speedMps.value = value.value as? Float
        }

        override fun onErrorEvent(propId: Int, zone: Int) = KitLog.w("PERF_VEHICLE_SPEED error zone=$zone")
    }

    /** Car 서비스에 연결하고 구독을 시작한다. 권한이 없는 항목은 건너뛰고 로그만 남긴다. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        if (car != null) return@withContext
        val c = Car.createCar(appContext)
        car = c
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        propertyManager = pm
        runCatching {
            pm.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { KitLog.w("gear subscription failed: ${it.message}") }
        runCatching {
            pm.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }.onFailure { KitLog.w("speed subscription failed (CAR_SPEED 권한 확인): ${it.message}") }
        runCatching {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = ux
            _drivingRestricted.value = ux.currentCarUxRestrictions.isRequiresDistractionOptimization
            ux.registerListener { r -> _drivingRestricted.value = r.isRequiresDistractionOptimization }
        }.onFailure { KitLog.w("ux restriction subscription failed: ${it.message}") }
        Unit
    }

    fun release() {
        runCatching { propertyManager?.unregisterCallback(gearCallback) }
        runCatching { propertyManager?.unregisterCallback(speedCallback) }
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        car = null
        propertyManager = null
        uxManager = null
    }
}
