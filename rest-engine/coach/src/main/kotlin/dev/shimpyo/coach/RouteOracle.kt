package dev.shimpyo.coach

import dev.shimpyo.coach.CandidateTuning.DETOUR_RATIO
import dev.shimpyo.coach.CandidateTuning.DETOUR_SLACK_M
import dev.shimpyo.coach.CandidateTuning.ORACLE_BACKOFF_MAX_MS
import dev.shimpyo.coach.CandidateTuning.ORACLE_BACKOFF_MIN_MS
import dev.shimpyo.coach.CandidateTuning.ORACLE_FAILS_FOR_BACKOFF
import dev.shimpyo.coach.CandidateTuning.ORACLE_MAX_AGE_MS
import dev.shimpyo.coach.CandidateTuning.ORACLE_MOVE_M
import dev.shimpyo.coach.CandidateTuning.ORACLE_NO_ANSWER_MS
import dev.shimpyo.coach.CandidateTuning.ORACLE_REQUEST_TIMEOUT_MS
import dev.shimpyo.coach.CandidateTuning.ORACLE_RETRY_MS
import dev.shimpyo.coach.CandidateTuning.ORACLE_TURN_DEG
import dev.shimpyo.engine.StopCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 후보까지의 실제 경로 거리와 시간을 Pleos 내비(requestTrip)로 구한다.
 * 진행 방향 반대편 휴게소는 유턴을 해야 하므로 경로 거리가 직선거리보다 크게 나온다. 이걸로 방향을 판정한다.
 *
 * 내비 요청은 NaviKit 잠금을 잡아 그동안 경로·TBT 조회와 경유지 명령이 기다린다. 그래서
 * - 같은 후보는 5분이 지났거나 3km 넘게 움직였거나 방향이 뒤집혔을 때만 다시 묻는다 (캐시 나이는 실제 시간 [clock]).
 * - 실패한 후보는 1분 동안 다시 묻지 않고, 내비가 답하지 않거나 연달아 실패하면 모든 요청을 30초~5분 쉰다 (서비스 감사 P2).
 * - 요청 하나는 [ORACLE_REQUEST_TIMEOUT_MS]까지만 기다린다. 부른 쪽이 취소하면(갱신 예산·모드 전환) 그대로 취소되고 실패로 세지 않는다.
 *
 * 시각: 예전에는 코치 시각(now)으로 캐시 나이를 쟀다. 시연에서는 가상 시각이라 시연을 끈 뒤 몇 시간씩 어긋났다. 지금은 [ElapsedClock].
 * 스레드: 코치 디스패처 하나에서 부르는 것을 기본으로 하되, 지금 CoachService가 여러 스레드에서 부르므로 상태는 [lock]으로 지킨다
 * (잠금 안에서 일시 중단하지 않는다).
 */
class RouteOracle(
    private val navi: NaviPort,
    private val clock: ElapsedClock = ElapsedClock { System.nanoTime() / 1_000_000L },
    private val log: CoachLog = CoachLog.NONE,
) : TripEstimator {
    private data class Entry(val distanceM: Double, val etaS: Double, val atMs: Long, val from: Fix)

    private val lock = Any()
    private val cache = HashMap<String, Entry>()
    /** 후보 id → 다시 물어도 되는 시각 */
    private val retryAt = HashMap<String, Long>()
    /** 이 시각까지 모든 요청을 쉰다 */
    private var pausedUntil = Long.MIN_VALUE
    private var backoffMs = ORACLE_BACKOFF_MIN_MS
    private var failsInARow = 0

    @Volatile override var enabled = true

    override suspend fun refine(c: StopCandidate, from: Fix): StopCandidate? {
        if (!enabled) return null
        val straight = Geo.distanceM(from.lat, from.lon, c.lat, c.lon)
        val entry = fresh(c.id, from, clock.elapsedMs()) ?: ask(c, from) ?: return null
        return applyEntry(c, entry, from, straight)
    }

    /**
     * 예전 진입점 (지금 CoachService가 쓴다). 실패하면 직선거리만 채운 후보를 돌려준다.
     * 코치 액터가 CandidateProvider로 바뀌면 지운다.
     */
    @Deprecated("CandidateProvider가 refine(c, from)을 쓴다", ReplaceWith("refine(c, Fix(lat, lon, null))"))
    suspend fun refine(c: StopCandidate, lat: Double, lon: Double, @Suppress("UNUSED_PARAMETER") now: Long): StopCandidate =
        refine(c, Fix(lat, lon, null)) ?: c.copy(straightDistanceM = c.straightDistanceM ?: Geo.distanceM(lat, lon, c.lat, c.lon))

    override fun clear() = synchronized(lock) {
        cache.clear()
        retryAt.clear()
        pausedUntil = Long.MIN_VALUE
        backoffMs = ORACLE_BACKOFF_MIN_MS
        failsInARow = 0
    }

    /** 다시 묻지 않아도 되는 캐시 값 */
    private fun fresh(id: String, from: Fix, now: Long): Entry? = synchronized(lock) {
        val e = cache[id] ?: return null
        val turned = from.heading != null && e.from.heading != null &&
            Geo.angleDiff(from.heading.toDouble(), e.from.heading.toDouble()) > ORACLE_TURN_DEG
        e.takeIf { now - it.atMs < ORACLE_MAX_AGE_MS && Geo.distanceM(from.lat, from.lon, it.from.lat, it.from.lon) < ORACLE_MOVE_M && !turned }
    }

    /** 내비에 묻는다. 쉬는 중이거나 실패하면 null */
    private suspend fun ask(c: StopCandidate, from: Fix): Entry? {
        val startedAt = clock.elapsedMs()
        synchronized(lock) {
            if (startedAt < pausedUntil || startedAt < (retryAt[c.id] ?: Long.MIN_VALUE)) return null
        }
        val answer = try {
            withTimeoutOrNull(ORACLE_REQUEST_TIMEOUT_MS) { navi.tripEstimate(c.lat, c.lon, c.name, ORACLE_REQUEST_TIMEOUT_MS) }
        } catch (e: CancellationException) {
            // 부른 쪽이 취소했으면 그대로 던진다. 아니면 포트 안쪽의 시간 초과(TimeoutCancellationException)라 실패로 본다
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            null
        }
        val now = clock.elapsedMs()
        if (answer == null || !answer.distanceM.isFinite() || answer.distanceM < 0.0 || !answer.etaS.isFinite()) {
            failed(c, noAnswer = now - startedAt >= ORACLE_NO_ANSWER_MS, now)
            return null
        }
        return Entry(answer.distanceM, answer.etaS, now, from).also { e ->
            synchronized(lock) {
                cache[c.id] = e
                retryAt.remove(c.id)
                failsInARow = 0
                backoffMs = ORACLE_BACKOFF_MIN_MS
            }
        }
    }

    private fun failed(c: StopCandidate, noAnswer: Boolean, now: Long) {
        val pause = synchronized(lock) {
            retryAt[c.id] = now + ORACLE_RETRY_MS
            failsInARow++
            if (!noAnswer && failsInARow < ORACLE_FAILS_FOR_BACKOFF) return@synchronized null
            pausedUntil = now + backoffMs
            failsInARow = 0
            backoffMs.also { backoffMs = (backoffMs * 2).coerceAtMost(ORACLE_BACKOFF_MAX_MS) }
        }
        if (pause != null) log.w("requestTrip ${if (noAnswer) "no answer" else "failing"} (${c.name}): pause ${pause / 1000}s")
    }

    /** 캐시 값을 지금 위치에 맞춘다: 캐시 뒤 움직인 만큼 뺀다 (진행 방향으로 움직였다고 가정, 방향이 뒤집히면 캐시를 안 쓴다) */
    private fun applyEntry(c: StopCandidate, e: Entry, from: Fix, straight: Double): StopCandidate {
        val moved = Geo.distanceM(from.lat, from.lon, e.from.lat, e.from.lon)
        val dist = (e.distanceM - moved).coerceAtLeast(0.0)
        val eta = if (e.distanceM > 1.0) e.etaS * dist / e.distanceM else e.etaS
        return c.copy(
            straightDistanceM = straight,
            routeDistanceM = dist,
            etaS = eta,
            directionOk = dist <= straight * DETOUR_RATIO + DETOUR_SLACK_M,
        )
    }
}
