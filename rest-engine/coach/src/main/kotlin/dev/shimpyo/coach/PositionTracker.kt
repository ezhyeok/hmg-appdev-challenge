package dev.shimpyo.coach

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

/**
 * 차의 실제 위치 하나를 갖는다: FusedLocation이 기본이고, 그 갱신이 끊기면 내비가 보는 위치로 대신한다.
 *
 * - 시연 위치는 여기 쓰지 않는다. CoachWorld가 덧씌운다. 그래서 시연 중에도 실제 위치가 계속 들어오고, 시연을 끄면
 *   곧바로 마지막 실제 위치로 돌아간다 (예전에는 시연 중 실제 위치를 버리고 시연 위치를 그대로 남겼다, 구조 리뷰 D3).
 * - 신선도는 실제 시간([ElapsedClock])으로 잰다. 예전에는 시연 가상 시각으로 재서 시연을 끈 뒤 내비 대체가 몇 시간씩 막혔다.
 * - 서 있는 차의 위치는 오래돼도 맞다. FusedLocation은 차가 서 있으면 새 값을 잘 보내지 않는데(에뮬레이터), 예전에는
 *   그것을 끊긴 것으로 보고 멀쩡한 위치를 내비 위치(에뮬레이터는 판교 고정)로 덮었다 (서비스 감사 P6).
 *   그래서 '움직이는 동안 15초 넘게 새 위치가 없을 때'만 끊긴 것으로 본다.
 * - 방위 0.0은 '모름'일 수 있다. 내비 CurrentLocationInfo.heading의 기본값이 0.0이고, 위치 SDK도 서 있으면 0을 줄 수 있다.
 *   북쪽으로 보면 진행 방향 앞쪽 후보가 뒤집힌다(데이터 감사 #10). 모르면 같은 자리면 직전 방위를, 움직였으면 두 위치의 방위를 쓴다.
 *
 * [moving]: 차가 움직이는지 (보통 [isMoving]에 차속·기어를 넣는다). 메서드는 어느 스레드에서 불러도 된다.
 */
class PositionTracker(private val clock: ElapsedClock, private val moving: () -> Boolean) {

    private val state = MutableStateFlow<Located?>(null)
    /** 마지막 실제 위치. 아직 없으면 null */
    val current: StateFlow<Located?> = state.asStateFlow()

    /** 움직이기 시작한 때 (서 있으면 null). 오래 서 있다 출발하자마자 끊긴 것으로 보지 않게 여기서부터 잰다 */
    private var movingSince: Long? = null

    /** FusedLocation 갱신 */
    @Synchronized
    fun onFused(fix: Fix) {
        if (!fix.valid()) return
        val now = clock.elapsedMs()
        val prev = state.value
        val raw = fix.heading?.takeIf { it.isFinite() }
        // 서 있을 때의 0.0은 방위가 없다는 뜻일 수 있다. 움직일 때는 위치 SDK가 이동으로 방위를 잰다
        val known = raw?.takeIf { it != 0f || moving() }
        state.value = Located(fix.copy(heading = known?.let(::normalize) ?: unknownHeading(prev, fix, now)), now, Located.Source.FUSED)
    }

    /**
     * 내비가 보는 위치 (위치 SDK가 끊겼을 때 NaviTracker가 묻는다). [requestedAtMs]: 내비에 물은 때.
     * 묻고 답을 받는 사이에 위치 SDK가 새 값을 줬으면 그쪽이 더 새것이라 버린다.
     */
    @Synchronized
    fun onNavi(fix: Fix, requestedAtMs: Long) {
        if (!fix.valid()) return
        val prev = state.value
        if (prev != null && prev.source == Located.Source.FUSED && prev.atElapsedMs >= requestedAtMs) return
        val now = clock.elapsedMs()
        // 내비 heading은 기본값이 0.0이라 0.0은 모르는 것으로 본다
        val known = fix.heading?.takeIf { it.isFinite() && it != 0f }
        state.value = Located(fix.copy(heading = known?.let(::normalize) ?: unknownHeading(prev, fix, now)), now, Located.Source.NAVI)
    }

    /**
     * 내비 위치로 대신해야 하는지: 위치가 아직 없거나, 움직이는 동안 [STALE_MS] 넘게 새 위치가 없을 때.
     * 서 있으면 마지막 위치가 그대로 맞으니 묻지 않는다 (서비스 감사 P6). 내비 조회 주기(5초 이하)마다 부른다.
     */
    @Synchronized
    fun needsNaviFallback(): Boolean {
        val now = clock.elapsedMs()
        val isMoving = moving()
        movingSince = if (isMoving) movingSince ?: now else null
        val cur = state.value ?: return true
        val since = movingSince ?: return false
        return now - max(cur.atElapsedMs, since) > STALE_MS
    }

    /** NaviTracker에 넘길 내비 위치 대체. [allowed]: CoachWorld 정책 (시연에서는 false: 시연 위치를 덧씌우므로 묻지 않는다) */
    fun naviFallback(allowed: () -> Boolean): LocationFallback = object : LocationFallback {
        override fun wanted(): Boolean = allowed() && needsNaviFallback()
        override fun onFix(fix: Fix, requestedAtMs: Long) = onNavi(fix, requestedAtMs)
    }

    /** 방위를 모를 때: 충분히 움직였으면 직전 위치에서의 방위, 같은 자리면 직전 방위, 아니면 모름 */
    private fun unknownHeading(prev: Located?, fix: Fix, now: Long): Float? {
        prev ?: return null
        val d = Geo.distanceM(prev.fix.lat, prev.fix.lon, fix.lat, fix.lon)
        if (d <= SAME_PLACE_M) return prev.fix.heading
        if (d >= BEARING_MIN_M && now - prev.atElapsedMs <= BEARING_MAX_AGE_MS && moving()) {
            return Geo.bearingDeg(prev.fix.lat, prev.fix.lon, fix.lat, fix.lon).toFloat()
        }
        return null
    }

    private fun Fix.valid(): Boolean =
        lat.isFinite() && lon.isFinite() && abs(lat) <= 90.0 && abs(lon) <= 180.0 && !(lat == 0.0 && lon == 0.0)

    private fun normalize(h: Float): Float = ((h % 360f) + 360f) % 360f

    companion object {
        /** 움직이는 동안 이만큼 새 위치가 없으면 끊긴 것으로 본다 (예전과 같은 15초) */
        const val STALE_MS = 15_000L
        /** 이 안이면 같은 자리 (위치 SDK와 내비의 지도 맞춤 차이 정도) */
        const val SAME_PLACE_M = 30.0
        /** 두 위치로 방위를 잴 최소 거리. 두 출처의 위치 차이에 흔들리지 않을 만큼 */
        const val BEARING_MIN_M = 50.0
        /** 이보다 오래된 직전 위치로는 방위를 재지 않는다 (그 사이 어디로 돌았는지 모른다) */
        const val BEARING_MAX_AGE_MS = 60_000L

        /** 차가 움직이는지. 차속을 알면 1km/h 이상, 모르면 P가 아닌지로 본다 */
        fun isMoving(speedKph: Float?, parked: Boolean): Boolean = speedKph?.let { it >= 1f } ?: !parked
    }
}

/** NaviTracker가 위치 SDK 대신 내비 위치를 받을지 묻고, 받은 위치를 넘기는 곳 ([PositionTracker.naviFallback]) */
interface LocationFallback {
    fun wanted(): Boolean
    fun onFix(fix: Fix, requestedAtMs: Long)
}
