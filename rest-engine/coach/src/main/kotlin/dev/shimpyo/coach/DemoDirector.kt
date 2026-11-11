package dev.shimpyo.coach

import dev.shimpyo.engine.CoachInput
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.StopCandidate

/**
 * 호환용 겉면. 옛 CoachService가 코치 액터(CoachCore + WorldSwitch)로 옮겨질 때까지만 둔다. 새 코드는 [DemoRun]과
 * [CoachWorld.Demo]를 쓴다. 바꾸는 상태는 불변 [DemoRun] 참조 하나뿐이라 시나리오와 시작 시각을 섞어 읽을 수 없다
 * (예전에는 세 필드를 따로 바꿨다, 구조 리뷰 Q2·서비스 감사 P1). 메서드마다 참조를 한 번만 읽는다.
 *
 * 시계: 옛 동작과 같게 [realClock] 하나로 실제 시각과 진행 경과를 모두 잰다 (앱 기본값 System.currentTimeMillis).
 */
class DemoDirector(private val realClock: () -> Long = System::currentTimeMillis) {
    data class Pt(val lat: Double, val lon: Double)

    /** 시연 시작 조건. 서비스가 새 엔진의 프로필로 쓴다 */
    data class Start(val occupants: List<Occupant>, val drivenMin: Int, val lastRestroomMin: Int)

    private val elapsed = ElapsedClock { realClock() }

    @Volatile private var run: DemoRun? = null

    val name: String? get() = run?.name
    val active: Boolean get() = run != null
    /** 시연 중에는 requestTrip 대신 가상 경로 거리를 쓴다 */
    val oracleEnabled: Boolean get() = run == null

    fun now(): Long = run?.now() ?: realClock()

    /** 시나리오 시작. 탑승자, 이미 달린 시간, 마지막 화장실을 돌려준다. 시연을 끄면 null */
    fun start(name: String?, realNow: Long = realClock()): Start? {
        val r = DemoRun.start(name, wallNow = realNow, elapsed = elapsed, elapsedNow = realNow)
        run = r
        return r?.let { Start(it.scenario.occupants, it.scenario.drivenMin, it.scenario.lastRestroomMin) }
    }

    /** 시연 경로에서 점까지의 거리와 현재 진행 거리 (테스트용). 경로 밖이면 null */
    internal fun alongForTest(p: Pt): Double? = run?.alongM(DemoRun.Pt(p.lat, p.lon))
    internal fun travelledForTest(): Double? = run?.travelledM()
    internal fun metersPerSecondForTest(): Double? = run?.scenario?.metersPerSecond

    /** 가상 위치와 진행 방향. 시연이 아니면 null */
    fun position(): Triple<Double, Double, Float>? = run?.position()?.let { Triple(it.lat, it.lon, it.heading ?: 0f) }

    /** 경로 상태와 정체를 시나리오 값으로 바꾼다 */
    fun apply(input: CoachInput): CoachInput = run?.apply(input) ?: input

    /** 시연 중에는 후보의 경로 거리를 가상 경로를 따라 다시 계산한다 */
    fun candidates(list: List<StopCandidate>): List<StopCandidate> = run?.candidates(list) ?: list
}
