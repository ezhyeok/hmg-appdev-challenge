package dev.shimpyo.app.data

import dev.shimpyo.coach.AcceptedStop
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
    fun theAcceptedStopSurvivesARestart() {
        // SV-N7: 주행 중 재시작 뒤 방금 수락한 휴게소를 다시 묻지 않으려면 맥박에 들르기로 한 곳이 남아야 한다
        val accepted = h.copy(park = null, accepted = AcceptedStop("k06e247e4", 1_762_909_100_000L))
        assertEquals(accepted, TripJson.decode(TripJson.encode(accepted)))
        assertNull("없으면 null", TripJson.decode(TripJson.encode(h))!!.accepted)
    }

    @Test
    fun aHeartbeatWrittenBeforeTheAcceptedStopExistedStillResumesTheTrip() {
        val old = """{"version":1,"savedAt":1762909200000,"tripStart":1762903800000,"lastRestroomAt":{"driver":1762900000000},""" +
            """"restroomFallbackAt":1762900000000,"continuousDrivingMin":85.5,"park":null}"""
        val read = TripJson.decode(old)!!
        assertNull(read.accepted)
        assertEquals(1_762_903_800_000L, read.tripStart)
        // 들르기로 한 곳만 깨져 있으면 그것만 버리고 여정은 이어 간다
        val broken = old.dropLast(1) + ""","accepted":{"stopId":"k1"}}"""
        assertEquals(read, TripJson.decode(broken))
    }

    @Test
    fun brokenOrForeignFilesReadAsNothing() {
        assertNull(TripJson.decode(""))
        assertNull(TripJson.decode("{\"savedAt\": 1}"))
        assertNull(TripJson.decode("not json"))
    }
}
