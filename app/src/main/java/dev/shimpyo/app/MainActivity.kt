package dev.shimpyo.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.hmg.pleoskit.PleosForegroundService
import dev.hmg.pleoskit.PleosPermissions
import dev.shimpyo.app.coach.CoachService
import dev.shimpyo.app.ui.ShimpyoRoot

/**
 * 화면 진입점. 실행 중 권한(차속, 위치)을 받고 [CoachService]를 시작한 뒤 Compose 화면을 띄운다.
 * 판단과 신호 수집은 서비스가 하고, 화면은 CoachStore.state를 그리기만 한다.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ShimpyoRoot() }
        // 차속·위치 권한은 실행 중에 받아야 한다. 이미 받았으면 바로 서비스를 시작한다
        if (PleosPermissions.requestIfNeeded(this, REQUEST_PERMISSIONS)) startCoach()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startCoach()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 거부해도 서비스는 시작한다. 해당 신호만 비어 있게 된다
        if (requestCode == REQUEST_PERMISSIONS) startCoach()
    }

    private fun startCoach() {
        // 개발용 옵션(--ez verify true, --es demo 시나리오 등)을 서비스로 그대로 넘긴다
        PleosForegroundService.start(this, CoachService::class.java, Intent().apply { intent.extras?.let { putExtras(it) } })
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 7001
    }
}
