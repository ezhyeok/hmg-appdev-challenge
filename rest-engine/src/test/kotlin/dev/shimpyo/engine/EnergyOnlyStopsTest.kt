package dev.shimpyo.engine

import dev.shimpyo.engine.stops.StopFile
import dev.shimpyo.engine.stops.StopRows
import kotlin.test.*

/** 데이터 변환부터 소비자까지: 화장실 증거가 없는 에너지 시설을 화장실로 권하지 않는다. */
class EnergyOnlyStopsTest {
    private val now = kst(2026, 11, 14, 10, 0)
    private fun row(ev: Boolean): StopCandidate = assertNotNull(StopRows.map(
        if (ev) StopFile.EV_CHARGERS else StopFile.PARKABLES,
        mapOf("id" to "energy", "n" to "에너지 전용", "lat" to 37.0, "lon" to 127.0, "k" to "fuel", "dc" to 2, "h24" to 1),
    )).candidate.copy(routeDistanceM = 4_500.0, straightDistanceM = 3_000.0, directionOk = true)

    private fun engine() = CoachEngine(profile = TripProfile(listOf(adult(), child(age = 7)), tripStart = now))
    private fun input(c: StopCandidate) = CoachInput(now, Gear.DRIVE, speedKph = 60f,
        route = RouteState(true, remainDistanceM = 200_000.0), candidates = listOf(c), nearby = listOf(c))

    @Test fun urgentAndNearbyRestroomsRejectBothEnergyOnlyKinds() {
        for (ev in listOf(false, true)) {
            val c = row(ev)
            assertTrue(engine().urgent(input(c)).isEmpty())
            val out = engine().update(input(c).copy(intent = VoiceIntent.UrgentRestroom))
            assertTrue(out.urgent.orEmpty().isEmpty())
            assertNull(out.prompt?.stop)
            assertNull(engine().update(input(c).copy(route = null, candidates = emptyList())).next)
        }
    }

    @Test fun restroomLastChanceNeverUsesAnEnergyOnlyStop() {
        for (ev in listOf(false, true)) {
            val c = row(ev)
            val out = engine().update(input(c))
            assertNull(out.next)
            val plan = engine().plan(input(c))
            assertTrue(plan.stops.none { Purpose.RESTROOM in it.purposes })
            assertFalse(RouteContext.isRestroomOpportunity(c))
        }
    }

    @Test fun rangeLastChanceCanUseEitherEnergyOnlyKind() {
        for (ev in listOf(false, true)) {
            val c = row(ev)
            val out = engine().update(input(c).copy(rangeKm = 60.0,
                energy = if (ev) EnergyKind.ELECTRIC else EnergyKind.FUEL))
            val next = assertNotNull(out.next)
            assertEquals(c.id, next.stop.id)
            assertEquals(setOf(Purpose.FUEL), next.purposes)
            assertTrue(next.reasons.any { it.code == "RANGE_LAST_CHANCE" })
        }
    }
}
