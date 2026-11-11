package dev.shimpyo.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
