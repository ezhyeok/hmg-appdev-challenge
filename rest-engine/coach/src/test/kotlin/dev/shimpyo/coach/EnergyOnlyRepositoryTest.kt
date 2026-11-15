package dev.shimpyo.coach

import dev.shimpyo.engine.*
import dev.shimpyo.engine.stops.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EnergyOnlyRepositoryTest {
    @Test fun chargerScheduleIsAppliedBeforeRangeSelection() = runBlocking {
        val record = assertNotNull(StopRows.map(StopFile.EV_CHARGERS, mapOf("id" to "ev", "n" to "충전소",
            "lat" to 37.001, "lon" to 127.0, "dc" to 1, "oh" to "09:00-18:00")))
        val repository = StopRepository { StopTable.decode(StopTable.encode(listOf(record))) }
        repository.ensureLoaded()
        val night = java.time.Instant.parse("2026-11-15T14:00:00Z").toEpochMilli()
        val day = java.time.Instant.parse("2026-11-15T03:00:00Z").toEpochMilli()
        fun at(time: Long) = repository.ahead(37.0, 127.0, null, 5_000.0, setOf(StopKind.OTHER), 75.0, time).single()
        assertEquals(false, at(night).openNow)
        assertEquals(true, at(day).openNow)
    }
    @Test fun energyRowsCannotFillTheRestroomSearchLimit() = runBlocking {
        val records = (1..30).map { i -> StopRecord(
            StopCandidate("energy$i", "주유소$i", StopKind.GAS_STATION, 37.0 + i / 100_000.0, 127.0,
                Facilities(restroom = false, fuel = true), parkable = true, openNow = true), null, null)
        } + StopRecord(StopCandidate("wc", "화장실", StopKind.GAS_STATION, 37.001, 127.0,
            Facilities(fuel = true, restroom = true), parkable = true, openNow = true), null, null)
        val repository = StopRepository { StopTable.decode(StopTable.encode(records)) }
        assertEquals(31, repository.ensureLoaded())
        val kinds = setOf(StopKind.GAS_STATION)
        assertEquals(listOf("energy1"), repository.near(37.0, 127.0, null, 5_000.0, kinds, 0L, 1).map { it.id })
        assertEquals(listOf("wc"), repository.nearRestrooms(37.0, 127.0, null, 5_000.0, kinds, 0L, 1).map { it.id })
        assertEquals(listOf("wc"), repository.nearUsable(37.0, 127.0, null, 5_000.0, kinds, 0L, 1).map { it.id })
        val nearby = NearbyStops(repository).current(Fix(37.0, 127.0, null), null, false, 0f, null, 0L, 0L, NearbyMode.ALWAYS)
        assertEquals(listOf("wc"), nearby.map { it.id })
        assertEquals(31, repository.ahead(37.0, 127.0, null, 5_000.0, kinds).size)
    }
}
