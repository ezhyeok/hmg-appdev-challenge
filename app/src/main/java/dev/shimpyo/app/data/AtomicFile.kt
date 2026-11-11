package dev.shimpyo.app.data

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream

/**
 * 파일 하나를 통째로 바꿔 쓴다. 쓰기는 한 번에 하나씩, 매번 다른 임시 파일에 쓰고 디스크까지 내린 뒤(fsync) 이름을 바꿔 넣는다:
 * 쓰다 만 내용이 남지 않고, 전원이 꺼져도 옛 내용이나 새 내용 가운데 하나가 온전히 남는다. fsync 없이 이름부터 바꾸면
 * 파일시스템에 따라 이름은 바뀌었는데 내용은 비어 있을 수 있다 (리뷰 R-F14).
 * [clear] 뒤에는 그 전에 꺼낸 세대([generation])로 들어온 쓰기를 버린다(지운 뒤 늦게 도착한 저장이 파일을 되살리지 않게).
 * 안드로이드 API를 쓰지 않아 AtomicFileTest로 지킨다.
 */
open class AtomicFile(private val file: File) {
    private val lock = Any()
    private val dir: File get() = file.absoluteFile.parentFile

    @Volatile var generation = 0L
        private set

    /** [text]로 바꿔 쓴다. 그 사이 [clear]됐거나 쓰기에 실패하면 false */
    fun write(text: String, generationAtSnapshot: Long = generation): Boolean = synchronized(lock) {
        if (generation != generationAtSnapshot) return@synchronized false
        runCatching {
            val tmp = File.createTempFile(file.name, TMP_SUFFIX, dir)
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                    sync(out.fd)
                }
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

    /** 임시 파일의 내용을 디스크까지 내린다 (이름을 바꾸기 전). 테스트가 순서를 확인하려고 바꿔 끼운다 */
    protected open fun sync(fd: FileDescriptor) = fd.sync()

    fun readText(): String? = synchronized(lock) { if (file.exists()) file.readText() else null }

    /**
     * 읽을 수 없는(깨진) 파일을 `<이름>.corrupt`로 옮겨 두고 자리를 비운다. 옮긴 파일을 돌려준다 (파일이 없거나 못 옮겼으면 null).
     * 깨진 파일을 '없음'으로 읽고 그대로 두면 다음 쓰기가 덮어써서 내용을 영영 잃는다 (리뷰 R-F14). 예전 것이 있으면 바꿔 둔다.
     */
    fun quarantine(): File? = synchronized(lock) {
        if (!file.exists()) return@synchronized null
        val kept = File(dir, file.name + CORRUPT_SUFFIX)
        kept.delete()
        if (file.renameTo(kept)) kept else null
    }

    /** 파일과 남은 임시 파일, 따로 둔 깨진 파일을 지우고 세대를 올린다 */
    fun clear(): Unit = synchronized(lock) {
        generation++
        file.delete()
        File(dir, file.name + CORRUPT_SUFFIX).delete()
        dir.listFiles { f -> f.name.startsWith(file.name) && f.name.endsWith(TMP_SUFFIX) }?.forEach { it.delete() }
    }

    companion object {
        const val CORRUPT_SUFFIX = ".corrupt"
        private const val TMP_SUFFIX = ".tmp"
    }
}
