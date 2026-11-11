package dev.hmg.pleosapp

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import dev.hmg.pleoskit.PleosForegroundService
import dev.hmg.pleoskit.PleosPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 뼈대 화면. 실행 중 권한을 받고 DriveService를 시작한 뒤, 모인 차량 상태를 그대로 보여 준다(개발용 상태판).
 * 아이디어가 정해지면 실제 화면으로 바꾼다.
 */
class MainActivity : Activity() {

    private val uiScope = CoroutineScope(Dispatchers.Main)
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 22f
            setPadding(48, 48, 48, 48)
        }
        setContentView(ScrollView(this).apply { addView(out) })

        uiScope.launch {
            DriveState.snapshot.collect { s -> out.text = render(s) }
        }
        // 차속·위치 권한은 실행 중에 받아야 한다. 이미 받았으면 바로 서비스를 시작한다
        if (PleosPermissions.requestIfNeeded(this, REQUEST_PERMISSIONS)) startDriveService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 거부해도 서비스는 시작한다. 해당 신호만 비어 있게 된다
        if (requestCode == REQUEST_PERMISSIONS) startDriveService()
    }

    private fun startDriveService() {
        // 개발용 점검 옵션(--ez selftest true 등)을 서비스로 그대로 넘긴다
        PleosForegroundService.start(this, DriveService::class.java, Intent().apply { intent.extras?.let { putExtras(it) } })
    }

    private fun render(s: DriveSnapshot): String = buildString {
        appendLine("기어            ${s.gear ?: "-"}")
        appendLine("차속            ${s.speedKph?.let { "%.0f km/h".format(it) } ?: "-"}")
        appendLine("주행 제한       ${s.drivingRestricted}")
        appendLine("내기순환        ${s.recirculation ?: "-"}")
        appendLine("공조 전원       ${s.hvacPower ?: "-"}")
        appendLine("설정 온도       ${s.cabinTargetTempC?.let { "$it °C" } ?: "-"}")
        appendLine("크루즈          ${s.cruise ?: "-"}")
        appendLine("주행 시간       ${s.tripDrivingMin?.let { "$it 분" } ?: "-"}")
        appendLine("브레이크        ${s.brakePressed ?: "-"}")
        appendLine("운전석 도어     ${s.driverDoorOpen?.let { if (it) "열림" else "닫힘" } ?: "-"}")
        appendLine("배터리          ${s.stateOfCharge?.let { "$it %" } ?: "-"}")
        appendLine("내비 모드       ${s.naviMode ?: "-"}")
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 7001
    }
}
