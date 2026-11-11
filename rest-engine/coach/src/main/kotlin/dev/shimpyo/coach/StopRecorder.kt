package dev.shimpyo.coach

import dev.shimpyo.engine.Gear
import dev.shimpyo.engine.StopKind

/**
 * 끝난 정차를 기록한다 (stops.json + 휴식 기록 화면). CoachCore가 들고 코치 틱마다 [onTick]을 부른다.
 *
 * - 좌표는 남기지 않는다. 300m 안의 알려진 정차지(휴게소 등)면 이름만 남긴다 (개인정보, docs/research/ai-science.md 8-7)
 * - 저장은 [onTick]의 persist가 true일 때만 한다(CoachWorld.persistsHistory). 시연 정차는 화면에만 보이고 세계가 바뀌면 사라진다.
 *   예전에는 시연 정차가 가상 시각으로 저장돼 다음 실제 시작이 미래의 '마지막 화장실'을 읽었다(서비스 감사 D3, UI 감사 #15)
 * - 세계가 바뀌면 [onWorldChanged]: 정차 시작을 새 시계로 옮기고 저장하지 않은 기록을 버린다
 *
 * 스레드: 코치 디스패처에서만 부른다. HistoryPort.append는 작은 파일 하나를 원자적으로 쓴다(AtomicFile, 정차 한 건에 한 번).
 */
class StopRecorder(
    private val detector: StopDetector,
    private val history: HistoryPort,
    private val stops: StopIndex,
    private val log: CoachLog = CoachLog.NONE,
) {
    /** history: 화면에 보일 전체 기록, persisted: stops.json에 저장했는지 */
    data class Recorded(val log: StopLog, val history: List<StopLog>, val persisted: Boolean)

    private var saved: List<StopLog> = emptyList()
    private var unsaved: List<StopLog> = emptyList()
    /** 정차 중 마지막으로 안 위치. 출발 틱의 위치보다 서 있던 곳을 잘 나타낸다 */
    private var parkedAt: Fix? = null

    /** 화면에 보일 기록: 저장된 것 + 이 세계에서 저장하지 않은 것 */
    val shown: List<StopLog> get() = saved + unsaved

    val parkState: ParkState? get() = detector.parkState

    /** 시작할 때 읽은 저장 기록 (읽기는 코어가 IO에서 한다. 미래 시각 정리는 TripSession이 먼저 한다) */
    fun load(list: List<StopLog>) {
        saved = list
    }

    /** [pos]: 지금 세계의 위치 (시연이면 시연 위치). [persist]: 지금 세계가 기록을 저장하는지 */
    fun onTick(t: Long, gear: Gear, speedKph: Float?, doorOpened: Boolean, pos: Fix?, persist: Boolean): Recorded? {
        val stop = detector.onTick(t, gear, speedKph, doorOpened)
        if (detector.parked) {
            if (pos != null) parkedAt = pos
            return null
        }
        val where = parkedAt ?: pos
        parkedAt = null
        if (stop == null) return null
        val entry = StopLog(start = stop.start, end = stop.end, lat = null, lon = null, restroom = stop.doorOpened, name = where?.let { placeName(it, t) })
        if (persist) {
            try {
                saved = history.append(entry)
                return Recorded(entry, shown, persisted = true)
            } catch (e: Exception) {
                log.w("stop history save failed: ${e.message}")
            }
        }
        unsaved = unsaved + entry
        return Recorded(entry, shown, persisted = false)
    }

    /** 세계(시연·실제)가 바뀌었다. [now]는 새 세계의 코치 시각 */
    fun onWorldChanged(now: Long) {
        detector.rebase(now)
        unsaved = emptyList()
    }

    /** 기록 지우기. 지금 서 있는 정차는 차의 상태라 그대로 둔다 */
    fun clear() {
        history.clear()
        saved = emptyList()
        unsaved = emptyList()
    }

    /** 앱 재시작: TripSession이 저장해 둔 정차를 이어 간다 */
    fun resume(state: ParkState, now: Long) = detector.resume(state, now)

    private fun placeName(at: Fix, now: Long): String? =
        stops.near(at.lat, at.lon, null, PLACE_RADIUS_M, PLACE_KINDS, now = now, limit = 1).firstOrNull()?.name

    companion object {
        const val PLACE_RADIUS_M = 300.0
        /** 차를 세울 수 있는 곳만 (기타 시설 이름으로 정차지를 부르지 않는다) */
        val PLACE_KINDS = setOf(
            StopKind.HIGHWAY_REST_AREA, StopKind.DROWSY_SHELTER, StopKind.PUBLIC_RESTROOM, StopKind.GAS_STATION, StopKind.PUBLIC_PARKING,
        )
    }
}
