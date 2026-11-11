package dev.shimpyo.app.data

import java.io.File

/**
 * 파일 하나를 통째로 바꿔 쓴다. 쓰기는 한 번에 하나씩, 매번 다른 임시 파일에 쓴 뒤 이름을 바꿔 넣는다(쓰다 만 내용이 남지 않게).
 * [clear] 뒤에는 그 전에 꺼낸 세대([generation])로 들어온 쓰기를 버린다(지운 뒤 늦게 도착한 저장이 파일을 되살리지 않게).
 * 안드로이드 API를 쓰지 않아 AtomicFileTest로 지킨다.
 */
class AtomicFile(private val file: File) {
    private val lock = Any()

    @Volatile var generation = 0L
        private set

    /** [text]로 바꿔 쓴다. 그 사이 [clear]됐거나 쓰기에 실패하면 false */
    fun write(text: String, generationAtSnapshot: Long = generation): Boolean = synchronized(lock) {
        if (generation != generationAtSnapshot) return@synchronized false
        runCatching {
            val tmp = File.createTempFile(file.name, ".tmp", file.absoluteFile.parentFile)
            try {
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    // 일부 파일시스템은 대상이 있으면 rename이 실패한다
                    file.delete()
                    check(tmp.renameTo(file)) { "rename failed" }
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }.isSuccess
    }

    fun readText(): String? = synchronized(lock) { if (file.exists()) file.readText() else null }

    /** 파일과 남은 임시 파일을 지우고 세대를 올린다 */
    fun clear(): Unit = synchronized(lock) {
        generation++
        file.delete()
        file.absoluteFile.parentFile?.listFiles { f -> f.name.startsWith(file.name) && f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }
}
