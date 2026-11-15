package dev.shimpyo.app.coach

import android.car.FuelType
import dev.shimpyo.engine.EnergyKind
import org.junit.Assert.*
import org.junit.Test

class EnergyPropertyTest {
    @Test fun fuelTypesSeparateElectricFuelHybridAndUnknown() {
        assertEquals(EnergyKind.ELECTRIC, CarSignals.energyKindOf(intArrayOf(FuelType.ELECTRIC)))
        assertEquals(EnergyKind.FUEL, CarSignals.energyKindOf(intArrayOf(FuelType.UNLEADED)))
        assertEquals(EnergyKind.FUEL, CarSignals.energyKindOf(intArrayOf(FuelType.DIESEL_1, FuelType.UNLEADED)))
        assertNull(CarSignals.energyKindOf(intArrayOf(FuelType.ELECTRIC, FuelType.UNLEADED)))
        assertNull(CarSignals.energyKindOf(intArrayOf(FuelType.UNKNOWN)))
        assertNull(CarSignals.energyKindOf(intArrayOf()))
        assertNull(CarSignals.energyKindOf(null))
    }
    @Test fun rangeMetresBecomeKmAndInvalidValuesStayUnknown() {
        assertEquals(38.5, CarSignals.rangeKmOf(38500f)!!, 0.0)
        assertEquals(0.0, CarSignals.rangeKmOf(0f)!!, 0.0)
        for (value in listOf(null, -1f, Float.NaN, Float.POSITIVE_INFINITY)) assertNull(CarSignals.rangeKmOf(value))
    }
}
