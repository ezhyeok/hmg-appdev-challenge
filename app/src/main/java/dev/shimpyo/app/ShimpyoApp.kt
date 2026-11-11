package dev.shimpyo.app

import android.app.Application
import dev.shimpyo.app.data.MemoryStore
import dev.shimpyo.app.data.Settings
import dev.shimpyo.app.data.StopHistoryStore
import dev.shimpyo.app.data.StopRepository

/** 앱 전역 객체. 서비스와 화면이 같은 저장소를 쓴다 */
class ShimpyoApp : Application() {
    val settings by lazy { Settings(this) }
    val memory by lazy { MemoryStore(this) }
    val stopHistory by lazy { StopHistoryStore(this) }
    val stops by lazy { StopRepository(this) }
}
