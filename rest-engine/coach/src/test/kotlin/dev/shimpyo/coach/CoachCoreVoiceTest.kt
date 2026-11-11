package dev.shimpyo.coach

import dev.shimpyo.coach.fakes.CoreRig
import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeInterpreter
import dev.shimpyo.coach.fakes.FakeNaviPort
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.FakeVoice
import dev.shimpyo.coach.fakes.Gyeongbu
import dev.shimpyo.coach.fakes.InMemoryMemory
import dev.shimpyo.coach.fakes.KID
import dev.shimpyo.engine.Level
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.PromptKind
import dev.shimpyo.engine.VoiceIntent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 말하기·듣기를 코어 끝까지 (CoreRig: 실제 엔진, 가짜 음성). 작업 관리 규칙은 VoiceDeskTest, 질문 기계의 규칙은 PromptMachineTest.
 * 닫힘 검증(2026-11-12)이 실패하는 테스트로 남긴 것들이다: 리뷰 R-F3·R-F4·R-F8·R-F9·R-F11, UI 닫힘 검증 #12.
 */
class CoachCoreVoiceTest {

    /** 귀성길 시연에서 옥산휴게소 질문에 [reply]로 한 번만 답하는 음성 */
    private fun replyOnce(reply: String): FakeVoice {
        var first = true
        return FakeVoice(answer = { q -> if ("옥산" in q && first) reply.also { first = false } else null })
    }

    private fun holiday(rig: CoreRig) {
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
    }

    // ------------------------------------------------------------ 카드에 한 말은 대답 하나 (리뷰 R-F3)

    @Test
    fun aSpokenYesThatNamesThePlaceIsOneAnswerNotAlsoANewRequest() = runTest {
        // 카드 질문에 "응 휴게소 들르자"라고 답했다. 예전에는 수락(들르기)과 함께 같은 말이 '쉬고 싶어'로 다시 전달돼, 엔진이 그것을
        // 새 요청으로 보고 방금 수락한 운전자에게 "…에서 쉬어 갈까요?"를 또 물었다
        val rig = CoreRig(this, voice = replyOnce("응 휴게소 들르자"))
        holiday(rig)
        rig.runUntil(150_000) { rig.state.goToResult != null } ?: error("들르기 결과 없음: ${rig.log.lines.takeLast(10)}")
        assertEquals(GoToOutcome.ROUTE_STARTED, rig.state.goToResult!!.outcome)
        rig.run(20_000)
        assertEquals("수락한 뒤에는 다시 묻지 않는다: ${rig.voice.asked}", 1, rig.voice.asked.size)
    }

    @Test
    fun answeringNoNotUrgentDoesNotOpenUrgentMode() = runTest {
        // "아니 안 급해"는 거절이다. 예전에는 같은 말이 '급해'로도 읽혀 코어가 급해 요청을 열고 엔진이 급해 질문을 또 물었다
        val rig = CoreRig(this, voice = replyOnce("아니 안 급해"))
        holiday(rig)
        rig.runUntil(150_000) { rig.voice.asked.isNotEmpty() } ?: error("질문이 없다")
        rig.run(6_000)
        assertEquals("거절했는데 급해 요청이 열렸다 (번호 ${rig.state.urgentRequestId})", UrgentState.IDLE, rig.state.urgentState)
        assertEquals("거절 뒤에 또 묻는다: ${rig.voice.asked}", 1, rig.voice.asked.size)
        assertNull("거절한 카드는 내려간다", rig.state.prompt)
    }

    // ------------------------------------------------------------ 묻는 말은 필요가 아니다 (리뷰 R-F2)

    @Test
    fun askingHowLongToTheRestroomDoesNotMarkANeedOrTeachTheModel() = runTest {
        // 예전에는 엔진 계약의 HowLong 예문 "화장실까지 얼마나 남았어?"가 IntentParser에서 OccupantNeed(null, RESTROOM)로 읽혔다
        // ('화장실'을 '얼마나 남'보다 먼저 봤다). 코어는 그대로 엔진에 넘기고, 엔진은 운전자가 화장실이 필요하다고 말한 것으로 봤다:
        // 게이지가 70분/OK에서 0분/NOW로 뛰고, 80분 간격이 관측으로 학습 파일에 저장되고, 답 대신 "다음 화장실은 …에 들를까요?"를 물었다.
        // 말 해석 규칙은 IntentParserTest가 지킨다. 여기서는 마이크에서 엔진·학습 파일까지 이어서 본다
        val memory = InMemoryMemory()
        val rig = CoreRig(this, memory = memory, settings = FakeSettings(listOf(DRIVER)))
        rig.voice.listens += "화장실까지 얼마나 남았어?"
        rig.drive(100f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(20 * 60_000L)
        assertEquals("전제: 여유가 있다", Level.OK, rig.state.restroom?.level)
        rig.command(UiCommand.Listen)
        rig.run(10_000)
        assertEquals("물어봤을 뿐인데 화장실이 필요하다고 말한 것이 됐다 (${rig.state.restroom?.reasons?.map { it.code }})", Level.OK, rig.state.restroom?.level)
        assertTrue("물어봤을 뿐인데 화장실 간격을 배웠다: ${memory.stored.restroomIntervalsMin}", memory.stored.restroomIntervalsMin.isEmpty())
        assertTrue("남은 시간을 답한다: said=${rig.voice.said} asked=${rig.voice.asked}", rig.voice.said.any { "여유" in it })
    }

    // ------------------------------------------------------------ 서 있을 때 한 말 (엔진 닫힘 검증 E6)

    @Test
    fun aRestroomRequestSpokenWhileParkedIsAnsweredWithACardThatStays() = runTest {
        // 꽉 막힌 정체에서 P로 서 있을 때 "아이가 화장실 가고 싶대"라고 했다. 엔진은 서 있어도 가장 가까운 화장실을 묻는다
        // (마지막 기회와 같은 종류의 질문, Prompt.userRequested). 예전에는 코치가 '정차 중 주행 질문'으로 거둬서 게이지만 NOW가 되고
        // 카드도 말도 없었다
        val rig = CoreRig(this)
        rig.voice.listens += "아이가 화장실 가고 싶대"
        rig.park()
        rig.fix(37.0, 127.19) // 입장졸음쉼터 10km 전에 서 있다
        rig.start()
        rig.run(5_000)
        assertNull("전제: 서 있는 동안 엔진은 먼저 묻지 않는다", rig.state.prompt)
        rig.command(UiCommand.Listen)
        rig.runUntil(5_000) { rig.state.prompt != null } ?: error("답이 없다: said=${rig.voice.said} asked=${rig.voice.asked}")
        val card = rig.state.prompt!!
        assertEquals(PromptKind.LAST_CHANCE, card.kind)
        assertTrue("말한 것에 대한 답이다", card.userRequested)
        assertNotNull("들를 곳을 묻는다", card.stop)
        assertTrue("말로도 묻는다: ${rig.voice.asked}", rig.voice.asked.any { "화장실" in it && "들를까요" in it })
        rig.run(20_000)
        assertEquals("서 있는 동안 카드가 남아 있다", card.id, rig.state.prompt?.id)
        assertEquals("전제: 계속 서 있다", true, rig.state.parked)
        // [들르기]를 누르면 서 있는 채로 내비에 넣는다
        rig.command(UiCommand.Answer(card.id, true))
        rig.runUntil(10_000) { rig.state.goToResult != null } ?: error("들르기 결과 없음")
        assertEquals(card.stop!!.id, rig.state.goToResult!!.stop.id)
    }

    @Test
    fun parkedNextToARestroomTheAnswerIsSpokenOrShown() = runTest {
        // 선 곳 가까이에 화장실이 있으면 엔진은 안내(INFO)로 답한다: 서 있어도 말하고, 음성 안내를 껐으면 화면 알림으로 낸다.
        // (가짜 내비의 경로 거리는 목적지와 상관없이 10km라서 여기서는 경로 보정을 끈다: 후보는 위치 기준 직선거리로 온다)
        fun parkedAtManghyang(settings: FakeSettings): CoreRig {
            val rig = CoreRig(this, settings = settings)
            rig.fakeNavi.faults["tripEstimate"] = FakeNaviPort.Fault.THROW
            rig.voice.listens += "아이가 화장실 가고 싶대"
            rig.park()
            rig.fix(Gyeongbu.manghyang.lat, Gyeongbu.manghyang.lon)
            rig.start()
            rig.run(5_000)
            rig.command(UiCommand.Listen)
            return rig
        }

        val spoken = parkedAtManghyang(FakeSettings(listOf(DRIVER, KID)))
        spoken.runUntil(8_000) { spoken.voice.said.any { "가까이에 화장실이 있어요" in it } }
            ?: error("답이 없다: said=${spoken.voice.said} asked=${spoken.voice.asked} ahead=${spoken.state.ahead.map { it.id to it.straightDistanceM }}")
        assertTrue("서 있는 곳의 이름으로 답한다: ${spoken.voice.said}", spoken.voice.said.any { "망향휴게소" in it })
        assertNull("안내는 카드를 띄우지 않는다", spoken.state.prompt)

        val quiet = parkedAtManghyang(FakeSettings(listOf(DRIVER, KID)).also { it.voiceEnabled = false })
        quiet.runUntil(8_000) { quiet.state.notice != null } ?: error("알림이 없다")
        assertTrue(quiet.state.notice!!, "가까이에 화장실이 있어요" in quiet.state.notice!!)
        assertTrue("말하지 않는다", quiet.voice.said.isEmpty())
    }

    @Test
    fun theEnginesOwnLastChanceIsStillWithdrawnWhenTheCarParks() = runTest {
        // 엔진이 먼저 건 주행 질문은 차를 세우면 거둔다 (서 있는 사람에게 '앞 휴게소에 들를까요'는 뜻이 없다): 말한 것의 답만 남긴다
        val rig = CoreRig(this)
        rig.drive(100f)
        rig.start()
        rig.command(UiCommand.Demo("holiday"))
        rig.runUntil(150_000) { rig.state.prompt?.kind == PromptKind.LAST_CHANCE } ?: error("카드가 뜨지 않았다")
        assertFalse("엔진이 먼저 건 질문이다", rig.state.prompt!!.userRequested)
        rig.park()
        rig.run(2_000)
        assertNull("세우면 거둔다", rig.state.prompt)
    }

    // ------------------------------------------------------------ 음성 안내 끔 (리뷰 R-F4, UI 닫힘 검증 #12)

    @Test
    fun withVoiceGuidanceOffASpokenQuestionIsAnsweredOnTheScreenForAFewSeconds() = runTest {
        // 화면은 음성 안내를 꺼도 마이크로 듣게 한다 ('말하기 (대답은 화면으로)'). 예전에는 엔진의 답(INFO: 말만 하는 안내)이
        // 소리로도 화면으로도 나가지 않았다. 말할 수 없으면 CoachUiState.notice로 낸다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER), voiceEnabled = false))
        rig.voice.listens += "얼마나 남았어"
        rig.drive(100f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(3_000)
        rig.command(UiCommand.Listen)
        val shownAfter = rig.runUntil(5_000) { rig.state.notice != null } ?: error("\"얼마나 남았어\"의 답이 화면에 나오지 않았다")
        assertTrue("남은 시간을 답한다: ${rig.state.notice}", "여유" in rig.state.notice!!)
        assertTrue("말하지 않는다", rig.voice.said.isEmpty() && rig.voice.asked.isEmpty())
        assertNull("안내는 카드가 아니다", rig.state.prompt)
        // 약 6초 보이고 코치가 거둔다
        rig.run(VoiceDesk.NOTICE_MS - 2_000)
        assertNotNull("${shownAfter}ms에 뜬 알림이 4초 뒤에도 보인다", rig.state.notice)
        rig.run(4_000)
        assertNull(rig.state.notice)
    }

    @Test
    fun withVoiceGuidanceOffNotUnderstoodIsAlsoShown() = runTest {
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER), voiceEnabled = false))
        rig.voice.listens += "창문 좀 열어 줄래"
        rig.drive(60f)
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.Listen)
        rig.runUntil(5_000) { rig.state.notice != null } ?: error("못 알아들었다는 알림이 없다")
        assertTrue(rig.state.notice!!, "못 알아들었어요" in rig.state.notice!!)
        assertTrue(rig.voice.said.isEmpty())
    }

    @Test
    fun withVoiceGuidanceOnAnswersAreSpokenNotShown() = runTest {
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.voice.listens += "얼마나 남았어"
        rig.drive(100f)
        rig.fix(37.40, 127.10)
        rig.start()
        rig.run(3_000)
        rig.command(UiCommand.Listen)
        rig.run(5_000)
        assertTrue("말로 답한다: ${rig.voice.said}", rig.voice.said.any { "여유" in it })
        assertNull("말했으면 화면 알림은 없다", rig.state.notice)
    }

    // ------------------------------------------------------------ 탑승자 말하기 (DescribeOccupants)

    @Test
    fun describingOccupantsAsksListensAppliesAndConfirms() = runTest {
        val rig = CoreRig(this, voice = FakeVoice(answer = { "일곱 살 아이 하나랑 강아지" }), settings = FakeSettings(listOf(DRIVER)))
        rig.park()
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.DescribeOccupants)
        rig.command(UiCommand.DescribeOccupants) // 두 번 눌러도 한 번만 묻는다
        rig.run(5_000)
        assertEquals(1, rig.voice.asked.count { "누가 함께 타나요" in it })
        assertEquals(listOf(OccupantType.ADULT, OccupantType.CHILD, OccupantType.DOG), rig.state.occupants.map { it.type })
        assertEquals("실제 세계의 탑승자는 설정에 저장한다", rig.state.occupants, rig.settings.occupants)
        assertTrue("무엇으로 맞췄는지 말한다: ${rig.voice.said}", rig.voice.said.any { "7살 아이" in it && "반려견" in it && "맞췄어요" in it })
    }

    @Test
    fun wordsTheRulesCannotReadGoToTheInterpreterAndGibberishChangesNothing() = runTest {
        val llm = FakeInterpreter(people = listOf(DRIVER, KID))
        val rig = CoreRig(this, voice = FakeVoice(answer = { "우리 집 막둥이" }), settings = FakeSettings(listOf(DRIVER)), interpreter = llm)
        rig.park()
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.DescribeOccupants)
        rig.run(5_000)
        assertEquals("규칙이 못 읽은 말은 해석기에 맡긴다", listOf("driver", "kid"), rig.state.occupants.map { it.id })

        llm.people = null
        rig.command(UiCommand.DescribeOccupants)
        rig.run(8_000)
        assertEquals("해석기도 못 읽으면 탑승자를 바꾸지 않는다", listOf("driver", "kid"), rig.state.occupants.map { it.id })
        assertTrue("못 알아들었다고 알린다: ${rig.voice.said}", rig.voice.said.any { "못 알아들었어요" in it })
    }

    @Test
    fun describeOccupantsWithVoiceGuidanceOffListensInsteadOfDoingNothing() = runTest {
        // 설정의 '음성 안내'는 말하기만 끈다(마이크는 듣는다). 예전에는 [말로 설정]이 음성 안내 켬 + TTS + STT가 아니면 말없이 돌아가서
        // 눌러도 아무 일도 없었다. 음성 안내를 껐으면 묻는 말은 화면 알림으로 내고 듣기만 한다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER), voiceEnabled = false))
        rig.voice.listens += "일곱 살 아이 하나"
        rig.park()
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.DescribeOccupants)
        assertTrue("무엇을 말하면 되는지 화면에 보인다: ${rig.state.notice}", rig.state.notice.orEmpty().contains("누가 함께 타나요"))
        rig.run(12_000)
        assertTrue("음성 안내를 껐으니 말하지 않는다", rig.voice.asked.isEmpty() && rig.voice.said.isEmpty())
        assertEquals("들은 말로 탑승자를 맞춘다", listOf(OccupantType.ADULT, OccupantType.CHILD), rig.state.occupants.map { it.type })
    }

    @Test
    fun describeOccupantsWithoutSpeechRecognitionSaysWhyOnTheScreen() = runTest {
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.voice.sttReady = false
        rig.park()
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.DescribeOccupants)
        assertTrue("왜 안 되는지 알린다: ${rig.state.notice}", rig.state.notice.orEmpty().contains("음성 인식을 쓸 수 없어요"))
        rig.run(3_000)
        assertTrue("묻지 않는다 (들을 수 없다)", rig.voice.asked.isEmpty())
    }

    @Test
    fun describingOnlyAHealthTypeWithoutConsentKeepsThePassengersAndSaysNoEmptySentence() = runTest {
        // 동의 없이 "임산부 한 명"이라고 말했다. 예전에는 걸러진 목록(운전자뿐)을 그대로 적용해 타고 있던 아이가 지워지고
        // "로 맞췄어요"라고 말했다 (리뷰 R-F9). 받아들인 사람이 없으면 탑승자를 그대로 두고 동의 안내만 한다
        val rig = CoreRig(this, voice = FakeVoice(answer = { "임산부 한 명" }), settings = FakeSettings(listOf(DRIVER, KID), healthConsent = false))
        rig.park()
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.DescribeOccupants)
        rig.run(10_000)
        assertTrue("동의 안내를 말한다 ${rig.voice.said}", rig.voice.said.any { "동의" in it })
        assertTrue("주어 없는 문장을 말한다: ${rig.voice.said}", rig.voice.said.none { "로 맞췄어요" in it })
        assertEquals("받아들인 것이 없으면 탑승자를 바꾸지 않는다", listOf("driver", "kid"), rig.state.occupants.map { it.id })
        assertEquals(0, rig.settings.occupantWrites)
    }

    @Test
    fun aHealthTypeAmongOthersIsLeftOutAndTheRestApplied() = runTest {
        val rig = CoreRig(this, voice = FakeVoice(answer = { "할머니랑 임산부" }), settings = FakeSettings(listOf(DRIVER), healthConsent = false))
        rig.park()
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.DescribeOccupants)
        rig.run(10_000)
        assertEquals(listOf(OccupantType.ADULT, OccupantType.ELDERLY), rig.state.occupants.map { it.type })
        assertTrue("동의 안내와 맞춘 결과를 함께 말한다: ${rig.voice.said}", rig.voice.said.any { "동의" in it && "어르신으로 맞췄어요" in it })
    }

    // ------------------------------------------------------------ 세계 전환과 음성 결과 (리뷰 R-F8)

    @Test
    fun occupantsDescribedDuringADemoAreNotWrittenToTheRealSettingsWhenTheDemoEndsFirst() = runTest {
        // 예전에는 세계 전환이 카드의 묻기·듣기만 취소해서, 탑승자 말하기와 LLM 해석의 결과가 전환 뒤에 도착해 그때의 세계에 적용됐다:
        // 시연 중에 물은 탑승자가 시연을 끈 뒤 실제 설정에 저장됐다. 세계가 바뀌면 음성 작업을 모두 멈추고, 결과에는 세계 세대를 실어
        // 세대가 다른 결과를 버린다
        val rig = CoreRig(this, voice = FakeVoice(answer = { "할머니랑 강아지" }, speakMs = 3_000), settings = FakeSettings(listOf(DRIVER)))
        rig.park()
        rig.start()
        rig.command(UiCommand.Demo("stop"))
        rig.run(2_000)
        rig.command(UiCommand.DescribeOccupants) // 질문을 말하는 데 3초
        rig.run(1_000)
        rig.command(UiCommand.Demo(null))
        rig.run(6_000)
        assertEquals("시연 중에 한 말이 실제 설정에 저장됐다: ${rig.settings.occupants.map { it.label }}", 0, rig.settings.occupantWrites)
        assertEquals(listOf("driver"), rig.state.occupants.map { it.id })
    }

    @Test
    fun aResultAlreadyInTheMailboxWhenTheWorldChangesIsDroppedByItsGeneration() = runTest {
        // 취소로 막을 수 없는 경우: 결과 메시지가 세계 전환 명령보다 먼저 우편함에 들어갔다. 세대가 달라 버린다
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.park()
        rig.start()
        rig.run(2_000)
        rig.core.command(UiCommand.Demo("stop"))
        val grandma = Occupant("grandma", OccupantType.ELDERLY, label = "할머니")
        rig.core.send(CoachCore.Msg.OccupantsInterpreted(gen = 1, list = listOf(DRIVER, grandma))) // 실제 세계(세대 1)에서 시작한 해석의 결과
        rig.core.send(CoachCore.Msg.Listened(gen = 1, text = "화장실 급해"))
        rig.run(2_000)
        assertEquals("시연 탑승자 그대로", listOf("driver", "kid"), rig.state.occupants.map { it.id })
        assertEquals("실제 설정에 쓰지 않는다", 0, rig.settings.occupantWrites)
        assertEquals("지난 세계에서 한 말로 급해를 열지 않는다", UrgentState.IDLE, rig.state.urgentState)
    }

    @Test
    fun aWorldSwitchWhileTheInterpreterThinksReturnsTheMicToIdle() = runTest {
        val slow = object : IntentInterpreter {
            override val label = "느린 해석기"
            override suspend fun interpret(text: String, occupants: List<Occupant>, activePromptId: String?): VoiceIntent? {
                kotlinx.coroutines.delay(5_000)
                return VoiceIntent.UrgentRestroom
            }
            override suspend fun occupants(text: String): List<Occupant>? = null
        }
        val rig = CoreRig(this, interpreter = slow, settings = FakeSettings(listOf(DRIVER)))
        rig.voice.listens += "음 뭐랄까"
        rig.park()
        rig.fix(37.4979, 127.0276, 70f)
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.Listen)
        rig.run(2_000)
        assertEquals(VoiceState.THINKING, rig.state.voice)
        rig.command(UiCommand.Demo("stop"))
        assertEquals("세계가 바뀌면 생각하던 것을 멈춘다", VoiceState.IDLE, rig.state.voice)
        rig.run(8_000)
        assertEquals("지난 세계에서 한 말의 해석은 버린다", UrgentState.IDLE, rig.state.urgentState)
    }

    // ------------------------------------------------------------ 효과 실패 (리뷰 R-F11)

    @Test
    fun anInterpreterThatThrowsDoesNotLeaveTheMicThinking() = runTest {
        // 예전에는 해석이 예외를 던지면 결과 메시지가 오지 않아 마이크 표시가 '생각하는 중'에 남고 '못 알아들었어요'도 없었다.
        // 음성 작업은 실패해도 결과를 정확히 한 번 돌려준다 (VoiceDesk)
        val broken = object : IntentInterpreter {
            override val label = "고장 난 해석기"
            override suspend fun interpret(text: String, occupants: List<Occupant>, activePromptId: String?): VoiceIntent? = error("llm crashed")
            override suspend fun occupants(text: String): List<Occupant>? = error("llm crashed")
        }
        val rig = CoreRig(this, interpreter = broken, settings = FakeSettings(listOf(DRIVER)))
        rig.voice.listens += "창문 좀 열어 줄래"
        rig.drive(60f)
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.Listen)
        rig.run(5_000)
        assertNotEquals("해석이 실패했는데 '생각하는 중'에 남는다", VoiceState.THINKING, rig.state.voice)
        assertTrue("못 알아들었다고 알린다: ${rig.voice.said}", rig.voice.said.any { "못 알아들었어요" in it })
        assertNull(rig.state.prompt)
    }

    @Test
    fun anInterpreterThatThrowsOnOccupantsSaysItDidNotUnderstand() = runTest {
        val broken = object : IntentInterpreter {
            override val label = "고장 난 해석기"
            override suspend fun interpret(text: String, occupants: List<Occupant>, activePromptId: String?): VoiceIntent? = null
            override suspend fun occupants(text: String): List<Occupant>? = error("llm crashed")
        }
        val rig = CoreRig(this, voice = FakeVoice(answer = { "우리 집 막둥이" }), interpreter = broken, settings = FakeSettings(listOf(DRIVER)))
        rig.park()
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.DescribeOccupants)
        rig.run(6_000)
        assertTrue("탑승자 해석이 실패해도 대답이 온다: ${rig.voice.said}", rig.voice.said.any { "못 알아들었어요" in it })
        assertEquals(listOf("driver"), rig.state.occupants.map { it.id })
    }

    // ------------------------------------------------------------ 멈추기 (StopVoice)

    @Test
    fun stopVoiceStopsTheOccupantQuestionAndASpokenAnswerAndNothingArrivesLater() = runTest {
        val rig = CoreRig(this, voice = FakeVoice(answer = { "할머니랑 강아지" }, speakMs = 4_000), settings = FakeSettings(listOf(DRIVER)))
        rig.park()
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.DescribeOccupants)
        rig.run(1_000)
        val stops = rig.voice.stops
        rig.command(UiCommand.StopVoice)
        assertEquals("VoicePort.stop을 부른다", stops + 1, rig.voice.stops)
        assertEquals(VoiceState.IDLE, rig.state.voice)
        rig.run(8_000)
        assertEquals("멈춘 말하기의 대답은 적용하지 않는다", listOf("driver"), rig.state.occupants.map { it.id })
        // 멈춘 뒤에는 다시 들을 수 있다 (바쁘지 않다)
        rig.voice.listens += "졸려"
        rig.command(UiCommand.Listen)
        rig.run(2_000)
        assertTrue("듣기가 시작됐다", rig.voice.listens.isEmpty())
    }

    @Test
    fun stopVoiceWithNothingRunningIsHarmless() = runTest {
        val rig = CoreRig(this, settings = FakeSettings(listOf(DRIVER)))
        rig.drive(60f)
        rig.start()
        rig.run(2_000)
        rig.command(UiCommand.StopVoice)
        rig.run(2_000)
        assertEquals(VoiceState.IDLE, rig.state.voice)
        assertNull(rig.state.prompt)
        assertFalse("멈출 것이 없어도 음성은 멈춤 명령을 받는다 (화면이 한 박자 늦어도 안전)", rig.voice.stops == 0)
    }

    @Test
    fun stopVoiceDuringTheCardQuestionKeepsTheCardForAButton() = runTest {
        // 카드가 묻던 중이면 '대답 없음'으로 알려 버튼 창(적어도 10초)을 준다. 묻던 질문을 다시 묻지 않는다
        val rig = CoreRig(this, voice = FakeVoice(speakMs = 6_000))
        holiday(rig)
        rig.runUntil(150_000) { rig.state.prompt?.kind == PromptKind.LAST_CHANCE } ?: error("카드가 뜨지 않았다")
        rig.run(1_000)
        val key = rig.state.prompt!!.id
        rig.command(UiCommand.StopVoice)
        rig.run(5_000)
        assertEquals(key, rig.state.prompt?.id)
        assertEquals(1, rig.voice.asked.size)
        rig.command(UiCommand.Answer(key, false))
        assertNull(rig.state.prompt)
    }
}
