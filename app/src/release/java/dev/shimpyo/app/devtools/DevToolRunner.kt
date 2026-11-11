package dev.shimpyo.app.devtools

import android.content.Context
import dev.hmg.pleoskit.LocationKit
import dev.hmg.pleoskit.NaviKit
import dev.hmg.pleoskit.SttKit
import dev.hmg.pleoskit.TtsKit
import dev.hmg.pleoskit.VehicleKit
import dev.shimpyo.app.coach.DevHooks

/**
 * 제출용(release) 빌드: 개발용 점검이 없다. debug의 같은 이름(app/src/debug)과 모양만 같고 아무것도 하지 않는다.
 * DevHooks가 release에서는 점검 요청을 받아들이지 않으므로 [run]은 불리지 않는다. 불려도 kit를 만들지도 차를 움직이지도 않는다
 * (닫힘 검증 N1: release에서 `--ez selftest true`가 좌석·창문을 움직였다).
 */
object DevToolRunner {
    /** 이 빌드에 점검 코드가 있는지 */
    const val AVAILABLE = false

    class Kits(
        val vehicle: () -> VehicleKit,
        val navi: () -> NaviKit,
        val tts: () -> TtsKit,
        val stt: () -> SttKit,
        val location: () -> LocationKit,
    )

    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    suspend fun run(request: DevHooks.Request, context: Context, kits: Kits) = Unit
}
