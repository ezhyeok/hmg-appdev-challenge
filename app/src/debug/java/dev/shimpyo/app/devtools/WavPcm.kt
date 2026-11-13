package dev.shimpyo.app.devtools

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * STT 실험(SdkProbe `stt-pcm`)에 넣을 오디오를 꺼낸다. STT의 sendAudio는 16kHz·16bit·mono PCM을 받는다
 * (Pleos 가이드 sending-audio-data.md). WAV면 fmt 덩어리를 확인하고 data 덩어리만 돌려준다. RIFF가 아니면 이미 PCM으로 본다.
 * 형식이 다르면 null (debug 빌드 전용)
 */
object WavPcm {
    fun pcm16kMono(bytes: ByteArray): ByteArray? {
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") {
            return bytes.takeIf { it.isNotEmpty() && it.size % 2 == 0 }
        }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var formatOk = false
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            if (size < 0 || pos + 8 + size > bytes.size) return null
            when (id) {
                "fmt " -> {
                    val format = b.getShort(pos + 8).toInt()
                    val channels = b.getShort(pos + 10).toInt()
                    val rate = b.getInt(pos + 12)
                    val bits = b.getShort(pos + 22).toInt()
                    formatOk = format == 1 && channels == 1 && rate == 16_000 && bits == 16
                }
                "data" -> return if (formatOk) bytes.copyOfRange(pos + 8, pos + 8 + size) else null
            }
            pos += 8 + size + (size and 1) // 덩어리는 짝수 바이트로 맞춘다
        }
        return null
    }
}
