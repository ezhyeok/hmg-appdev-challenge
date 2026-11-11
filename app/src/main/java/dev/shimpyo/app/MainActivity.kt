package dev.shimpyo.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.hmg.pleoskit.PleosForegroundService
import dev.hmg.pleoskit.PleosPermissions
import dev.shimpyo.app.coach.CoachService
import dev.shimpyo.app.coach.CoachStore
import dev.shimpyo.app.ui.PermissionPolicy
import dev.shimpyo.app.ui.ShimpyoRoot
import dev.shimpyo.app.ui.UiRestrictions
import dev.shimpyo.app.ui.driveRestriction
import dev.shimpyo.coach.UiCommand
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 화면 진입점. [CoachService]를 시작하고 Compose 화면을 띄운다. 판단과 신호 수집은 서비스가 하고,
 * 화면은 CoachStore.state를 그리기만 한다.
 *
 * - 서비스는 권한과 관계없이 화면이 보일 때 시작한다(이미 돌고 있으면 그대로). 서비스가 멈췄다가 화면으로 돌아오면 다시 시작한다.
 * - 실행 중 권한(차속, 위치)은 정차 중일 때 한 번만 묻는다([PermissionPolicy]). 새로 허용되면 서비스에 PermissionsChanged를 보낸다.
 * - 주행 제한은 화면이 서비스 없이 스스로 구독한다(UiRestrictions).
 */
class MainActivity : ComponentActivity() {

    /** 이번 액티비티(다시 만들어진 것 포함)에서 권한 창을 이미 띄웠는지 */
    private var permissionsAsked = false
    /** 마지막으로 확인한 빠진 권한 (새로 허용됐는지 비교한다) */
    private var lastMissing: List<String> = emptyList()
    private var forwardExtras = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ShimpyoRoot() }
        // 창 분할·크기 변경으로 화면이 다시 만들어질 때(savedInstanceState != null)는 처음 실행 인텐트의 개발용 옵션
        // (--es demo 등)을 다시 보내지 않는다. 다시 보내면 시연이 처음부터 다시 시작된다(2026-11-11 창 분할에서 확인,
        // tools/laptop/demo-smoke.sh --split이 지킨다)
        forwardExtras = savedInstanceState == null
        permissionsAsked = savedInstanceState?.getBoolean(KEY_PERMISSIONS_ASKED) ?: false
        lastMissing = PleosPermissions.missing(this)
        // 정차 중임을 읽은 뒤에만 권한 창을 띄운다. 주행 중에 실행했으면 정차할 때까지 기다린다
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UiRestrictions.state(this@MainActivity)
                    .map { driveRestriction(it) }
                    .distinctUntilChanged()
                    .collect { r ->
                        val missing = PleosPermissions.missing(this@MainActivity)
                        if (PermissionPolicy.shouldAsk(permissionsAsked, missing, r)) {
                            permissionsAsked = true
                            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
                        }
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 처음 실행, 새 인텐트의 옵션, 또는 서비스가 멈췄다가 돌아온 경우 (이미 돌고 있으면 다시 부르지 않는다)
        if (forwardExtras || !CoachStore.state.value.serviceRunning) startCoach()
    }

    override fun onResume() {
        super.onResume()
        // 설정 앱에서 권한을 허용하고 돌아왔을 수 있다
        checkGranted()
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
        // 거부해도 서비스는 계속 돈다. 해당 신호만 비어 있게 된다
        if (requestCode == REQUEST_PERMISSIONS) checkGranted()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PERMISSIONS_ASKED, permissionsAsked)
    }

    private fun checkGranted() {
        val now = PleosPermissions.missing(this)
        if (PermissionPolicy.newlyGranted(lastMissing, now)) CoachStore.send(UiCommand.PermissionsChanged)
        lastMissing = now
    }

    private fun startCoach() {
        // 개발용 옵션(--ez verify true, --es demo 시나리오 등)을 서비스로 그대로 넘긴다. 한 인텐트는 한 번만 넘긴다
        val extras = if (forwardExtras) intent.extras else null
        forwardExtras = false
        PleosForegroundService.start(this, CoachService::class.java, Intent().apply { extras?.let { putExtras(it) } })
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 7001
        const val KEY_PERMISSIONS_ASKED = "permissionsAsked"
    }
}
