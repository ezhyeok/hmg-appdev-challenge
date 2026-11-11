package dev.shimpyo.app

import android.app.Application
import dev.shimpyo.app.data.MemoryStore
import dev.shimpyo.app.data.Settings
import dev.shimpyo.app.data.StopAssets
import dev.shimpyo.app.data.StopHistoryStore
import dev.shimpyo.app.data.TripStore
import dev.shimpyo.coach.StopRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/** 앱 전역 객체. 서비스와 화면이 같은 저장소를 쓴다 */
class ShimpyoApp : Application() {
    /**
     * 파일 쓰기 스레드 하나. 앱이 사는 동안 유지한다(서비스가 끝나도 밀린 쓰기는 마저 쓴다). 한 번에 하나씩, 맡긴 순서대로 쓴다:
     * 정차 기록의 '지우기 → 덧붙이기' 순서가 뒤집히지 않는다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    val settings by lazy { Settings(this) }
    val memory by lazy { MemoryStore(this) }
    val stopHistory by lazy { StopHistoryStore(this, writer) }
    val trips by lazy { TripStore(this, writer) }
    val stops by lazy { StopRepository { StopAssets.loadTable(this) } }
}
