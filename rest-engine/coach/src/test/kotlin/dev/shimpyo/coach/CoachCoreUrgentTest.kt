package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.CoreRig
import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.FakeVoice
import dev.shimpyo.coach.fakes.Gyeongbu
import dev.shimpyo.engine.PromptKind
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 급해 흐름을 코어 끝까지 (CoreRig: 서비스와 같은 조립, 실제 엔진, 가상 시간). 규칙 하나하나는 UrgentFlowTest가 지키고,
 * 여기는 화면이 실제로 보내는 명령 순서로 코어·엔진·질문 기계가 함께 맞게 움직이는지 본다.
 * 닫힘 검증(2026-11-12)이 실패하는 테스트로 남긴 것들이다: 리뷰 R-F1·R-F7a~d, UI 닫힘 검증 #7·#9·N4.
 */
class CoachCoreUrgentTest {

    /** 강남역 근처를 시속 30km로 지나는 차 (가까운 화장실: 강남역 공중화장실 140m, 역삼 주유소 700m) */
    private fun urgentRig(scope: TestScope, voice: FakeVoice = FakeVoice()): CoreRig {
        val rig = CoreRig(scope, voice = voice, settings = FakeSettings(listOf(DRIVER)))
        rig.drive(30f)
        rig.fix(37.4979, 127.0276, 70f)
        rig.start()
        rig.run(2_000)
        return rig
    }

    // ------------------------------------------------------------ 닫기

    @Test
    fun dismissingRightAfterTheResultsAppearSaysNothing() = runTest {
        // 결과가 나온 틱과 엔진에 급해를 알리는 다음 틱 사이(1초 안)에 닫았다. 예전에는 쌓아 둔 급해 의도가 남아 닫은 뒤에 엔진이
        // 급해 답을 말했다 (리뷰 R-F7b, UI 닫힘 검증 #7). 엔진에는 요청이 살아 있을 때만 알린다
        val rig = urgentRig(this)
        rig.command(UiCommand.Urgent(5L))
        assertEquals("전제: 명령에 바로 결과가 나온다", UrgentState.READY, rig.state.urgentState)
        rig.command(UiCommand.DismissUrgent(5L))
        rig.run(5_000)
        assertTrue("닫은 뒤 말하지 않는다: said=${rig.voice.said} asked=${rig.voice.asked}", rig.voice.said.isEmpty() && rig.voice.asked.isEmpty())
        assertTrue("닫은 뒤 급해 카드가 없다", rig.state.prompt?.kind != PromptKind.URGENT_RESULT)
    }

    @Test
    fun aStopRequestedEarlierDoesNotCloseTheUrgentScreenWhenItCompletes() = runTest {
        // 계획 화면·마지막 기회 카드로 보낸 들르기가 내비 확인을 기다리는 동안 급해를 열었다. 예전에는 어떤 들르기든 성공하면 급해 요청을
        // 끝내서, 앞 들르기가 확인되는 순간 고르지도 않은 급해 화면이 닫혔다 (리뷰 R-F7a). 이 요청의 목록에서 고른 곳만 요청을 끝낸다
        val rig = urgentRig(this)
        rig.fakeNavi.latencyMs["startRoute"] = 20_000L
        rig.command(UiCommand.GoTo(Gyeongbu.oksan))
        rig.run(2_000)
        rig.command(UiCommand.Urgent(8L))
        assertEquals("전제: 급해 결과가 떠 있다", UrgentState.READY, rig.state.urgentState)
        rig.runUntil(40_000) { rig.state.goToResult != null } ?: error("앞 들르기 결과 없음")
        assertEquals("oksan", rig.state.goToResult!!.stop.id)
        assertEquals("급해 목록에서 아무것도 고르지 않았다", UrgentState.READY, rig.state.urgentState)
    }

    // ------------------------------------------------------------ 다시 말하기·엔진 초기화

    @Test
    fun sayingImUrgentAgainWhileTheRequestIsOpenIsAnsweredAgain() = runTest {
        // 급해 요청이 열려 있는 동안(화면을 닫지 않았다) "화장실 급해"를 다시 말했다. 예전에는 목록만 다시 찾고 엔진에는 요청마다
        // 한 번만 알려서 두 번째 말에 아무 대답이 없었다 (리뷰 R-F7c). 다시 찾은 결과로 엔진에 다시 알려 1등 장소를 다시 묻는다
        val rig = urgentRig(this)
        rig.voice.listens += listOf("화장실 급해", "화장실 급해")
        rig.command(UiCommand.Listen)
        rig.run(60_000) // 첫 급해 질문은 대답 없이 수명(45초)을 다한다. 요청은 열려 있다
        assertEquals("전제: 요청이 열려 있다", UrgentState.READY, rig.state.urgentState)
        assertTrue("전제: 첫 번째 말에는 급해 질문을 했다", rig.voice.asked.any { "바로 안내할까요" in it })
        val spoken = rig.voice.asked.size + rig.voice.said.size
        rig.command(UiCommand.Listen)
        rig.run(10_000)
        assertTrue("두 번째 \"화장실 급해\"에 아무 말도 하지 않는다", rig.voice.asked.size + rig.voice.said.size > spoken)
    }

    @Test
    fun clearingRecordsWhileTheUrgentScreenIsOpenStillGetsTheEnginesQuestion() = runTest {
        // 결과가 뜬 직후 엔진의 주행 상태가 새로 시작됐다(기록 지우기, 새 여정): 질문 초기화가 쌓아 둔 급해 의도를 버렸는데 요청은
        // 엔진에 알렸다고 믿어, 화면에는 목록이 떠 있고 엔진은 급해를 모르는 채로 남았다 (리뷰 R-F7d). 새 엔진에 다시 알린다
        val rig = urgentRig(this)
        rig.command(UiCommand.Urgent(4L))
        assertEquals("전제: 명령에 바로 결과가 나온다", UrgentState.READY, rig.state.urgentState)
        rig.command(UiCommand.ClearRecords)
        rig.run(2_000)
        assertEquals("요청은 그대로 열려 있다", UrgentState.READY, rig.state.urgentState)
        assertEquals("새 엔진도 급해를 알고 1등 장소를 묻는다", PromptKind.URGENT_RESULT, rig.state.prompt?.kind)
    }

    // ------------------------------------------------------------ 고른 곳 (UI 닫힘 검증 #9)

    @Test
    fun aResultPickedBeforeTheEnginesUrgentQuestionExistsIsNotAskedAboutAgain() = runTest {
        // 급해 결과는 명령에 바로 뜨고 엔진의 급해 질문은 다음 틱(1초 안)에 뜬다. 그 사이에 1순위 카드를 누르면 화면은 질문이 없으니
        // GoTo만 보낸다. 예전에는 들르기가 성공해 급해가 끝나도 쌓아 둔 급해 의도가 다음 틱에 엔진으로 가서, 방금 안내를 시작한 그곳을
        // "…바로 안내할까요?"로 물었다('응'이면 같은 곳으로 내비 명령이 한 번 더 간다)
        val rig = urgentRig(this)
        rig.command(UiCommand.Urgent(3L))
        assertEquals("전제: 위치를 알면 명령에 바로 결과가 뜬다", UrgentState.READY, rig.state.urgentState)
        assertNull("전제: 엔진의 급해 질문은 아직 없다", rig.state.prompt)
        val first = rig.state.urgent!!.first().stop
        rig.command(UiCommand.GoTo(first))
        rig.runUntil(10_000) { rig.state.goToResult != null } ?: error("들르기 결과 없음: ${rig.log.lines.takeLast(10)}")
        assertEquals(GoToOutcome.ROUTE_STARTED, rig.state.goToResult!!.outcome)
        rig.run(10_000)
        assertTrue("이미 안내를 시작한 곳을 다시 묻는다: ${rig.voice.asked}", rig.voice.asked.none { "바로 안내할까요" in it })
        assertTrue("고른 뒤에 급해 질문 카드가 뜬다", rig.state.prompt?.kind != PromptKind.URGENT_RESULT)
    }

    @Test
    fun aPickStillWaitingForTheNaviIsNotAskedAboutEither() = runTest {
        // 같은 흐름인데 내비 확인이 오래 걸린다(에뮬레이터 19.5초): 그동안 요청은 살아 있지만 엔진에 급해 질문을 시키지 않는다.
        // 실패하면 요청은 그대로이고, 그때 엔진에 알려 다른 곳을 묻게 한다
        val rig = urgentRig(this)
        rig.fakeNavi.latencyMs["startRoute"] = 20_000L
        rig.fakeNavi.faults["startRoute"] = dev.shimpyo.coach.fakes.FakeNaviPort.Fault.THROW
        rig.command(UiCommand.Urgent(3L))
        rig.command(UiCommand.GoTo(rig.state.urgent!!.first().stop))
        rig.run(15_000)
        assertTrue("내비가 확인하는 동안 묻지 않는다: ${rig.voice.asked}", rig.voice.asked.isEmpty())
        assertEquals(UrgentState.READY, rig.state.urgentState)
        rig.runUntil(60_000) { rig.state.goToResult != null } ?: error("들르기 결과 없음")
        assertEquals(GoToOutcome.FAILED, rig.state.goToResult!!.outcome)
        assertEquals("실패하면 급해 화면은 그대로다 (다시 고를 수 있다)", UrgentState.READY, rig.state.urgentState)
        rig.run(3_000)
        assertTrue("이제 엔진이 급해 질문을 한다: ${rig.voice.asked}", rig.voice.asked.any { "바로 안내할까요" in it })
    }

    @Test
    fun aListedResultOtherThanTheEnginesTopIsKeptAsTheChosenStop() = runTest {
        // 목록에서 엔진 질문(1등)과 다른 곳을 고르면 화면은 GoTo(고른 곳) + Answer(급해 질문, 거절)을 보낸다 (ContentActions.pick).
        // 예전에는 엔진이 거절만 알고 고른 곳을 몰라서, 30초 뒤 "화장실 여유가 거의 없어요. … 들를까요?"를 다시 묻고 추천도 다른 곳으로
        // 넘어갔다 (2026-11-12 에뮬레이터: 급해 목록에서 기흥휴게소를 고른 뒤 엔진이 다른 주유소를 다시 물었다).
        // 고른 것이 급해 질문의 대답이다: 질문은 거절 없이 끝나고, 내비가 확인하면 고른 곳을 엔진에 '들르기로 한 곳'으로 알린다
        val rig = urgentRig(this)
        rig.command(UiCommand.Urgent(3L))
        rig.run(2_000)
        val question = checkNotNull(rig.state.prompt) { "엔진의 급해 질문이 없다" }
        assertEquals(PromptKind.URGENT_RESULT, question.kind)
        val second = rig.state.urgent!![1].stop
        assertNotEquals("전제: 2순위는 엔진 질문과 다른 곳", question.stop!!.id, second.id)
        rig.command(UiCommand.GoTo(second))
        assertNull("고르는 순간 급해 질문 카드는 내려간다 (묻던 음성도 멈춘다)", rig.state.prompt)
        rig.command(UiCommand.Answer(question.id, false))
        rig.runUntil(10_000) { rig.state.goToResult != null } ?: error("들르기 결과 없음")
        assertEquals(second.id, rig.state.goToResult!!.stop.id)
        assertEquals("화면은 같은 번호의 IDLE을 보고 닫는다", UrgentState.IDLE, rig.state.urgentState)
        val note = rig.accepted.notes.single()
        assertEquals("고른 곳을 엔진에 알린다", second.id, note.stopId)
        assertEquals(setOf(dev.shimpyo.engine.Purpose.RESTROOM), note.purposes)
        val asked = rig.voice.asked.size
        rig.run(60_000)
        assertEquals("고른 곳으로 가는 1분 동안 정차를 또 묻는다: ${rig.voice.asked.drop(asked)}", asked, rig.voice.asked.size)
        assertEquals("1분 뒤에도 고른 곳이 추천이다", second.id, rig.state.next?.stop?.id)
        assertEquals("한 번만 알린다", 1, rig.accepted.notes.size)
    }

    @Test
    fun closingTheUrgentScreenWhileTheNaviStillConfirmsKeepsTheChosenStop() = runTest {
        // 예전에는 고른 곳을 엔진 후보에 남기는 고정이 들르기 결과가 올 때 급해 요청이 살아 있어야 걸렸다. 내비 확인(2026-11-12
        // 에뮬레이터: 경유지 추가 19.5초)을 기다리다 [닫기]를 누르면 고정이 걸리지 않아, 길안내는 시작됐는데 엔진은 1분 뒤 그곳을 잊고
        // 다른 곳에 들르겠냐고 다시 물었다. 들르기를 시작할 때부터 후보에 넣는다
        val rig = urgentRig(this)
        rig.fakeNavi.latencyMs["startRoute"] = 20_000L
        rig.command(UiCommand.Urgent(3L))
        rig.run(2_000)
        val question = checkNotNull(rig.state.prompt) { "엔진의 급해 질문이 없다" }
        val chosen = question.stop!!.id
        rig.command(UiCommand.Answer(question.id, true)) // 1순위 카드를 눌렀다 (ContentActions.pick)
        rig.run(13_000)
        assertNull("전제: 내비가 아직 확인 중이다", rig.state.goToResult)
        rig.command(UiCommand.DismissUrgent(3L))
        rig.runUntil(20_000) { rig.state.goToResult != null } ?: error("들르기 결과 없음")
        assertEquals(GoToOutcome.ROUTE_STARTED, rig.state.goToResult!!.outcome)
        val asked = rig.voice.asked.size
        rig.run(90_000)
        assertEquals("길안내를 시작한 곳이 1분 30초 뒤에도 추천이다", chosen, rig.state.next?.stop?.id)
        assertEquals("그곳으로 가는 중에 다른 정차를 묻는다: ${rig.voice.asked.drop(asked)}", asked, rig.voice.asked.size)
    }

    @Test
    fun theUrgentStopIsNoLongerRecommendedOnceTheCarIsFarPastIt() = runTest {
        // 급해에서 수락한 곳은 직선거리만 아는 후보로 매 틱 엔진에 다시 들어간다. 직선거리는 지나쳐도 0보다 크므로 엔진은 그곳을
        // '아직 앞에 있다'고 보고 90분까지 들고 있었고, 고정은 엔진이 추천을 멈춰야만 풀렸다: 들르지 않고 지나치면 90분 동안 추천이 뒤에
        // 있는 그곳에 묶이고 엔진은 다른 질문도 내지 않았다 (리뷰 R-F1). 가장 가까웠던 때보다 300m 넘게 멀어지면 고정을 푼다
        val rig = urgentRig(this, FakeVoice(answer = { if ("바로 안내할까요" in it) "응" else null }))
        rig.command(UiCommand.Urgent(3L))
        rig.runUntil(20_000) { rig.state.goToResult != null } ?: error("결과 없음: ${rig.log.lines.takeLast(10)}")
        rig.run(30_000)
        assertEquals("전제: 수락한 곳을 추천한다", "gangnam-wc", rig.state.next?.stop?.id)
        // 들르지 않고 20km 넘게 지나갔다
        rig.drive(80f)
        rig.fix(37.60, 127.25, 70f)
        rig.run(10 * 60_000L)
        assertNotEquals("10분 전에 22km 뒤로 지나친 곳을 아직 '들르기로 한 곳'으로 추천한다", "gangnam-wc", rig.state.next?.stop?.id)
    }

    // ------------------------------------------------------------ 요청 번호 (UI 닫힘 검증 N4)

    @Test
    fun coreOpenedUrgentIdsAreNotReusedByTheNextCore() = runTest {
        // 화면은 닫은 급해 번호를 최근 8개까지 들고 있고(액티비티 저장 상태라 프로세스가 죽었다 살아나도 남는다) 번호가 다시 쓰이지
        // 않는다고 가정한다. 예전에는 코어가 음성 "화장실 급해"로 여는 번호를 코어마다 -1부터 다시 세서, 음성으로 연 급해를 한 번 닫은 뒤
        // 서비스가 다시 뜨면 다음 음성 급해(-1)는 '닫은 번호'라 화면이 열리지 않았다. 번호는 코어를 만든 때(경과 시간)에서 시작한다
        val ids = ArrayList<Long>()
        repeat(2) {
            val rig = urgentRig(this)
            rig.voice.listens += "화장실 급해"
            rig.command(UiCommand.Listen)
            rig.run(2_000)
            assertEquals("전제: 코어가 급해 요청을 열었다", UrgentState.READY, rig.state.urgentState)
            ids += rig.state.urgentRequestId
        }
        assertTrue("음성으로 연 요청은 음수 번호", ids.all { it < 0 })
        assertNotEquals("다시 뜬 코어가 앞 코어의 번호를 또 쓴다: $ids", ids[0], ids[1])
    }
}
