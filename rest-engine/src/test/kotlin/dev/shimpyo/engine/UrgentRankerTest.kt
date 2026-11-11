package dev.shimpyo.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UrgentRankerTest {
    private val cfg = CoachConfig()
    private val ranker = UrgentRanker(cfg, CoachTexts())
    private val local = LocalClock(SEOUL)
    private val day = kst(2026, 11, 13, 14, 0)
    private val night = kst(2026, 11, 13, 23, 0)

    private fun approx(e: Double, a: Double, tol: Double) = assertTrue(abs(e - a) <= tol, "expected $e±$tol but $a")

    @Test
    fun totalTimeIsDrivePlusParkWalkPlusUTurn() {
        // 시내 25km/h: 1km = 2.4분
        val gas = stop("gas", 1_000.0, StopKind.GAS_STATION)
        val area = stop("area", 1_000.0, StopKind.HIGHWAY_REST_AREA)
        val parking = stop("parking", 1_000.0, StopKind.PUBLIC_PARKING).copy(walkDistanceM = 140.0)
        val uTurn = stop("uturn", 1_000.0, StopKind.GAS_STATION, directionOk = false)
        val r = ranker.rank(listOf(gas, area, parking, uTurn), day, local, hasInfant = false, speedKph = 30f, limit = 10)
            .associateBy { it.stop.id }
        approx((2.4 + 1.0) * 60, r.getValue("gas").totalTimeS, 1e-6)
        approx((2.4 + 2.0) * 60, r.getValue("area").totalTimeS, 1e-6)
        approx((2.4 + 3.0 + 2.0) * 60, r.getValue("parking").totalTimeS, 1e-6) // 140m ÷ 70m/분 = 2분
        approx((2.4 + 1.0 + 5.0) * 60, r.getValue("uturn").totalTimeS, 1e-6)
        assertTrue(r.getValue("uturn").why.any { it.code == "URGENT_UTURN" })
    }

    @Test
    fun excludesClosedNonParkableAndNoRestroom() {
        val r = ranker.rank(
            listOf(
                stop("closed", 300.0, StopKind.OTHER, openNow = false),
                stop("noPark", 300.0, StopKind.PUBLIC_RESTROOM, parkable = false),
                stop("noToilet", 300.0, StopKind.GAS_STATION, facilities = Facilities(restroom = false)),
                stop("noDistance", null, StopKind.GAS_STATION),
                stop("ok", 3_000.0, StopKind.GAS_STATION),
            ),
            day, local, false, 30f, 10,
        )
        assertEquals(listOf("ok"), r.map { it.stop.id })
    }

    @Test
    fun unknownOpenIsPenalizedUnlessOpen24h() {
        val known = stop("known", 1_000.0, StopKind.GAS_STATION, openNow = true)
        val unknown = stop("unknown", 900.0, StopKind.GAS_STATION, openNow = null)
        val always = stop("always", 950.0, StopKind.GAS_STATION, openNow = null, facilities = Facilities(open24h = true))
        val r = ranker.rank(listOf(known, unknown, always), day, local, false, 30f, 10).map { it.stop.id }
        assertEquals(listOf("always", "known", "unknown"), r)
    }

    @Test
    fun nightBonusForSafetyOnlyAtNight() {
        val plain = stop("plain", 1_000.0, StopKind.GAS_STATION)
        val safe = stop("safe", 1_500.0, StopKind.GAS_STATION, facilities = Facilities(emergencyBell = true, cctv = true))
        assertEquals("plain", ranker.rank(listOf(plain, safe), day, local, false, 30f, 10).first().stop.id)
        val atNight = ranker.rank(listOf(plain, safe), night, local, false, 30f, 10)
        assertEquals("safe", atNight.first().stop.id, "1.2분 더 걸려도 밤에는 비상벨·CCTV")
        assertTrue(atNight.first().why.any { it.code == "URGENT_SAFETY" })
        assertEquals("safe", ranker.rank(listOf(plain, safe), kst(2026, 11, 14, 5, 30), local, false, 30f, 10).first().stop.id)
    }

    @Test
    fun drowsyShelterCountsAsSafeAtNightEvenWithoutFlags() {
        val gas = stop("gas", 1_000.0, StopKind.GAS_STATION)
        val shelter = stop("shelter", 1_500.0, StopKind.DROWSY_SHELTER) // 비상벨·CCTV 표시 없음
        assertEquals("gas", ranker.rank(listOf(gas, shelter), day, local, false, 30f, 10).first().stop.id)
        val r = ranker.rank(listOf(gas, shelter), night, local, false, 30f, 10)
        assertEquals("shelter", r.first().stop.id, "고속도로 졸음쉼터는 지침상 비상벨·CCTV가 기본 시설")
        assertTrue(r.first().why.any { it.code == "URGENT_SAFETY" && it.text.contains("졸음쉼터") })
    }

    @Test
    fun infantPrefersDiaperTable() {
        val plain = stop("plain", 1_000.0, StopKind.GAS_STATION)
        val diaper = stop("diaper", 2_000.0, StopKind.GAS_STATION, facilities = Facilities(diaperTable = true))
        assertEquals("plain", ranker.rank(listOf(plain, diaper), day, local, false, 30f, 10).first().stop.id)
        val withInfant = ranker.rank(listOf(plain, diaper), day, local, true, 30f, 10)
        assertEquals("diaper", withInfant.first().stop.id)
        assertTrue(withInfant.first().why.any { it.code == "URGENT_DIAPER" })
    }

    @Test
    fun highwaySpeedUsesHighwayAverageAndEtaWins() {
        val a = stop("a", 8_000.0, StopKind.HIGHWAY_REST_AREA)
        val r = ranker.rank(listOf(a), day, local, false, 100f, 1).single()
        approx((6.0 + 2.0) * 60, r.totalTimeS, 1e-6) // 8km ÷ 80km/h = 6분
        val withEta = ranker.rank(listOf(a.copy(etaS = 240.0)), day, local, false, 100f, 1).single()
        approx((4.0 + 2.0) * 60, withEta.totalTimeS, 1e-6)
    }

    @Test
    fun limitAndDeterministicTieBreak() {
        val list = listOf(stop("b", 1_000.0, StopKind.GAS_STATION), stop("a", 1_000.0, StopKind.GAS_STATION), stop("c", 5_000.0, StopKind.GAS_STATION))
        val r = ranker.rank(list, day, local, false, 30f, 2)
        assertEquals(listOf("a", "b"), r.map { it.stop.id })
        assertTrue(ranker.rank(emptyList(), day, local, false, 30f, 2).isEmpty())
        assertTrue(ranker.rank(list, day, local, false, 30f, 0).isEmpty())
        val why = r.first().why
        assertEquals("URGENT_TIME", why.first().code)
        assertTrue(why.first().text.startsWith("약 3분"), why.first().text)
    }
}
