package dev.shimpyo.coach

import dev.shimpyo.engine.CoachEngine
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.StopCandidate
import kotlinx.coroutines.CoroutineDispatcher

/**
 * 실제 시각(epoch ms, System.currentTimeMillis)을 주는 시계의 이름. 타입은 [CoachClock]과 같다: 실제 세계의 코치 시각이 곧
 * 실제 시각이라서다(CoachWorld.Real(wall)). 이름을 따로 둔 것은 시연의 가상 시계(DemoRun)가 오면 안 되는 자리
 * (맥박 저장 시각, 시연을 켠 때, 실제 세계의 시계)를 읽는 사람이 알게 하려는 것이다.
 */
typealias WallClock = CoachClock

/**
 * 코어가 쓰는 바깥(포트)과 부품 한 묶음. 앱(CoachService)과 테스트(CoreRig)가 같은 방법으로 만든다.
 * [tracker]·[candidates]의 루프는 코어 밖에서 같은 코치 디스패처로 돌린다(서비스가 launch). 코어는 그 스냅샷만 읽는다.
 */
class CoachDeps(
    /** 실제 시각 (epoch ms). 실제 세계의 코치 시각이고 맥박·정차 기록에 쓴다 */
    val wall: WallClock,
    val elapsed: ElapsedClock,
    val hub: SignalHub,
    val odometer: Odometer,
    val positions: PositionTracker,
    val navi: NaviPort,
    val tracker: NaviTracker,
    val candidates: CandidateProvider,
    val oracle: TripEstimator,
    val stops: StopIndex,
    val voice: VoicePort,
    /** 규칙으로 못 알아들은 말을 맡는다. 없으면 null */
    val interpreter: IntentInterpreter?,
    val cabin: CabinPort,
    val history: HistoryPort,
    val memory: MemoryPort,
    val settings: SettingsPort,
    val trips: TripStorePort,
    val engines: EngineFactory,
    val ui: UiSink,
    val loopInputs: LoopInputs,
    /** 파일 읽기·쓰기 (앱: Dispatchers.IO, 테스트: 테스트 디스패처) */
    val io: CoroutineDispatcher,
    val log: CoachLog = CoachLog.NONE,
    /** 고른 정차를 엔진에 알리는 길. 앱은 기본값([EngineAccepted.ENGINE])을 쓴다. 테스트는 알린 것을 적어 두는 것으로 바꾼다 */
    val accepted: EngineAccepted = EngineAccepted.ENGINE,
)

/**
 * "운전자가 이 정차를 골랐고 내비 명령이 성공했다"를 엔진에 알리는 길 (서비스 닫힘 검증 D7, UI 닫힘 검증 9a).
 * 엔진이 묻지 않은 곳(급해 목록의 2순위, 계획 화면)을 골라도 엔진이 그곳을 '들르기로 한 곳'으로 알아야, 그곳으로 가는 동안
 * 다른 정차를 묻지 않는다. 코어는 성공한 들르기마다(질문 수락, 급해 목록, 계획 화면, 다시 시도) 엔진 보고 뒤에 한 번 부르고,
 * 주행 중 다시 떴을 때 맥박에 적어 둔 곳이 후보에 보이면 한 번 더 부른다 (TripSession.restoredAccepted).
 *
 * 실제 구현([ENGINE])은 CoachEngine.noteAccepted 그대로다: 그곳이 추천(NEXT_ACCEPTED)이 되고, 같은 곳을 묻던 질문은 수락으로,
 * 다른 곳을 묻던 화장실 질문은 벌점 없이 끝난다. 엔진은 그곳이 후보 목록에서 1분 넘게 빠지면 잊으므로, 코어가 그곳을 같은
 * id로 후보에 계속 넣어 준다 ([StopPin]). 따로 이름을 둔 것은 테스트가 "언제, 몇 번, 어떤 목적으로
 * 알렸나"를 엔진 속을 보지 않고 확인하게 하려는 것이다 (fakes/RecordingAccepted: 적고 나서 엔진에 그대로 넘긴다).
 */
fun interface EngineAccepted {
    fun note(engine: CoachEngine, stop: StopCandidate, purposes: Set<Purpose>, now: Long)

    companion object {
        /** 엔진에 그대로 알린다 */
        val ENGINE = EngineAccepted { engine, stop, purposes, now -> engine.noteAccepted(stop, purposes, now) }
    }
}

/**
 * 코어가 쓰고 다른 두 루프가 읽는 입력. 내비 추적과 후보 루프는 코어보다 먼저 만들어지므로(코어의 부품이다) 코어를 직접 참조하지 않고
 * 이것을 읽는다. 모두 같은 코치 디스패처에서 돌지만 값을 통째로 바꾸므로 @Volatile로 둔다.
 */
class LoopInputs {
    /** 내비 추적 루프: 위치 SDK가 끊겼을 때 내비 위치로 보충해도 되는지 (CoachWorld.usesNaviLocationFallback) */
    @Volatile var naviFallback: Boolean = true
    /** 후보 갱신 루프의 다음 입력 (CandidateProvider.run) */
    @Volatile var request: CandidateRequest = CandidateRequest(null, NaviSnapshot(), CandidatePolicy.REAL)
}
