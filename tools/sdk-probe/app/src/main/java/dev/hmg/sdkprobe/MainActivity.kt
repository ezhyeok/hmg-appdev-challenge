package dev.hmg.sdkprobe

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** ProbeService를 포그라운드 서비스로 시작하고, 결과 줄을 화면에 보여 준다. */
class MainActivity : Activity() {

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setPadding(24, 24, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(out) })
        ProbeLog.sink = { line -> runOnUiThread { out.append(line + "\n") } }
        startForegroundService(Intent(this, ProbeService::class.java))
    }

    override fun onDestroy() {
        ProbeLog.sink = null
        super.onDestroy()
    }
}
