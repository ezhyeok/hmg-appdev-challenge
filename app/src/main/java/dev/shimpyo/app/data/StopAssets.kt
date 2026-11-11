package dev.shimpyo.app.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.shimpyo.engine.stops.StopTable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * assets/data/stops.bin을 읽는다. 이 표는 rest-engine의 buildStopTable이 tools/data의 JSON을 StopRows 규칙으로
 * 변환해 만든 것이다(형식은 StopTable 주석). 예전에는 JSON 13MB를 행마다 Map → StopCandidate로 읽어 에뮬레이터에서
 * 18초 동안 GC가 돌았다. 지금은 배열을 통째로 읽고 질의 결과로 나간 행만 StopCandidate를 만든다.
 * JSON과 같은 후보가 나오는지는 rest-engine StopTableTest가 6만여 행 전부를 비교해 지킨다.
 */
object StopAssets {
    private const val TAG = "Shimpyo"
    private const val PATH = "data/stops.bin"

    /**
     * 표를 읽는다. 파일이 없거나 형식이 다르면 null(후보 없이 동작한다).
     * stops.bin은 APK에 압축하지 않고 넣어(app/build.gradle.kts noCompress) 압축 풀기 없이 메모리 매핑으로 읽는다.
     * 매핑이 안 되면(압축된 경우) 스트림으로 읽는다.
     */
    fun loadTable(context: Context): StopTable? = try {
        val t0 = SystemClock.elapsedRealtime()
        val table = mapped(context)?.let(StopTable::decode)
            ?: StopTable.decode(context.assets.open(PATH).use { it.readBytes() })
        table.also { Log.i(TAG, "stop table loaded: ${it.size} in ${SystemClock.elapsedRealtime() - t0} ms") }
    } catch (e: Exception) {
        Log.w(TAG, "stop table $PATH failed: ${e.message}")
        null
    }

    private fun mapped(context: Context): ByteBuffer? = try {
        context.assets.openFd(PATH).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length) }
        }
    } catch (e: IOException) {
        null // 압축된 asset은 openFd가 안 된다
    }
}
