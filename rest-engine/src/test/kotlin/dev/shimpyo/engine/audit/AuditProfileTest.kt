package dev.shimpyo.engine.audit

import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.MIN
import dev.shimpyo.engine.SEC
import dev.shimpyo.engine.TripProfile
import dev.shimpyo.engine.adult
import dev.shimpyo.engine.kst
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 프로필·시간 입력: 출발 뒤 정정, 미래 시각 */
class AuditProfileTest {
    private val start = kst(2026, 11, 11, 10, 0)

    @Test
    fun correctingTheLastRestroomEarlierAfterStartLowersTheGauge() {
        // 회귀(감사 F1): 출발 뒤 '출발 전에 화장실 다녀오셨어요?'에 '아니'라고 답해 더 이른 시각을 넣어도 앞으로만 옮겨서 무시했다
        val profile = TripProfile(listOf(adult("driver")), tripStart = start)
        val e = CoachEngine(profile = profile, random = Random(7))
        val before = assertNotNull(e.update(CoachInput(now = start, gear = Gear.PARK, speedKph = 0f)).restroom.minutes)
        e.setProfile(profile.copy(lastRestroomAt = mapOf("driver" to start - 60 * MIN)))
        val after = assertNotNull(e.update(CoachInput(now = start + 5 * SEC, gear = Gear.PARK, speedKph = 0f)).restroom.minutes)
        assertTrue(after <= before - 55, "60분 전으로 정정했으면 게이지가 줄어야: $before → $after")
    }

    @Test
    fun reapplyingTheOriginalProfileDoesNotUndoADetectedStop() {
        // 지금 맞다: 정차로 시계를 되돌린 뒤 같은 프로필을 다시 넣어도 되돌아가지 않는다 (위 결함을 고칠 때 지켜야 할 것)
        val profile = TripProfile(listOf(adult("driver")), tripStart = start - 100 * MIN, lastRestroomAt = mapOf("driver" to start - 100 * MIN))
        val e = CoachEngine(profile = profile, random = Random(7))
        var t = start
        e.update(CoachInput(now = t, gear = Gear.DRIVE, speedKph = 90f))
        t += SEC
        e.update(CoachInput(now = t, gear = Gear.PARK, speedKph = 0f, doorOpened = true))
        repeat(5) { t += MIN; e.update(CoachInput(now = t, gear = Gear.PARK, speedKph = 0f)) }
        val afterStop = assertNotNull(e.update(CoachInput(now = t, gear = Gear.DRIVE, speedKph = 30f)).restroom.minutes)
        e.setProfile(profile)
        val reapplied = assertNotNull(e.update(CoachInput(now = t + SEC, gear = Gear.DRIVE, speedKph = 30f)).restroom.minutes)
        // 103분 간격이 개인 기록으로 들어가 문턱이 150분보다 조금 줄어든다(약 137분). 정차 전에는 약 50분이었다
        assertTrue(afterStop >= 120, "정차로 회복: $afterStop")
        assertTrue(reapplied >= afterStop - 1, "$afterStop → $reapplied")
    }

    @Test
    fun aWakeTimeInTheFutureIsNotTakenAsJustWokeUp() {
        // 회귀(감사 T4): 미래 기상 시각을 '방금 일어남'으로 봐서, 새벽 3시 출발에 오늘 날짜 '7시'를 넣으면 새벽 졸림 곡선이 꺼졌다
        val t = kst(2026, 11, 12, 3, 0)
        fun level(wake: Long?) = CoachEngine(profile = TripProfile(listOf(adult("driver")), tripStart = t, driverWakeAt = wake), random = Random(7))
            .update(CoachInput(now = t, gear = Gear.DRIVE, speedKph = 90f)).fatigue.level
        assertEquals(level(null), level(kst(2026, 11, 12, 7, 0)))
    }
}
