package dev.shimpyo.app.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.shimpyo.engine.stops.StopTable
import dev.shimpyo.engine.stops.StopTableFormatException
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
     * 표를 읽는다. 파일이 없거나 깨졌거나 형식 버전이 다르면 null(후보 없이 동작한다).
     * stops.bin은 APK에 압축하지 않고 넣어(app/build.gradle.kts noCompress) 압축 풀기 없이 메모리 매핑으로 읽는다.
     * 매핑이 안 되면(압축된 경우) 스트림으로 읽는다.
     *
     * StopTable.decode는 머리말의 개수를 파일 크기와 맞춰 본 뒤에만 배열을 잡으므로 깨진 파일이 OutOfMemoryError를
     * 내지 않고, 값을 하나라도 벗어나면 StopTableFormatException 하나로 실패한다(rest-engine StopTableDecodeTest).
     */
    fun loadTable(context: Context): StopTable? = try {
        val t0 = SystemClock.elapsedRealtime()
        val table = mapped(context)?.let(StopTable::decode)
            ?: StopTable.decode(context.assets.open(PATH).use { it.readBytes() })
        table.also { Log.i(TAG, "stop table loaded: ${it.size} in ${SystemClock.elapsedRealtime() - t0} ms") }
    } catch (e: StopTableFormatException) {
        Log.e(TAG, "stop table $PATH is corrupt or from another version: ${e.message}")
        null
    } catch (e: IOException) {
        Log.w(TAG, "stop table $PATH unreadable: ${e.message}")
        null
    } catch (e: RuntimeException) {
        // 검사를 통과한 표에서는 나지 않아야 한다(버그). 차 안에서는 앱을 죽이지 않고 후보 없이 동작한다
        Log.e(TAG, "stop table $PATH failed unexpectedly", e)
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
