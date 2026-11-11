package dev.shimpyo.app.devtools

import android.content.Context
import dev.hmg.pleoskit.LocationKit
import dev.hmg.pleoskit.NaviKit
import dev.hmg.pleoskit.SttKit
import dev.hmg.pleoskit.TtsKit
import dev.hmg.pleoskit.VehicleKit
import dev.shimpyo.app.coach.DevHooks

/**
 * 개발용 점검을 돌리는 곳 (debug 빌드). 서비스는 이 이름만 알고, 제출용(release) 빌드에는 아무것도 하지 않는 같은 이름이
 * 있다(app/src/release). 그래서 점검 코드(KitSelfTest·SdkVerify)는 release APK에 들어가지 않는다 (닫힘 검증 N1).
 * kit는 필요한 것만 그때 만든다 (서비스의 Kits가 처음 쓸 때 만든다).
 */
object DevToolRunner {
    /** 이 빌드에 점검 코드가 있는지 */
    const val AVAILABLE = true

    class Kits(
        val vehicle: () -> VehicleKit,
        val navi: () -> NaviKit,
        val tts: () -> TtsKit,
        val stt: () -> SttKit,
        val location: () -> LocationKit,
    )

    /** 점검 하나를 끝까지 돌린다. 결과는 logcat `PleosApp` */
    suspend fun run(request: DevHooks.Request, context: Context, kits: Kits) {
        when (request.tool) {
            DevHooks.Tool.VERIFY -> SdkVerify(context, kits.vehicle(), kits.navi(), kits.location()).run()
            DevHooks.Tool.SELFTEST -> KitSelfTest(kits.vehicle(), kits.navi(), kits.tts(), kits.stt(), kits.location()).run(request.withRoute)
            DevHooks.Tool.PROBE -> SdkProbe(kits.vehicle, kits.navi, kits.tts, kits.stt).run(request.probe.orEmpty())
            null -> Unit
        }
    }
}
