package dev.shimpyo.coach

import dev.shimpyo.coach.voice.Hangul
import dev.shimpyo.engine.StopCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 들르기(질문 수락·급해 목록·계획 화면·다시 시도)를 어떻게 실행할지 정하는 순수 규칙.
 *
 * - 경유지 추가와 새 길안내는 명령 바로 전에 다시 읽은 내비 모드로 고른다. 내비 스냅숏은 최대 15초 묵어서, 그 사이 사용자가
 *   길안내를 시작했으면 새 길안내가 사용자의 목적지를 덮어쓴다(구조 리뷰 D12, 서비스 감사 D9). 모드를 모르면 보내지 않는다.
 * - 좌표 없는 경로 위 시설(tbt: id, TBT에서 이름으로만 찾은 휴게소)은 내비 경로가 이미 지나가므로 주행 중에는 명령 없이
 *   '경로 위에 있어요'로 안내한다. 내비 장소 검색 화면은 정차 중에만 연다(주행 중 화면 조작 유도 금지, UI 감사 #3).
 * - 다시 시도는 한 번, 보내지 못한 명령(예외)과 모드 조회 실패만. 보냈는데 확인이 안 된 명령(false)은 늦게 반영됐을 수 있어
 *   다시 보내지 않는다(경유지가 두 번 들어가거나 경로가 다시 시작된다). 그때는 FAILED를 내고 화면의 다시 시도 버튼에 맡긴다.
 */
object GoToPlanner {
    enum class Action { ADD_WAYPOINT, START_ROUTE, ON_ROUTE, OPEN_SEARCH }

    enum class Failure {
        /** 내비 모드를 읽지 못했거나 모르는 값이다 */
        MODE_UNKNOWN,
        /** 명령을 보내지 못했다 (예외, 검색 화면을 못 열었다) */
        NOT_SENT,
        /** 보냈지만 확인 시간 안에 반영되지 않았다 */
        UNCONFIRMED,
        /** 경로 위 시설인데 길안내가 끝나 있다 */
        NO_ROUTE,
    }

    const val TBT_PREFIX = "tbt:"
    const val MAX_ATTEMPTS = 2
    /** 다시 시도 전 간격. 내비 명령 사이 최소 간격(NaviKit.COMMAND_GAP_MS)과 같다 */
    const val RETRY_DELAY_MS = 3_000L
    /**
     * 실행 전체 상한. 모드 조회(최대 15초) + 길안내 확인(최대 45초)을 두 번 해도 넘지 않게 잡았다.
     * 넘으면 FAILED. 코어는 이 안에 결과(GoToFinished)를 반드시 받는다.
     */
    const val MAX_TOTAL_MS = 130_000L

    fun coordinateless(stop: StopCandidate): Boolean = stop.id.startsWith(TBT_PREFIX)

    /** 정차 중 좌표 없는 곳은 검색만 열면 되니 모드를 읽지 않는다 */
    fun needsMode(stop: StopCandidate, parked: Boolean): Boolean = !(coordinateless(stop) && parked)

    /**
     * 할 일. [freshMode]: 명령 직전에 읽은 내비 모드(읽지 못했으면 null). null을 돌려주면 이번 시도는 [failureWithoutAction].
     */
    fun action(stop: StopCandidate, freshMode: NaviMode?, parked: Boolean): Action? {
        if (coordinateless(stop)) {
            return when {
                parked -> Action.OPEN_SEARCH
                // 모드를 못 읽어도 이 후보는 지금 경로의 TBT에서 왔다. 명령을 보내지 않으니 해가 없다
                freshMode == null || freshMode == NaviMode.UNKNOWN || freshMode.hasRoute -> Action.ON_ROUTE
                else -> null
            }
        }
        return when {
            freshMode == null || freshMode == NaviMode.UNKNOWN -> null
            freshMode.hasRoute -> Action.ADD_WAYPOINT
            else -> Action.START_ROUTE
        }
    }

    /** [action]이 null일 때의 실패 이유 */
    fun failureWithoutAction(stop: StopCandidate): Failure = if (coordinateless(stop)) Failure.NO_ROUTE else Failure.MODE_UNKNOWN

    fun retry(failure: Failure, attempt: Int): Boolean =
        attempt < MAX_ATTEMPTS && (failure == Failure.MODE_UNKNOWN || failure == Failure.NOT_SENT)

    fun outcome(action: Action): GoToOutcome = when (action) {
        Action.ADD_WAYPOINT -> GoToOutcome.WAYPOINT_ADDED
        Action.START_ROUTE -> GoToOutcome.ROUTE_STARTED
        Action.ON_ROUTE -> GoToOutcome.ON_ROUTE
        Action.OPEN_SEARCH -> GoToOutcome.SEARCH_OPENED
    }

    /** 결과를 알리는 문장 */
    fun speech(outcome: GoToOutcome, stop: StopCandidate): String = when (outcome) {
        GoToOutcome.WAYPOINT_ADDED -> "${Hangul.eulReul(stop.name)} 경유지로 넣었어요"
        GoToOutcome.ROUTE_STARTED -> "${Hangul.euRo(stop.name)} 안내할게요"
        GoToOutcome.ON_ROUTE -> {
            val ahead = distanceText(stop.routeDistanceM ?: stop.straightDistanceM)?.let { "$it 앞 " } ?: ""
            "${eunNeun(stop.name)} 경로 위에 있어요, ${ahead}진입로로 들어가세요"
        }
        GoToOutcome.SEARCH_OPENED -> "내비에서 ${Hangul.eulReul(stop.name)} 찾아 두었어요. 화면에서 골라 주세요"
        GoToOutcome.FAILED -> "내비에 넣지 못했어요. 화면에서 다시 눌러 주세요"
    }

    /** 1km 미만은 100m 단위, 10km 미만은 소수 한 자리, 그 위는 정수 km */
    private fun distanceText(m: Double?): String? {
        if (m == null || m.isNaN() || m <= 0.0) return null
        if (m < 1_000.0) return "${((m / 100.0).roundToInt() * 100).coerceAtLeast(100)}m"
        val km = m / 1_000.0
        if (km >= 10.0) return "${km.roundToInt()}km"
        val tenths = (km * 10.0).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}km" else "${tenths / 10}.${tenths % 10}km"
    }

    // Hangul(voice/)에 없는 은/는. 공용 파일을 건드리지 않으려고 여기 둔다
    private fun eunNeun(word: String) = word + if (Hangul.hasBatchim(word)) "은" else "는"
}

/**
 * 들르기 실행기 (효과 실행). 코어가 PromptEffect.StartGoTo를 받으면 이것을 launch하고, 돌려받은 결과를
 * PromptEvent.GoToFinished로 우편함에 넣는다. 판단은 [GoToPlanner]가 하고 여기는 내비 호출과 다시 시도만 한다.
 * 결과는 [GoToPlanner.MAX_TOTAL_MS] 안에 반드시 나온다. 취소(세계 전환)는 결과로 바꾸지 않고 그대로 전한다.
 */
class GoToRunner(
    private val navi: NaviPort,
    private val log: CoachLog = CoachLog.NONE,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run(stop: StopCandidate, parked: Boolean): GoToOutcome =
        withTimeoutOrNull(GoToPlanner.MAX_TOTAL_MS) { attempts(stop, parked) }
            ?: GoToOutcome.FAILED.also { log.w("goTo ${stop.name}: no result in ${GoToPlanner.MAX_TOTAL_MS} ms") }

    private suspend fun attempts(stop: StopCandidate, parked: Boolean): GoToOutcome {
        var attempt = 1
        while (true) {
            val failure = when (val r = once(stop, parked)) {
                is Attempt.Done -> return r.outcome
                is Attempt.Failed -> r.failure
            }
            log.w("goTo ${stop.name}: attempt $attempt failed ($failure)")
            if (!GoToPlanner.retry(failure, attempt)) return GoToOutcome.FAILED
            attempt++
            pause(GoToPlanner.RETRY_DELAY_MS)
        }
    }

    private sealed interface Attempt {
        data class Done(val outcome: GoToOutcome) : Attempt
        data class Failed(val failure: GoToPlanner.Failure) : Attempt
    }

    private suspend fun once(stop: StopCandidate, parked: Boolean): Attempt {
        val mode = if (GoToPlanner.needsMode(stop, parked)) guarded { navi.routeStatus().mode } else null
        val action = GoToPlanner.action(stop, mode, parked) ?: return Attempt.Failed(GoToPlanner.failureWithoutAction(stop))
        val sent: Boolean? = when (action) {
            GoToPlanner.Action.ON_ROUTE -> true
            GoToPlanner.Action.ADD_WAYPOINT -> guarded { navi.addWaypoint(stop.lat, stop.lon, stop.name) }
            GoToPlanner.Action.START_ROUTE -> guarded { navi.startRoute(stop.lat, stop.lon, stop.name) }
            // 검색은 '열었다/못 열었다'만 알 수 있다. 못 열었으면 보내지 못한 것과 같다
            GoToPlanner.Action.OPEN_SEARCH -> guarded { navi.showPlaceSearch(stop.name) }?.takeIf { it }
        }
        return when (sent) {
            true -> Attempt.Done(GoToPlanner.outcome(action))
            null -> Attempt.Failed(GoToPlanner.Failure.NOT_SENT)
            false -> Attempt.Failed(GoToPlanner.Failure.UNCONFIRMED)
        }
    }

    /** 내비 예외는 null로 (취소는 그대로 던진다) */
    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w("navi: ${e.message}")
        null
    }
}
