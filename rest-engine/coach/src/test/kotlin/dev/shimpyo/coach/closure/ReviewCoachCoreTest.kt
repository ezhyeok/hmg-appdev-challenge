package dev.shimpyo.coach.closure

import dev.shimpyo.coach.UiCommand
import dev.shimpyo.coach.fakes.CoreRig
import dev.shimpyo.coach.fakes.DRIVER
import dev.shimpyo.coach.fakes.FakeSettings
import dev.shimpyo.coach.fakes.InMemoryMemory
import dev.shimpyo.engine.Level
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 독립 리뷰(2026-11-12)가 찾은 것 가운데 아직 열려 있는 것. 지금 실패한다(고치면 통과). `// OPEN:`이 이유다.
 * 남은 하나는 말 해석(IntentParser, R-F2)이 고쳐지면 통과한다. 코어 쪽에서 닫은 것들은 CoachCoreUrgentTest·CoachCoreVoiceTest·
 * CoachCoreGoToTest로 옮겼다. CoreRig(서비스와 같은 조립, 가상 시간)로 끝까지 돌린다.
 */
class ReviewCoachCoreTest {

    // ------------------------------------------------------------ 물음을 필요로 읽는다 (R-F2, IntentParser 담당)

    @Test
    fun askingHowLongToTheRestroomDoesNotMarkANeedOrTeachTheModel() = runTest {
        // OPEN: 엔진 계약의 HowLong 예문 "화장실까지 얼마나 남았어?"가 IntentParser에서 OccupantNeed(null, RESTROOM)로 읽힌다
        // ('화장실'을 '얼마나 남'보다 먼저 본다, ReviewIntentParserTest). 코어는 그대로 엔진에 넘기고, 엔진은 운전자가 화장실이
        // 필요하다고 말한 것으로 본다: 게이지가 70분/OK에서 0분/NOW로 뛰고, 80분 간격이 관측으로 학습 파일에 저장되고,
        // 답 대신 "다음 화장실은 …에 들를까요?"를 묻는다.
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
}
