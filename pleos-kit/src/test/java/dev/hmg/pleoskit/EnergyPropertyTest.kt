package dev.hmg.pleoskit

import org.junit.Assert.*
import org.junit.Test

class EnergyPropertyTest {
    @Test fun integerVectorAcceptsAaosBoxedArrayAndPrimitiveArray() {
        assertArrayEquals(intArrayOf(10, 1), CarState.intVector(arrayOf(10, 1)))
        assertArrayEquals(intArrayOf(10), CarState.intVector(intArrayOf(10)))
        assertNull(CarState.intVector(arrayOf(10, "bad")))
        assertNull(CarState.intVector(10))
        assertNull(CarState.intVector(null))
    }
    @Test fun energyIsRequestedWithTheOtherRuntimePermissions() {
        assertTrue("android.car.permission.CAR_ENERGY" in PleosPermissions.RUNTIME)
    }
}
