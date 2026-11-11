package dev.shimpyo.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun parkedFlagFollowsGear() {
        val engine = CoachEngine(profile = TripProfile(occupants = listOf(Occupant("me", OccupantType.ADULT)), tripStart = 0))
        val out = engine.update(CoachInput(now = 1_000, gear = Gear.PARK))
        assertEquals(true, out.parked)
    }
}
