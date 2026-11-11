package dev.shimpyo.app.data

import dev.shimpyo.coach.ParkState
import dev.shimpyo.coach.StopLog
import dev.shimpyo.coach.TripHeartbeat
import dev.shimpyo.engine.PersonalMemory
import dev.shimpyo.engine.PromptStat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

/**
 * 앱 저장소 (정차 기록 stops.json, 여정 맥박 trip.json, 학습 memory.json). 파일은 임시 폴더에 두고, 쓰기 스레드는
 * 테스트가 직접 돌린다(runCurrent): 코치 스레드가 부른 순간과 파일에 쓰는 순간을 따로 본다.
 * 닫는 결함: 서비스 감사 D14(기록 덮어쓰기·지우기 경쟁), P9(스레드 사이에 나눠 쓰는 값), 리뷰 R-F14(깨진 파일을 덮어씀).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoresTest {
    private val dir: File = Files.createTempDirectory("stores").toFile()

    /** 앱의 쓰기 스레드 대신: runCurrent()를 부를 때 밀린 쓰기가 맡긴 순서대로 돈다 */
    private fun TestScope.writer() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private fun stop(startMin: Long, restroom: Boolean = false, name: String? = null) =
        StopLog(start = startMin * 60_000, end = (startMin + 12) * 60_000, lat = null, lon = null, restroom = restroom, name = name)

    private fun stored(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    // ------------------------------------------------------------ 정차 기록

    @Test
    fun aStopIsVisibleAtOnceAndWrittenByTheWriter() = runTest {
        val store = StopHistoryStore(File(dir, "stops.json"), writer())
        val a = stop(10, restroom = true, name = "옥산휴게소")
        assertEquals(listOf(a), store.append(a))
        assertEquals("코치 스레드는 쓰기를 기다리지 않는다", listOf(a), store.load())
        assertNull("아직 쓰지 않았다", stored("stops.json"))
        runCurrent()
        assertEquals(listOf(a), StopHistoryJson.decode(stored("stops.json")!!))
        // 다시 켜면 파일에서 읽는다
        assertEquals(listOf(a), StopHistoryStore(File(dir, "stops.json"), writer()).load())
    }

    @Test
    fun clearingThenAppendingLeavesOnlyTheNewStop() = runTest {
        // D14: 지운 뒤에 덧붙인 기록이 늦게 도는 지우기에 휩쓸리면 안 된다
        val store = StopHistoryStore(File(dir, "stops.json"), writer())
        store.append(stop(10))
        store.append(stop(40))
        runCurrent()
        store.clear()
        val fresh = stop(90, restroom = true)
        store.append(fresh)
        assertEquals(listOf(fresh), store.load())
        runCurrent()
        assertEquals(listOf(fresh), StopHistoryJson.decode(stored("stops.json")!!))
    }

    @Test
    fun aWriteQueuedBeforeClearDoesNotBringTheFileBack() = runTest {
        // D14: 지우기 전에 맡긴 쓰기가 지운 뒤 파일을 되살리면 안 된다
        val store = StopHistoryStore(File(dir, "stops.json"), writer())
        store.append(stop(10))
        store.append(stop(40))
        store.clear() // 두 쓰기가 아직 돌기 전
        assertTrue(store.load().isEmpty())
        runCurrent()
        assertNull("맡긴 순서대로 돌아서 마지막이 지우기다", stored("stops.json"))
        assertTrue(StopHistoryStore(File(dir, "stops.json"), writer()).load().isEmpty())
    }

    @Test
    fun aBrokenHistoryFileIsKeptAsideAndTheNextStopDoesNotDestroyIt() = runTest {
        // R-F14: 깨진 파일을 '기록 없음'으로 읽고 다음 정차가 한 건짜리로 덮어썼다
        File(dir, "stops.json").writeText("[{\"s\":1,\"e\":2,\"r\":tr")
        val store = StopHistoryStore(File(dir, "stops.json"), writer())
        assertTrue("빈 기록으로 시작한다", store.load().isEmpty())
        assertEquals("[{\"s\":1,\"e\":2,\"r\":tr", stored("stops.json.corrupt"))
        assertNull(stored("stops.json"))
        val a = stop(10)
        store.append(a)
        runCurrent()
        assertEquals(listOf(a), StopHistoryJson.decode(stored("stops.json")!!))
        assertEquals("따로 둔 파일은 그대로다", "[{\"s\":1,\"e\":2,\"r\":tr", stored("stops.json.corrupt"))
        // 기록 지우기는 따로 둔 파일도 지운다
        store.clear()
        runCurrent()
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun anEmptyHistoryIsNotABrokenOne() = runTest {
        File(dir, "stops.json").writeText("[]")
        assertTrue(StopHistoryStore(File(dir, "stops.json"), writer()).load().isEmpty())
        assertNull(stored("stops.json.corrupt"))
        assertEquals(emptyList<StopLog>(), StopHistoryJson.decode("[]"))
        assertNull(StopHistoryJson.decode(""))
        assertNull(StopHistoryJson.decode("{}"))
    }

    @Test
    fun onlyTheLatestTwoHundredStopsAreKept() = runTest {
        val store = StopHistoryStore(File(dir, "stops.json"), writer())
        repeat(StopHistoryStore.MAX + 5) { store.append(stop(it * 20L)) }
        runCurrent()
        val saved = StopHistoryJson.decode(stored("stops.json")!!)!!
        assertEquals(StopHistoryStore.MAX, saved.size)
        assertEquals(stop(5 * 20L), saved.first())
        assertEquals(saved, store.load())
    }

    @Test
    fun stopsAppendedFromSeveralThreadsAreAllKept() = runTest {
        // P9: 목록은 잠금 안에서만 바꾼다 (코치 스레드와 화면의 기록 지우기가 함께 부를 수 있다)
        val store = StopHistoryStore(File(dir, "stops.json"), writer())
        val start = CountDownLatch(1)
        val threads = (0 until 8).map { t -> thread { start.await(); repeat(20) { i -> store.append(stop(t * 1_000L + i)) } } }
        start.countDown()
        threads.forEach { it.join() }
        runCurrent()
        assertEquals(160, store.load().size)
        assertEquals(160, store.load().toSet().size)
        assertEquals("파일은 마지막 목록이다", store.load(), StopHistoryJson.decode(stored("stops.json")!!))
    }

    // ------------------------------------------------------------ 여정 맥박

    private fun heartbeat(savedAt: Long) = TripHeartbeat(
        savedAt = savedAt, tripStart = 1_762_903_800_000L, lastRestroomAt = mapOf("driver" to 1_762_900_000_000L),
        restroomFallbackAt = 1_762_900_000_000L, continuousDrivingMin = 42.0, park = ParkState(1_762_908_900_000L, doorSeen = false),
    )

    /** 쓰기 스레드에 맡긴 일의 수를 센다 */
    private class CountingDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var jobs = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            jobs++
            inner.dispatch(context, block)
        }
    }

    @Test
    fun heartbeatsThatPileUpAreWrittenOnceWithTheLatest() = runTest {
        // 맥박은 전체 스냅숏이다: 쓰기가 밀리면 마지막 것만 쓴다 (쓰기 스레드에 일이 쌓이지 않는다)
        val counting = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val file = File(dir, "trip.json")
        val store = TripStore(file, CoroutineScope(counting))
        store.save(heartbeat(1))
        store.save(heartbeat(2))
        store.save(heartbeat(3))
        assertEquals("세 번 저장해도 맡긴 쓰기는 하나다", 1, counting.jobs)
        assertNull(stored("trip.json"))
        runCurrent()
        assertEquals(heartbeat(3), store.load())
        store.save(heartbeat(4))
        assertEquals(2, counting.jobs)
        runCurrent()
        assertEquals(heartbeat(4), TripStore(file, writer()).load())
    }

    @Test
    fun aBrokenHeartbeatReadsAsNoTrip() = runTest {
        File(dir, "trip.json").writeText("{\"savedAt\":")
        assertNull("새 여정으로 시작한다", TripStore(File(dir, "trip.json"), writer()).load())
        assertNull("파일이 없어도 같다", TripStore(File(dir, "none.json"), writer()).load())
    }

    // ------------------------------------------------------------ 학습

    private val learned = PersonalMemory(
        restroomIntervalsMin = mapOf("driver" to listOf(95.0, 110.5), "kid" to listOf(60.0)),
        promptStats = mapOf("REST_SUGGESTION" to PromptStat(accepted = 3, declined = 1)),
        restroomCensoredMin = mapOf("driver" to listOf(130.0)),
        restroomSuggestedMin = mapOf("kid" to listOf(55.0)),
    )

    @Test
    fun learningSurvivesTheRoundTrip() {
        val store = MemoryStore(File(dir, "memory.json"))
        assertEquals("파일이 없으면 빈 학습", PersonalMemory(), store.load())
        assertTrue(store.save(learned, store.generation))
        assertEquals(learned, MemoryStore(File(dir, "memory.json")).load())
        assertEquals(learned, MemoryJson.decode(MemoryJson.encode(learned)))
    }

    @Test
    fun brokenLearningIsKeptAsideAndClearedLearningStaysCleared() {
        File(dir, "memory.json").writeText("{\"restroomIntervalsMin\":{\"driver\":[95.0,")
        val store = MemoryStore(File(dir, "memory.json"))
        assertEquals(PersonalMemory(), store.load())
        assertEquals("{\"restroomIntervalsMin\":{\"driver\":[95.0,", stored("memory.json.corrupt"))
        // 지우기 전에 꺼낸 저장은 지운 뒤에 쓰지 않는다 (AtomicFile의 세대)
        val before = store.generation
        store.clear()
        assertFalse(store.save(learned, before))
        assertEquals(PersonalMemory(), store.load())
        assertTrue(dir.list()!!.isEmpty())
        assertTrue(store.save(learned, store.generation))
        assertEquals(learned, store.load())
    }
}
