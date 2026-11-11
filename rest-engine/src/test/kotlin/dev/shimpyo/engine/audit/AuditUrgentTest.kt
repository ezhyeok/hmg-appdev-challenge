package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachConfig
import dev.shimpyo.engine.CoachTexts
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.LocalClock
import dev.shimpyo.engine.SEOUL
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.UrgentRanker
import dev.shimpyo.engine.kst
import dev.shimpyo.engine.stop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UrgentRanker: 닫힘·영업 모름, 야간, 영아, 유턴, 지나친 곳. 모두 지금 맞게 동작하는 경계를 고정한다 */
class AuditUrgentTest {
    private val cfg = CoachConfig()
    private val ranker = UrgentRanker(cfg, CoachTexts())
    private val local = LocalClock(SEOUL)
    private val night = kst(2026, 11, 11, 23, 0)
    private val noon = kst(2026, 11, 11, 12, 0)

    @Test
    fun closedIsExcludedEvenIfFlaggedOpen24h() {
        val r = ranker.rank(listOf(stop("closed24", 500.0, kind = StopKind.PUBLIC_RESTROOM, openNow = false, facilities = Facilities(open24h = true))), night, local, false, 30f, 5)
        assertTrue(r.isEmpty())
    }

    @Test
    fun passedOrDistanceLessCandidatesAreExcluded() {
        val r = ranker.rank(listOf(stop("behind", -300.0), stop("noDistance", null, etaS = 60.0)), noon, local, false, 30f, 5)
        assertTrue(r.isEmpty(), r.map { it.stop.id }.toString())
    }

    @Test
    fun atNightAShelterBeatsAnUnknownHoursRestroomAtTheSameDistance() {
        val r = ranker.rank(
            listOf(
                stop("wc", 2_000.0, kind = StopKind.PUBLIC_RESTROOM, openNow = null),
                stop("shelter", 2_000.0, kind = StopKind.DROWSY_SHELTER, openNow = null),
            ),
            night, local, false, 30f, 5,
        )
        assertEquals("shelter", r.first().stop.id)
    }

    @Test
    fun infantAtNightStacksDiaperAndSafetyBonuses() {
        val r = ranker.rank(
            listOf(
                stop("plain", 1_000.0, kind = StopKind.PUBLIC_RESTROOM, facilities = Facilities(open24h = true)),
                stop("care", 2_500.0, kind = StopKind.PUBLIC_RESTROOM, facilities = Facilities(open24h = true, diaperTable = true, emergencyBell = true)),
            ),
            night, local, true, 30f, 5,
        )
        // 1.5km 더 멀어도(도시 25km/h로 약 3.6분) 교환대 3 + 야간 안전 2 = 5분 가점이 이긴다
        assertEquals("care", r.first().stop.id)
        assertTrue(r.first().why.any { it.code == "URGENT_DIAPER" } && r.first().why.any { it.code == "URGENT_SAFETY" })
    }

    @Test
    fun uTurnCostsFiveMinutesAndUnknownDirectionOne() {
        val r = ranker.rank(
            listOf(stop("ahead", 1_000.0, directionOk = true), stop("unknown", 1_000.0, directionOk = null), stop("uturn", 1_000.0, directionOk = false)),
            noon, local, false, 30f, 5,
        )
        assertEquals(listOf("ahead", "unknown", "uturn"), r.map { it.stop.id })
        val t = r.associate { it.stop.id to it.totalTimeS / 60.0 }
        approx(t.getValue("ahead") + 1.0, t.getValue("unknown"), 1e-6)
        approx(t.getValue("ahead") + 5.0, t.getValue("uturn"), 1e-6)
    }
}
