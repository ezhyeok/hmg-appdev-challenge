package dev.shimpyo.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
        // 창 분할·크기 변경으로 화면이 다시 만들어질 때(savedInstanceState != null)는 처음 실행 인텐트의 개발용 옵션
        // (--es demo 등)을 다시 보내지 않는다. 다시 보내면 시연이 처음부터 다시 시작된다(2026-11-11 창 분할에서 확인,
        // tools/laptop/demo-smoke.sh --split이 지킨다)
        forwardExtras = savedInstanceState == null
        // 차속·위치 권한은 실행 중에 받아야 한다. 이미 받았으면 바로 서비스를 시작한다
        if (PleosPermissions.requestIfNeeded(this, REQUEST_PERMISSIONS)) startCoach()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i("Shimpyo", "onNewIntent extras=${intent.extras?.keySet()}")
        setIntent(intent)
        forwardExtras = true
        startCoach()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 거부해도 서비스는 시작한다. 해당 신호만 비어 있게 된다
        if (requestCode == REQUEST_PERMISSIONS) startCoach()
    }

    private var forwardExtras = true

    private fun startCoach() {
        // 개발용 옵션(--ez verify true, --es demo 시나리오 등)을 서비스로 그대로 넘긴다. 한 인텐트는 한 번만 넘긴다
        val extras = if (forwardExtras) intent.extras else null
        forwardExtras = false
        PleosForegroundService.start(this, CoachService::class.java, Intent().apply { extras?.let { putExtras(it) } })
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 7001
    }
}
