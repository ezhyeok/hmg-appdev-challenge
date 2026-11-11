package dev.shimpyo.coach.closure

import dev.shimpyo.coach.voice.IntentParser
import dev.shimpyo.coach.voice.YesNo
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 독립 리뷰(2026-11-12): CoachCore가 모든 말(마이크 버튼, 카드 대답 안의 '다른 요청')을 맡기는 IntentParser에는 테스트가 없다.
 * 지금 실패한다(고치면 통과). 제품 코드는 고치지 않았다.
 */
class ReviewIntentParserTest {
    private val parser = IntentParser()
    private val people = listOf(Occupant("driver", OccupantType.ADULT), Occupant("kid", OccupantType.CHILD, ageYears = 7))

    @Test
    fun howLongToTheRestroomIsAQuestionNotARestroomRequest() {
        // OPEN: 엔진 계약의 HowLong 예문이 "화장실까지 얼마나 남았어?"(Api.kt)인데, 규칙은 '화장실'을 '얼마나 남'보다 먼저 봐서
        // OccupantNeed(null, RESTROOM)를 낸다. 엔진은 답(INFO) 대신 그 사람을 NOW로 올리고 학습 간격을 남기고 정차를 묻는다.
        assertEquals(VoiceIntent.HowLong, parser.parse("화장실까지 얼마나 남았어?", people, activePromptId = null))
    }

    @Test
    fun aNegatedSentenceIsNotTheRequestItNegates() {
        // OPEN: 부정을 보지 않는다. "안 급해"가 급해 요청, "화장실 안 가도 돼"가 화장실 요청이 된다. 카드 질문의 거절 대답은
        // 대개 이런 꼴이라(ReviewCoachCoreTest.answeringNoNotUrgentDoesNotOpenUrgentMode) 거절이 새 요청으로 바뀐다.
        assertEquals(YesNo.NO, parser.yesNo("아니 안 급해"))
        assertNotEquals(VoiceIntent.UrgentRestroom, parser.parse("아니 안 급해", people, activePromptId = null))
        assertNotEquals(
            VoiceIntent.OccupantNeed(null, dev.shimpyo.engine.Purpose.RESTROOM),
            parser.parse("화장실 안 가도 돼", people, activePromptId = null),
        )
    }
}
