package dev.shimpyo.app.data

import dev.shimpyo.coach.ParkState
import dev.shimpyo.coach.TripHeartbeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 여정 맥박 trip.json (world.md 7장). 깨진 파일은 '없음'이라 새 여정으로 시작한다 */
class TripJsonTest {
    private val h = TripHeartbeat(
        savedAt = 1_762_909_200_000L, tripStart = 1_762_903_800_000L, lastRestroomAt = mapOf("driver" to 1_762_900_000_000L, "kid" to 1_762_905_000_000L),
        restroomFallbackAt = 1_762_900_000_000L, continuousDrivingMin = 85.5, park = ParkState(1_762_908_900_000L, doorSeen = true),
    )

    @Test
    fun aHeartbeatSurvivesTheRoundTrip() {
        assertEquals(h, TripJson.decode(TripJson.encode(h)))
        val driving = h.copy(park = null, lastRestroomAt = emptyMap())
        assertEquals(driving, TripJson.decode(TripJson.encode(driving)))
    }

    @Test
    fun brokenOrForeignFilesReadAsNothing() {
        assertNull(TripJson.decode(""))
        assertNull(TripJson.decode("{\"savedAt\": 1}"))
        assertNull(TripJson.decode("not json"))
    }
}
