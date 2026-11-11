package dev.shimpyo.coach

import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.Facilities
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.VoiceIntent
import dev.shimpyo.engine.RouteState
import dev.shimpyo.engine.StopCandidate
import dev.shimpyo.engine.StopKind
import dev.shimpyo.engine.TripProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 실제 내비 정체 목록(2026-11-12 에뮬레이터, 판교 출발 길안내, `congestion items:` 로그 그대로)을 포트 경계 → CongestionBasis → 엔진까지
 * 넣어 20km 앞 휴게소의 엔진 도착 예상이 정체 시간의 뜻에 따라 어떻게 달라지는지 본다. 항목 35개가 모두 '60초'(분 단위 늘어난 시간)라
 * 예전처럼 그것을 지나는 시간으로 넣으면 20km 안의 짧은 정체 8곳(합계 640m)이 도착 예상을 약 6분 늦췄다(1,203초 → 858초).
 */
class CongestionArrivalTest {

    /** offset/길이/늘어난 시간/평균 속도 */
    private val measured = "400/100/60/38.3 600/40/60/35.5 1600/60/60/13.7 1600/30/60/61.3 2000/40/60/51.8 2000/100/60/54.1 2400/200/60/35.8 " +
        "2600/70/60/10.1 47000/100/60/9.0 47000/100/60/9.1 97000/40/60/81.9 98000/100/60/12.2 98000/100/60/51.9 98000/40/60/25.5 " +
        "98000/80/60/52.0 98000/100/60/27.1 98000/70/60/25.9 98000/100/60/15.8 98000/100/60/39.0 99000/100/60/26.7 99000/10/60/70.9 " +
        "99000/100/60/20.3 99000/100/60/27.5 99000/100/60/16.4 99000/100/60/60.4 100000/50/60/30.9 100000/200/60/38.5 100000/80/60/19.4 " +
        "100000/70/60/67.5 460000/1700/60/109.6 473000/30/60/13.6 473000/100/60/16.2 474000/600/60/46.5 474000/600/60/37.0 475000/300/60/30.0"

    private fun items(old: Boolean): List<NaviCongestion> = measured.split(' ').map { it.split('/').map(String::toDouble) }.mapNotNull { (off, len, delay, avr) ->
        if (old) NaviCongestion(off, len, delay, "HEAVY", "", "", "") // 예전: trafficCongestionTime을 그대로 지나는 시간으로
        else NaviCongestion.fromNavi(off, len, delay, avr, "HEAVY", "", "", "")
    }

    private val giheung = StopCandidate(
        "giheung", "기흥휴게소", StopKind.HIGHWAY_REST_AREA, 37.235, 127.1046, Facilities(food = true), parkable = true,
        routeDistanceM = 20_000.0, directionOk = true,
    )

    /**
     * 엔진의 도착 예상 (초): "화장실 가고 싶어"에 엔진이 답하는 질문의 장소에 엔진이 채우는 도착 시간(withEta → RouteContext.arrivalOf).
     * 급해 순위(UrgentRanker)는 정체를 보지 않으므로 이 경로로 잰다
     */
    private fun arrivalS(old: Boolean): Double {
        val segments = CongestionBasis().update(items(old), remainM = 383_000.0, odometerM = 0.0)
        val now = 1_794_417_158_000L
        val engine = CoachEngine(profile = TripProfile(listOf(Occupant("driver", OccupantType.ADULT)), tripStart = now - 30 * 60_000L), random = Random(42))
        val input = CoachInput(
            now = now, gear = Gear.DRIVE, speedKph = 90f,
            route = RouteState(guiding = true, remainDistanceM = 383_000.0, congestion = segments),
            candidates = listOf(giheung),
            intent = VoiceIntent.OccupantNeed(null, Purpose.RESTROOM),
        )
        val out = engine.update(input)
        val stop = listOfNotNull(out.prompt?.stop, out.next?.stop).first { it.id == "giheung" }
        return checkNotNull(stop.etaS) { "엔진이 도착 시간을 채운다" }
    }

    @Test
    fun theMeasuredShortJamsNoLongerAddMinutesToTheArrival() {
        val before = arrivalS(old = true)
        val after = arrivalS(old = false)
        println("기흥휴게소 20km, 90km/h: 예전 ${"%.0f".format(before)}초, 지금 ${"%.0f".format(after)}초")
        // 20km 안의 정체 8곳: 길이 640m, 평균 속도로 지나는 시간 합 약 86초. 예전에는 항목마다 60초 (엔진은 겹친 곳을 한 번만 센다)
        val crossingIn20km = items(old = false).filter { it.offsetM < 20_000.0 }.sumOf { it.durationS }
        assertEquals(86.0, crossingIn20km, 3.0)
        // 자유 흐름 20km ÷ 90km/h = 800초. 지금은 그 640m만큼만 느리다 (2026-11-12 측정값: 예전 1,203초 → 지금 858초)
        assertTrue("지금 ${after}초: 자유 흐름 800초 + 짧은 정체 1분 안팎", after in 820.0..900.0)
        assertTrue("예전 ${before}초: 짧은 정체들이 5분 넘게 더했다", before - after > 300.0)
    }
}
