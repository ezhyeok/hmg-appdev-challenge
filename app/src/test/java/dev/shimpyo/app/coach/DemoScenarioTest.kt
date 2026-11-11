package dev.shimpyo.app.coach

import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.TripProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시연 시나리오를 서비스와 같은 방식으로 엔진에 넣어 본다 (DemoDirector.apply + candidates → CoachEngine.update).
 * 회귀: 2026-11-11 에뮬레이터에서 귀성길 시연이 '정체 전 마지막 화장실'을 묻지 않았다. 시연 후보에 평균 속도로 만든
 * etaS가 들어갔고, 직선으로 이은 시연 경로가 실제보다 짧아 죽암휴게소가 정체 구간 안에 들어갔기 때문이다.
 */
class DemoScenarioTest {

    private fun restArea(id: String, name: String, lat: Double, lon: Double, kind: StopKind = StopKind.HIGHWAY_REST_AREA) =
        StopCandidate(id = id, name = name, kind = kind, lat = lat, lon = lon, facilities = Facilities(food = kind == StopKind.HIGHWAY_REST_AREA), parkable = true)

    // 앱에 든 공공데이터의 부산방향 시설 좌표 (StopAssets와 같은 값)
    private val oksan = restArea("oksan", "옥산휴게소(부산방향)", 36.657743, 127.369817)
    private val jukam = restArea("jukam", "죽암휴게소(부산방향)", 36.486810, 127.429261)
    private val sintanjin = restArea("sintanjin", "신탄진졸음쉼터", 36.425354, 127.416697, StopKind.DROWSY_SHELTER)

    @Test
    fun holidayDemoAsksOksanAsTheLastChanceBeforeTheJam() {
        var real = 1_700_000_000_000L
        val director = DemoDirector { real }
        val start = checkNotNull(director.start("holiday"))
        val t0 = director.now()
        val restroomAt = t0 - start.lastRestroomMin * 60_000L
        val engine = CoachEngine(
            profile = TripProfile(start.occupants, tripStart = t0 - start.drivenMin * 60_000L, lastRestroomAt = start.occupants.associate { it.id to restroomAt }),
            memory = PersonalMemory(),
        )
        val oksanAt = checkNotNull(director.alongForTest(DemoDirector.Pt(oksan.lat, oksan.lon))) { "옥산이 시연 경로 위에 있어야 한다" }
        val candidates = listOf(oksan, jukam, sintanjin)

        // 정체는 죽암휴게소 앞에서 끝난다 (정체 안에 화장실이 있으면 마지막 기회 조건이 깨진다)
        val firstInput = director.apply(CoachInput(now = director.now(), gear = Gear.DRIVE, speedKph = 100f, candidates = director.candidates(candidates)))
        val jam = firstInput.route!!.congestion.single()
        val jukamAhead = director.candidates(candidates).first { it.id == "jukam" }.routeDistanceM!!
        assertTrue("죽암 ${jukamAhead}m는 정체 끝 ${jam.startDistanceM + jam.lengthM}m 뒤", jukamAhead > jam.startDistanceM + jam.lengthM)
        assertTrue("시연 후보에는 지어낸 etaS가 없다", director.candidates(candidates).all { it.etaS == null })

        var askedAtM: Double? = null
        var recommendedAtM: Double? = null
        while (askedAtM == null) {
            val travelled = director.travelledForTest()!!
            val toOksan = oksanAt - travelled
            if (toOksan < 1_000.0) break
            val out = engine.update(
                director.apply(CoachInput(now = director.now(), gear = Gear.DRIVE, speedKph = 100f, candidates = director.candidates(candidates))),
            )
            if (recommendedAtM == null && out.next?.stop?.id == "oksan" && out.next!!.reasons.any { it.code.startsWith("LAST_CHANCE") }) {
                recommendedAtM = toOksan
                assertNotNull("추천에 엔진이 계산한 도착 시간", out.next!!.stop.etaS)
            }
            if (out.prompt?.kind == PromptKind.LAST_CHANCE) {
                assertEquals("oksan", out.prompt!!.stop?.id)
                assertNotNull("질문 카드에도 엔진이 계산한 도착 시간", out.prompt!!.stop?.etaS)
                askedAtM = toOksan
            }
            real += 1_000L // 서비스 루프처럼 1초마다
        }
        assertNotNull("옥산휴게소 앞에서 마지막 기회를 추천해야 한다", recommendedAtM)
        val asked = checkNotNull(askedAtM) { "옥산휴게소 진입로 2~5km 전에 물어야 한다" }
        assertTrue("진입로까지 ${asked}m에서 물었다", asked in 2_000.0..5_000.0)
    }

    @Test
    fun demoOffLeavesInputsUntouched() {
        val director = DemoDirector { 0L }
        assertNull(director.start(null))
        val input = CoachInput(now = 5L, gear = Gear.DRIVE, candidates = listOf(oksan))
        assertEquals(input, director.apply(input))
        assertEquals(listOf(oksan), director.candidates(listOf(oksan)))
    }
}
