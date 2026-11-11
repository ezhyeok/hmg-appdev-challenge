package dev.shimpyo.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * 학습 상태 파일(memory.json) 쓰기. 회귀: 정차 기록 저장과 1분 저장이 같은 임시 파일을 동시에 써서 파일이 깨질 수 있었고,
 * 기록 지우기 뒤에 늦게 도착한 저장이 파일을 되살릴 수 있었다(2026-11-11 리뷰).
 */
class AtomicFileTest {
    private fun tempFile(): File = File(Files.createTempDirectory("atomic").toFile(), "memory.json")

    @Test
    fun concurrentWritesNeverInterleave() {
        val f = tempFile()
        val store = AtomicFile(f)
        val texts = (1..8).map { i -> "{\"w\":$i,\"pad\":\"" + "x".repeat(20_000 * i) + "\"}" }
        val start = CountDownLatch(1)
        val threads = texts.map { t -> thread { start.await(); repeat(20) { store.write(t) } } }
        start.countDown()
        threads.forEach { it.join() }
        assertTrue("파일은 어느 한 번의 쓰기 내용 그대로여야 한다", store.readText() in texts)
        assertEquals("임시 파일이 남지 않는다", listOf("memory.json"), f.parentFile!!.list()!!.toList())
    }

    @Test
    fun theNewContentIsOnDiskBeforeItTakesTheFilesName() {
        // 리뷰 R-F14: fsync 없이 이름부터 바꾸면 전원이 꺼졌을 때 이름은 새 파일인데 내용이 비어 있을 수 있다.
        // 디스크로 내리는 순간(sync)에 임시 파일에는 새 내용이 모두 있고, 원래 파일은 아직 옛 내용이어야 한다
        val f = tempFile()
        val seen = mutableListOf<String>()
        val store = object : AtomicFile(f) {
            override fun sync(fd: FileDescriptor) {
                val tmp = f.parentFile!!.listFiles { x -> x.name.endsWith(".tmp") }!!.single()
                seen += "target=${if (f.exists()) f.readText() else "(none)"} tmp=${tmp.readText()}"
                super.sync(fd)
            }
        }
        assertTrue(store.write("first"))
        assertTrue(store.write("second"))
        assertEquals(listOf("target=(none) tmp=first", "target=first tmp=second"), seen)
        assertEquals("second", store.readText())
    }

    @Test
    fun aBrokenFileIsMovedAsideInsteadOfBeingOverwritten() {
        // 리뷰 R-F14: 깨진 stops.json을 '기록 없음'으로 읽으면 다음 쓰기가 덮어썼다
        val f = tempFile()
        f.writeText("{broken")
        val store = AtomicFile(f)
        val kept = store.quarantine()
        assertEquals("memory.json.corrupt", kept?.name)
        assertEquals("{broken", kept!!.readText())
        assertNull("자리는 비었다", store.readText())
        assertTrue(store.write("fresh"))
        assertEquals("새로 써도 따로 둔 파일은 그대로다", "{broken", kept.readText())
        // 또 깨지면 따로 둔 것을 바꾼다 (하나만 둔다)
        f.writeText("{broken again")
        assertEquals("{broken again", store.quarantine()!!.readText())
        assertNull("파일이 없으면 옮길 것도 없다", store.quarantine())
        assertEquals(listOf("memory.json.corrupt"), f.parentFile!!.list()!!.toList())
    }

    @Test
    fun clearingAlsoRemovesTheFileKeptAside() {
        // 기록 지우기는 개인 기록을 남기지 않는다: 따로 둔 깨진 파일도 지운다
        val f = tempFile()
        f.writeText("{broken")
        val store = AtomicFile(f)
        store.quarantine()
        store.write("new")
        store.clear()
        assertTrue(f.parentFile!!.list()!!.isEmpty())
    }

    @Test
    fun aSaveTakenBeforeClearIsDropped() {
        val store = AtomicFile(tempFile())
        assertTrue(store.write("old"))
        val gen = store.generation // 저장할 내용을 꺼낸 때
        store.clear()
        assertFalse("지우기 전에 꺼낸 저장은 버린다", store.write("stale", gen))
        assertNull(store.readText())
        assertTrue("지운 뒤 새로 꺼낸 저장은 쓴다", store.write("new"))
        assertEquals("new", store.readText())
    }
}
