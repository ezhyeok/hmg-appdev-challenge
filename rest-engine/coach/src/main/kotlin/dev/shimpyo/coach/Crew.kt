package dev.shimpyo.coach

import dev.shimpyo.engine.Occupant

/**
 * 탑승자에 관한 일 한 묶음: 지금 세계에 누가 타는지, 건강 정보 동의, 자는 중 표시, 말로 설정. 코어(액터)가 들고 코어 스레드에서만
 * 부른다. 규칙은 순수 부품이 한다: HealthConsent(동의), AsleepMarks(자는 중), OccupantPhrase(말 → 탑승자), WorldSwitch(세계마다의 탑승자).
 * 여기는 그것들을 잇고 화면 상태(occupants, asleepOccupantIds)를 올린다.
 *
 * 말로 설정은 두 걸음이다: [describe]가 묻고 들은 말을 돌려주면 코어가 우편함을 거쳐 [heard]에 넣고, 규칙으로 못 읽으면 LLM 해석을
 * 띄워 그 결과를 다시 우편함을 거쳐 [interpreted]에 넣는다. 결과를 돌려받는 콜백은 우편함에 넣는 일만 한다(VoiceDesk).
 */
class Crew(
    private val worlds: WorldSwitch,
    private val settings: SettingsPort,
    private val voice: VoicePort,
    private val desk: VoiceDesk,
    private val interpreter: IntentInterpreter?,
    private val ui: UiSink,
    private val log: CoachLog = CoachLog.NONE,
) {
    private val asleep = AsleepMarks()
    /** 마지막으로 본 건강 정보 동의 (처음에는 모름). 꺼진 것을 보면 건강 유형 탑승자를 뺀다 */
    private var consentSeen: Boolean? = null

    /** 지금 세계의 탑승자 (시연이면 시연 탑승자) */
    val occupants: List<Occupant> get() = worlds.active.world.occupants(settings)

    /** 자는 중으로 표시된 탑승자 id. 코어가 매 틱 엔진 입력에 넣는다 (CoachInput.asleepOccupantIds) */
    val asleepIds: Set<String> get() = asleep.ids

    // ------------------------------------------------------------ 탑승자와 동의

    /** 화면이 탑승자를 바꿨다. 건강 유형은 동의가 있을 때만 둔다. 시연이면 그 시연에만 (WorldSwitch.setOccupants) */
    fun set(list: List<Occupant>) {
        worlds.setOccupants(HealthConsent.allowed(list, settings.healthConsent))
        publish()
    }

    /**
     * 건강 정보 동의가 꺼져 있으면(화면이 설정을 바꾼다: 명령은 오지 않는다) 건강 유형 탑승자를 모든 세계에서 뺀다 (UI 닫힘 검증 N3).
     * 코어가 시작할 때와 매 틱 부른다. 동의 값이 바뀐 때만 탑승자를 본다 (동의 읽기는 싸고, 탑승자 읽기는 설정을 다시 푼다)
     */
    fun enforceConsent() {
        val consent = settings.healthConsent
        if (consent == consentSeen) return
        consentSeen = consent
        if (!consent && worlds.dropHealthOccupants()) {
            log.i("health consent off: health-type occupants removed")
            publish()
        }
    }

    // ------------------------------------------------------------ 자는 중

    /** 탑승자를 자는 중으로 표시하거나 푼다 (화면의 표시, 말 "아이 잠들었어"·"깼어"). 아이만 표시할 수 있다 (AsleepMarks) */
    fun setAsleep(occupantId: String?, on: Boolean): AsleepMarks.Result {
        val result = asleep.set(occupantId, on, occupants, worlds.active.clock.now())
        if (result == AsleepMarks.Result.MARKED || result == AsleepMarks.Result.CLEARED) publishAsleep()
        return result
    }

    /** 틱마다 (엔진 입력을 만들기 전에): 화장실 정차가 되거나 아침이 지나면 표시를 푼다 */
    fun onTick(now: Long, park: ParkState?) {
        if (asleep.onTick(now, park)) publishAsleep()
    }

    /** 화장실 정차가 기록됐다 */
    fun onRestroomStop() {
        if (asleep.onRestroomStop()) publishAsleep()
    }

    /** 세계(시연·실제)가 바뀌었다: 표시는 지난 세계의 것이다. 화면 상태는 코어가 새 세계의 것으로 통째로 올린다 */
    fun worldChanged() {
        asleep.clear()
    }

    // ------------------------------------------------------------ 말로 설정

    /**
     * [말로 설정]: 누가 타는지 묻고 듣는다. 음성 안내를 껐으면 묻는 말은 화면 알림으로 내고 듣기만 한다. 음성 인식을 쓸 수 없으면
     * 왜 안 되는지 알린다 (UI 닫힘 검증 #12: 예전에는 눌러도 조용히 아무 일도 없었다). 들은 말은 [onHeard]로 돌려준다
     */
    fun describe(onHeard: (String?) -> Unit) {
        if (desk.describing) return
        if (!voice.sttReady) return desk.notice("음성 인식을 쓸 수 없어요. 화면에서 골라 주세요")
        val speak = desk.canSpeak
        if (!speak) desk.notice(QUESTION)
        desk.describe(QUESTION.takeIf { speak }, onHeard)
    }

    /** 들은 말을 탑승자로 읽는다. 규칙으로 못 읽으면 LLM에 맡기고 그 결과를 [onInterpreted]로 돌려준다 */
    fun heard(text: String?, onInterpreted: (List<Occupant>?) -> Unit) {
        if (text.isNullOrBlank()) return
        val list = OccupantPhrase.parse(text)
        val llm = interpreter
        if (list.isNotEmpty() || llm == null) return interpreted(list)
        desk.interpret("interpretOccupants", failed = null, done = onInterpreted) { llm.occupants(text) }
    }

    /** 읽은 탑승자 목록을 적용하고 운전자에게 답한다 (못 읽었으면 그렇다고, 동의가 없어 뺀 사람이 있으면 그 안내도) */
    fun interpreted(list: List<Occupant>?) {
        if (list.isNullOrEmpty()) return desk.answer("잘 못 알아들었어요. 화면에서 골라 주세요")
        val heard = HealthConsent.spoken(list, settings.healthConsent)
        heard.apply?.let(::set)
        desk.answer(heard.reply)
    }

    // ------------------------------------------------------------ 화면

    private fun publish() {
        val now = occupants
        asleep.onOccupants(now)
        ui.update { copy(occupants = now, asleepOccupantIds = asleep.ids) }
    }

    private fun publishAsleep() = ui.update { copy(asleepOccupantIds = asleep.ids) }

    private companion object {
        const val QUESTION = "누가 함께 타나요? 예를 들어 일곱 살 아이 하나랑 강아지처럼 말해 주세요"
    }
}
