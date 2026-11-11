package dev.shimpyo.coach.voice

import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.VoiceIntent
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 규칙 기반 의도 분류 (IntentParser). 표 하나가 규칙 하나다: 말 → 기대하는 의도. 표는 docs/design/coach-layer/voice.md와 같다.
 * 운전자가 실제로 하는 말(띄어쓰기 없는 STT 결과, 부정, 되묻기, 아이·반려견 이야기)을 의도마다 맞는 말과 아닌 말로 넣었다.
 * 회귀: "화장실까지 얼마나 남았어?"가 화장실 요청이 됐다(R-F2), "안 급해"가 급해 요청이 됐다(R-F3).
 */
class IntentParserTest {
    private val parser = IntentParser()

    private val driver = Occupant("driver", OccupantType.ADULT, label = "운전자")
    private val kid = Occupant("kid", OccupantType.CHILD, ageYears = 7, label = "첫째")
    private val kid2 = Occupant("kid2", OccupantType.CHILD, ageYears = 4, label = "둘째")
    private val baby = Occupant("baby", OccupantType.INFANT, label = "아기")
    private val grandma = Occupant("grandma", OccupantType.ELDERLY, label = "할머니")
    private val dog = Occupant("dog", OccupantType.DOG, label = "반려견")

    private val alone = listOf(driver)
    private val withKid = listOf(driver, kid)
    private val twoKids = listOf(driver, kid, kid2)
    private val family = listOf(driver, kid, baby, grandma, dog)

    private val urgent = VoiceIntent.UrgentRestroom
    private fun restroom(who: String? = null) = VoiceIntent.OccupantNeed(who, Purpose.RESTROOM)
    private fun feeding(who: String? = null) = VoiceIntent.OccupantNeed(who, Purpose.FEEDING)
    private fun pet(who: String? = null) = VoiceIntent.OccupantNeed(who, Purpose.PET)
    private val fuel = VoiceIntent.OccupantNeed(null, Purpose.FUEL)

    /** 표 전체를 돌리고 틀린 줄을 모두 모아 한 번에 알린다 */
    private fun <T> table(name: String, rows: List<Pair<String?, T>>, actual: (String?) -> T) {
        val wrong = rows.mapNotNull { (said, expected) ->
            val got = actual(said)
            if (got == expected) null else "  \"$said\" → $got (기대: $expected)"
        }
        assertTrue("$name: ${wrong.size}/${rows.size}줄이 다르다\n${wrong.joinToString("\n")}", wrong.isEmpty())
    }

    private fun parsing(name: String, people: List<Occupant>, vararg rows: Pair<String?, VoiceIntent?>, prompt: String? = null) =
        table(name, rows.toList()) { parser.parse(it, people, activePromptId = prompt) }

    // ------------------------------------------------------------ 급해

    @Test
    fun urgentRestroom() = parsing(
        "급해", family,
        "화장실 급해" to urgent,
        "급해" to urgent,
        "나 급해" to urgent,
        "급해요" to urgent,
        "화장실 급한데" to urgent,
        "화장실이 급합니다" to urgent,
        "미안 급해" to urgent, // '미안'의 안은 부정이 아니다
        "못 참겠어" to urgent,
        "더는 못 참아" to urgent,
        "쌀 것 같아" to urgent,
        "쌀 거 같아" to urgent,
        "지릴 것 같아" to urgent,
        "화장실 빨리" to urgent,
        "빨리 화장실 좀" to urgent,
        "급똥이야" to urgent,
        "아이가 화장실 급하대" to urgent,
        "화장실 급한데 얼마나 남았어" to urgent, // 필요를 말했으면 되묻기보다 필요가 먼저다
        "화장실급해" to urgent, // 띄어쓰기 없는 STT
    )

    @Test
    fun notUrgent() = parsing(
        "급하지 않다", family,
        "안 급해" to null,
        "아니 안 급해" to null,
        "급하지 않아" to null,
        "급하진 않아" to null,
        "별로 안 급해" to null,
        "하나도 안 급해" to null,
        "급한 건 아니야" to null,
        "안급해" to null,
        "아니안급해" to null,
        "급하게 가지 말자" to null, // 서두르지 말자는 말
        "시간이 급해" to null,
        "참을 수 있어" to null,
        "아직 참을 만해" to null,
        "급한 건 아닌데 화장실 가고 싶어" to restroom(), // 급하지는 않지만 필요는 말했다
    )

    // ------------------------------------------------------------ 화장실

    @Test
    fun restroomNeed() = parsing(
        "화장실 필요", family,
        "화장실 가고 싶어" to restroom(),
        "화장실 가야 돼" to restroom(),
        "화장실 좀 가자" to restroom(),
        "화장실 들르자" to restroom(),
        "화장실 들렀다 가자" to restroom(),
        "화장실 좀" to restroom(),
        "화장실" to restroom(),
        "화장실 어디야" to restroom(),
        "근처 화장실 찾아줘" to restroom(),
        "가까운 화장실" to restroom(),
        "쉬 마려워" to restroom(),
        "오줌 마려" to restroom(),
        "소변 보고 싶어" to restroom(),
        "볼일 좀 보고 싶어" to restroom(),
        "응가 마려워" to restroom(),
        "똥 마려워" to restroom(),
        "화장실 다녀오고 싶어" to restroom(),
        "화장실 갔다 오자" to restroom(),
        "화장실 가도 돼?" to restroom(),
        "화장실 가고 싶은데 얼마나 걸려?" to restroom(), // 필요 + 되묻기 → 필요
        "화장실가고싶어" to restroom(),
        "아이가 화장실 가고 싶대" to restroom("kid"),
        "첫째가 쉬 마렵대" to restroom("kid"),
        "애가 쉬한대" to restroom("kid"),
        "딸이 화장실 간대" to restroom("kid"),
        "아들이 응가 마렵대" to restroom("kid"),
        "할머니가 화장실 가고 싶으시대" to restroom("grandma"),
    )

    @Test
    fun restroomNeedResolvesWhoWasMentioned() {
        parsing("아이를 설정하지 않았다", alone, "아이가 화장실 가고 싶대" to restroom(null))
        parsing("아기라고 불러도 아이", withKid, "애기가 화장실 가고 싶대" to restroom("kid"))
        parsing(
            "아이가 둘", twoKids,
            "첫째가 쉬 마렵대" to restroom("kid"),
            "둘째가 화장실 가고 싶대" to restroom("kid2"),
            "막내가 쉬 마렵대" to restroom("kid2"),
            "아이가 화장실 가고 싶대" to restroom("kid"),
        )
    }

    @Test
    fun mentioningTheRestroomIsNotANeed() = parsing(
        "화장실을 말했지만 필요는 아니다", family,
        "화장실 안 가도 돼" to null,
        "화장실 괜찮아" to null,
        "화장실 갔다 왔어" to null,
        "화장실 아까 갔어" to null,
        "화장실 안 가고 싶어" to null,
        "화장실 가고 싶지 않아" to null,
        "안 마려워" to null,
        "화장실 갈 필요 없어" to null,
        "화장실 들르지 말자" to null,
        "화장실 말고" to null,
        "화장실에 사람 많네" to null,
        // 남은 시간을 묻는 말은 화장실·휴게소가 들어 있어도 되묻기다 (R-F2: 예전에는 화장실 요청이 되어 게이지가 NOW로 뛰고 학습까지 됐다)
        "화장실까지 얼마나 남았어?" to VoiceIntent.HowLong,
        "화장실 언제 가?" to VoiceIntent.HowLong,
        "다음 화장실 몇 분 남았어" to VoiceIntent.HowLong,
        "화장실 멀었어?" to VoiceIntent.HowLong,
        "화장실 얼마나 더 가야 돼?" to VoiceIntent.HowLong,
        "화장실까지얼마나남았어" to VoiceIntent.HowLong,
    )

    // ------------------------------------------------------------ 영아·반려견

    @Test
    fun feeding() {
        parsing(
            "수유·기저귀", family,
            "기저귀 갈아야 해" to feeding("baby"),
            "아기 기저귀" to feeding("baby"),
            "수유해야 돼" to feeding("baby"),
            "분유 먹여야 해" to feeding("baby"),
            "아기 배고파" to feeding("baby"), // 아기의 배고픔은 식당이 아니라 수유실이다
            "아기 맘마 줘야 해" to feeding("baby"),
            "이유식 먹일 시간이야" to feeding("baby"),
            "젖 먹여야 해" to feeding("baby"),
            "옷이 젖었어" to null,
            "기저귀 갈았어" to null,
            "기저귀 괜찮아" to null,
            "분유 먹였어" to null,
            "수유 안 해도 돼" to null,
        )
        parsing("아기를 설정하지 않았다", alone, "기저귀 갈아야 해" to feeding(null))
    }

    @Test
    fun pet() {
        parsing(
            "반려견", family,
            "강아지 쉬 마려운가 봐" to pet("dog"),
            "강아지 산책시켜야 해" to pet("dog"),
            "멍멍이 화장실" to pet("dog"),
            "강아지가 낑낑대" to pet("dog"),
            "반려견 배변" to pet("dog"),
            "강아지 볼일 봐야 해" to pet("dog"),
            "강아지가 급해 보여" to pet("dog"), // 사람 화장실을 찾는 급해 모드가 아니다
            "강아지 귀엽지" to null,
            "강아지 산책 안 해도 돼" to null,
            "강아지 산책했어" to null,
            "강아지 자" to null,
        )
        parsing("반려견을 설정하지 않았다", alone, "강아지 산책 좀 시키자" to pet(null))
    }

    // ------------------------------------------------------------ 졸림·휴식

    @Test
    fun sleepy() = parsing(
        "졸려", family,
        "졸려" to VoiceIntent.Sleepy,
        "졸려요" to VoiceIntent.Sleepy,
        "졸리다" to VoiceIntent.Sleepy,
        "졸린다" to VoiceIntent.Sleepy,
        "나 졸려" to VoiceIntent.Sleepy,
        "졸음 와" to VoiceIntent.Sleepy,
        "졸음이 쏟아져" to VoiceIntent.Sleepy,
        "잠 와" to VoiceIntent.Sleepy,
        "잠이 와" to VoiceIntent.Sleepy,
        "잠 온다" to VoiceIntent.Sleepy,
        "피곤해" to VoiceIntent.Sleepy,
        "피곤하다" to VoiceIntent.Sleepy,
        "눈이 감겨" to VoiceIntent.Sleepy,
        "눈 감긴다" to VoiceIntent.Sleepy,
        "하품 나와" to VoiceIntent.Sleepy,
        "아이고 졸려" to VoiceIntent.Sleepy, // '아이고'는 아이가 아니다
        "자고 싶어" to VoiceIntent.Sleepy,
        "잠들 것 같아" to VoiceIntent.Sleepy,
        "깜빡 졸았어" to VoiceIntent.Sleepy,
        "졸 뻔했어" to VoiceIntent.Sleepy,
        "졸린데 얼마나 남았어" to VoiceIntent.Sleepy,
    )

    @Test
    fun notSleepy() = parsing(
        "운전자가 졸린 것이 아니다", family,
        "안 졸려" to null,
        "졸리지 않아" to null,
        "졸리진 않아" to null,
        "하나도 안 피곤해" to null,
        "아이가 졸려 해" to null, // 졸린 사람은 운전자가 아니다
        "애가 졸리대" to null,
        "잠 안 와" to null,
        "잠깐만" to null,
        "잠시만요" to null,
        "아이 잠들었어" to null, // 잠든 아이는 parseSpoken이 받는다 (엔진 의도가 아니다)
        "졸음쉼터 언제 나와" to VoiceIntent.HowLong,
        "졸음쉼터 가자" to VoiceIntent.WantRest,
        "할머니가 피곤하시대" to VoiceIntent.WantRest, // 다른 사람이 피곤하면 휴식 요청이지 운전자 졸림이 아니다
    )

    @Test
    fun wantRest() = parsing(
        "쉬고 싶다", family,
        "좀 쉬고 싶어" to VoiceIntent.WantRest,
        "쉬자" to VoiceIntent.WantRest,
        "쉬었다 가자" to VoiceIntent.WantRest,
        "잠깐 쉬자" to VoiceIntent.WantRest,
        "쉬어 가자" to VoiceIntent.WantRest,
        "휴식이 필요해" to VoiceIntent.WantRest,
        "한숨 자고 가자" to VoiceIntent.WantRest,
        "눈 좀 붙이고 가자" to VoiceIntent.WantRest,
        "휴게소 가자" to VoiceIntent.WantRest,
        "휴게소 들르자" to VoiceIntent.WantRest,
        "휴게소" to VoiceIntent.WantRest,
        "휴게소 어디야" to VoiceIntent.WantRest,
        "졸음쉼터 들어가자" to VoiceIntent.WantRest,
        "다음 휴게소에서 쉬자" to VoiceIntent.WantRest,
    )

    @Test
    fun notWantingRest() = parsing(
        "쉬자는 말이 아니다", family,
        "안 쉬어도 돼" to null,
        "쉬고 싶지 않아" to null,
        "안 쉬고 싶어" to null,
        "잘 쉬었어" to null,
        "휴게소 안 들러도 돼" to null,
        "휴게소 말고" to null,
        "휴게소 그냥 지나가자" to null,
        "휴게소에 사람 많네" to null,
        "휴게소 언제 나와" to VoiceIntent.HowLong,
        "다음 휴게소 얼마나 남았어" to VoiceIntent.HowLong,
        "휴게소 멀었어?" to VoiceIntent.HowLong,
    )

    // ------------------------------------------------------------ 식사·주유·되묻기

    @Test
    fun hungry() = parsing(
        "배고파", family,
        "배고파" to VoiceIntent.Hungry,
        "배고프다" to VoiceIntent.Hungry,
        "배가 고파" to VoiceIntent.Hungry,
        "밥 먹자" to VoiceIntent.Hungry,
        "밥 좀 먹고 가자" to VoiceIntent.Hungry,
        "식사하고 가자" to VoiceIntent.Hungry,
        "점심 먹어야지" to VoiceIntent.Hungry,
        "간식 먹고 싶어" to VoiceIntent.Hungry,
        "출출해" to VoiceIntent.Hungry,
        "아이가 배고프대" to VoiceIntent.Hungry,
        "아이스크림 먹고 싶어" to VoiceIntent.Hungry,
        "배고픈데 휴게소 얼마나 남았어" to VoiceIntent.Hungry,
        "점심 먹었는데 또 배고파" to VoiceIntent.Hungry,
        "안 배고파" to null,
        "배 안 고파" to null,
        "배고프지 않아" to null,
        "밥 먹었어" to null,
        "식사했어" to null,
        "점심 먹었어" to null,
        "배불러" to null,
    )

    @Test
    fun fuel() = parsing(
        "주유·충전", family,
        "주유소 들르자" to fuel,
        "기름 넣어야 돼" to fuel,
        "기름 없어" to fuel,
        "충전해야 돼" to fuel,
        "주유 좀 하자" to fuel,
        "배터리 없어" to fuel,
        "기름 얼마나 남았어" to null, // 연료량은 코치가 모른다. 화장실 여유를 답하면 틀린 답이다
        "핸드폰 충전해야 돼" to null,
        "기름 안 넣어도 돼" to null,
        "주유했어" to null,
        "기름 넣었어" to null,
        "경유지로 넣어줘" to null,
    )

    @Test
    fun howLong() = parsing(
        "얼마나 남았어", family,
        "얼마나 남았어" to VoiceIntent.HowLong,
        "얼마나 남았어?" to VoiceIntent.HowLong,
        "얼마남았어" to VoiceIntent.HowLong,
        "몇 분 남았어" to VoiceIntent.HowLong,
        "얼마나 더 가야 해" to VoiceIntent.HowLong,
        "얼마나 걸려" to VoiceIntent.HowLong,
        "아직 멀었어?" to VoiceIntent.HowLong,
        "언제쯤 도착해" to VoiceIntent.HowLong,
        "남은 시간 알려줘" to VoiceIntent.HowLong,
    )

    @Test
    fun notUnderstood() = parsing(
        "모르는 말", family,
        null to null,
        "" to null,
        "   " to null,
        "창문 좀 열어 줄래" to null,
        "음 뭐랄까" to null,
        "음 글쎄" to null,
        "노래 틀어줘" to null,
        "에어컨 켜줘" to null,
        "안녕" to null,
        // 떠 있는 질문이 없으면 대답만으로는 뜻이 없다
        "응" to null,
        "아니" to null,
        "가자" to null,
    )

    // ------------------------------------------------------------ 질문이 떠 있을 때 (마이크 버튼)

    @Test
    fun withAnActivePromptYesAndNoBecomeAcceptAndDecline() {
        val id = "p7#1"
        val accept = VoiceIntent.Accept(id)
        val decline = VoiceIntent.Decline(id)
        parsing(
            "수락", family,
            "응" to accept, "네" to accept, "네네" to accept, "예" to accept, "그래" to accept, "좋아" to accept, "좋아요" to accept,
            "오케이" to accept, "콜" to accept, "ㅇㅇ" to accept, "가자" to accept, "들르자" to accept, "들러줘" to accept,
            "네 들러주세요" to accept, "네 가요" to accept, "어 가자" to accept, "그래 들렀다 가자" to accept,
            "넣어줘" to accept, "안내해줘" to accept, "응들르자" to accept,
            // 수락하면서 그 정차를 다시 말한 것은 새 휴식 요청이 아니다 (R-F3)
            "응 휴게소 들르자" to accept,
            "휴게소 들르자" to accept,
            prompt = id,
        )
        parsing(
            "거절", family,
            "아니" to decline, "아니요" to decline, "아뇨" to decline, "괜찮아" to decline, "괜찮아요" to decline, "됐어" to decline,
            "아니 됐어" to decline, "싫어" to decline, "나중에" to decline, "패스" to decline, "별로" to decline,
            "다음에 가자" to decline, "다음 휴게소에서 쉬자" to decline,
            "그냥 가자" to decline, "그냥 지나가자" to decline, "계속 가" to decline,
            "안 들러도 돼" to decline, "안 가도 돼" to decline, "들르지 말자" to decline,
            // 부정한 요청은 요청이 아니라 거절이다 (R-F3: 예전에는 급해 요청이 됐다)
            "아니 안 급해" to decline, "안 급해" to decline, "화장실 안 가도 돼" to decline, "아니괜찮아" to decline,
            prompt = id,
        )
        parsing(
            "대답보다 다른 요청이 먼저", family,
            "아니 화장실 급해" to urgent,
            "화장실 급해" to urgent,
            "응 화장실 급해" to urgent, // 의도 하나만 돌려주는 parse는 급해를 삼키지 않는다 (카드 대답은 replyTo로 받는다)
            "배고파" to VoiceIntent.Hungry,
            "아이가 화장실 가고 싶대" to restroom("kid"),
            "얼마나 남았어?" to VoiceIntent.HowLong,
            "다음 휴게소 얼마나 남았어?" to VoiceIntent.HowLong, // '다음'이 있어도 묻는 말은 거절이 아니다
            "응가 마려워" to restroom(), // '응'이 아니다
            prompt = id,
        )
        parsing(
            "대답이 아니다", family,
            "음 글쎄" to null,
            "잠깐만" to null,
            "아이가 자" to null, // '가자'가 아니다
            "네비 켜줘" to null,
            "바로 가자" to null, // 들르자는 말인지 그냥 가자는 말인지 알 수 없다: 버튼을 기다린다
            "예정대로 가자" to null,
            prompt = id,
        )
    }

    // ------------------------------------------------------------ 예·아니요

    @Test
    fun yesNo() {
        fun answers(name: String, expected: YesNo, vararg said: String?) = table(name, said.map { it to expected }) { parser.yesNo(it) }
        answers(
            "예", YesNo.YES,
            "응", "응응", "웅", "엉", "어", "어어", "네", "네네", "넵", "예", "그래", "그래요", "그럼", "그럼요", "그러자",
            "좋아", "좋아요", "좋습니다", "좋지", "오케이", "ok", "OK", "콜", "당연하지", "물론이지", "알았어", "알겠습니다",
            "가자", "가줘", "가주세요", "갑시다", "들르자", "들러", "들러줘", "들렀다 가자", "쉬자", "쉬었다 가자",
            "넣어줘", "부탁해", "안내해줘", "고고",
            "응 가자", "네 들러주세요", "어 그래", "응 휴게소 들르자", "휴게소 가자", "괜찮네 들르자",
            "응가자", "네가주세요", // 띄어쓰기 없는 STT: "응 가자", "네 가주세요"
        )
        answers(
            "아니요", YesNo.NO,
            "아니", "아니요", "아니야", "아뇨", "아냐", "아닙니다", "괜찮아", "괜찮아요", "괜찮습니다", "됐어", "됐어요", "싫어",
            "나중에", "이따가", "다음에", "다음 휴게소 가자", "그냥 가자", "그냥 가", "계속 가자", "쭉 가자", "집에 가자",
            "그냥 지나가자", "지나쳐", "패스", "취소", "별로", "굳이",
            "안 들러도 돼", "안 가도 돼", "안 가", "안 쉬어도 돼", "안 돼", "안 좋아", "필요 없어", "들르지 말자", "가지 마",
            "아니 안 급해", "안 급해", "별로 안 급해", "참을 수 있어",
            "응 아니 됐어", "아니 괜찮아 그냥 가자",
        )
        answers(
            "모름", YesNo.UNKNOWN,
            null, "", "음", "글쎄", "모르겠어", "잠깐만",
            "화장실 급해", "배고파", "얼마나 남았어", "다음 휴게소 얼마나 남았어?",
            "아이가 자", "애기 자", // 잠든 아이 이야기는 '가자'가 아니다
            "응가 마려워", "응급실", "네비 켜줘", "네 시에 도착해", "예정대로", "그래도", "콜라 마시고 싶어", "좋아하는 노래 틀어줘",
            "바로 가자", "빨리 가자", // 어느 쪽인지 알 수 없다
        )
    }
}
