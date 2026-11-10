package dev.hmg.sdkprobe

import android.util.Log

/** probe 결과 한 줄을 logcat(tag: PleosProbe)에 남기고, 화면이 열려 있으면 화면에도 보낸다. */
object ProbeLog {
    private const val TAG = "PleosProbe"

    @Volatile
    var sink: ((String) -> Unit)? = null

    fun log(area: String, name: String, status: String, detail: Any?) {
        val line = "PROBE|$area|$name|$status|$detail"
        Log.i(TAG, line)
        sink?.invoke(line)
    }

    fun ok(area: String, name: String, detail: Any?) = log(area, name, "OK", detail)
    fun info(area: String, name: String, detail: Any?) = log(area, name, "INFO", detail)
    fun fail(area: String, name: String, e: Throwable) =
        log(area, name, "FAIL", "${e.javaClass.simpleName}: ${e.message}")
}
