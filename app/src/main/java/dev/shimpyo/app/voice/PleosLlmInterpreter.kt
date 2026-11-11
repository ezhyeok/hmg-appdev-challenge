package dev.shimpyo.app.voice

import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.constant.Mode
import ai.pleos.playground.llm.listener.ResultListener
import android.content.Context
import android.util.Log
import dev.shimpyo.coach.IntentInterpreter
import dev.shimpyo.engine.Occupant
import dev.shimpyo.engine.OccupantType
import dev.shimpyo.engine.Purpose
import dev.shimpyo.engine.VoiceIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 규칙으로 못 알아들은 자유 발화를 Pleos LLM SDK(ON_DEVICE)로 해석한다. 결과는 정해진 JSON만 받아 검증하고,
 * 엔진 의도로 바꾼다. LLM은 판단을 하지 않는다(어디서 쉴지, 언제 물을지는 엔진이 정한다).
 *
 * 에뮬레이터(2026-11-11)에서는 ON_DEVICE 모드가 응답하지 않았다. 처음 호출에서 [FIRST_TIMEOUT_MS] 안에 답이 없으면
 * 이번 실행 동안은 쓰지 않고 규칙 기반으로만 동작한다.
 */
class PleosLlmInterpreter(private val context: Context) : IntentInterpreter {
    enum class State { UNKNOWN, READY, UNAVAILABLE }

    @Volatile var state = State.UNKNOWN
        private set
    private var llm: LLM? = null

    override val label: String
        get() = when (state) {
            State.READY -> "Pleos LLM (온디바이스)"
            State.UNAVAILABLE -> "규칙 기반 (LLM 응답 없음)"
            State.UNKNOWN -> "규칙 기반"
        }

    /** 발화 → 엔진 의도. 해석하지 못하면 null */
    override suspend fun interpret(text: String, occupants: List<Occupant>, activePromptId: String?): VoiceIntent? {
        val raw = generate(intentPrompt(text)) ?: return null
        return runCatching { parseIntent(JSONObject(extractJson(raw)), occupants, activePromptId) }.getOrNull()
    }

    /** "아이 둘이랑 강아지" → 탑승자 목록. 해석하지 못하면 null */
    override suspend fun occupants(text: String): List<Occupant>? {
        val raw = generate(occupantPrompt(text)) ?: return null
        return runCatching {
            val arr = JSONArray(extractJson(raw))
            val out = mutableListOf(Occupant("driver", OccupantType.ADULT, label = "운전자"))
            for (i in 0 until minOf(arr.length(), 8)) {
                val o = arr.getJSONObject(i)
                val type = TYPES[o.optString("type")] ?: continue
                val age = if (o.has("age") && !o.isNull("age")) o.optInt("age").takeIf { it in 0..17 } else null
                out += Occupant("p$i", type, ageYears = age, label = o.optString("label").take(12).ifEmpty { LABELS[type] ?: "" })
            }
            out.takeIf { it.size > 1 }
        }.getOrNull()
    }

    private suspend fun generate(prompt: String): String? {
        if (state == State.UNAVAILABLE) return null
        val model = llm ?: withContext(Dispatchers.IO) {
            runCatching { LLM(context, Mode.ON_DEVICE).also { it.initialize() } }.getOrNull()
        }?.also { llm = it } ?: run {
            state = State.UNAVAILABLE
            return null
        }
        val done = CompletableDeferred<String?>()
        val sb = StringBuilder()
        withContext(Dispatchers.IO) {
            runCatching {
                model.generateContent(prompt, object : ResultListener {
                    override fun onResponse(response: String, completed: Boolean) {
                        sb.append(response)
                        if (completed) done.complete(sb.toString())
                    }

                    override fun onError(reason: String) {
                        Log.w(TAG, "llm error: $reason")
                        done.complete(null)
                    }
                })
            }.onFailure { done.complete(null) }
        }
        val timeout = if (state == State.READY) TIMEOUT_MS else FIRST_TIMEOUT_MS
        val result = withTimeoutOrNull(timeout) { done.await() }
        state = if (result != null) State.READY else State.UNAVAILABLE
        return result
    }

    fun release() {
        runCatching { llm?.release() }
        llm = null
    }

    private fun intentPrompt(text: String) = """
        차 안에서 들은 말을 아래 JSON 하나로만 분류해. 다른 글은 쓰지 마.
        {"intent": "urgent_restroom|occupant_need|sleepy|want_rest|hungry|how_long|accept|decline|unknown",
         "who": "driver|child|infant|elderly|pregnant|dog|unknown",
         "purpose": "restroom|feeding|pet|rest|meal|none"}
        예) "애기 기저귀 갈아야 할 것 같아" → {"intent":"occupant_need","who":"infant","purpose":"feeding"}
        예) "아 진짜 못 참겠다" → {"intent":"urgent_restroom","who":"unknown","purpose":"restroom"}
        들은 말: "$text"
    """.trimIndent()

    private fun occupantPrompt(text: String) = """
        운전자 말고 차에 함께 탄 사람을 JSON 배열로만 써. 다른 글은 쓰지 마.
        각 항목: {"type": "adult|child|infant|elderly|pregnant|oab|ibs|dog", "age": 숫자 또는 null, "label": "짧은 이름"}
        예) "일곱 살 딸이랑 강아지" → [{"type":"child","age":7,"label":"딸"},{"type":"dog","age":null,"label":"강아지"}]
        말: "$text"
    """.trimIndent()

    private fun parseIntent(o: JSONObject, occupants: List<Occupant>, activePromptId: String?): VoiceIntent? {
        val who = o.optString("who")
        val occupantId = TYPES[who]?.let { t -> occupants.firstOrNull { it.type == t }?.id }
        return when (o.optString("intent")) {
            "urgent_restroom" -> VoiceIntent.UrgentRestroom
            "occupant_need" -> {
                val purpose = when (o.optString("purpose")) {
                    "feeding" -> Purpose.FEEDING
                    "pet" -> Purpose.PET
                    "rest" -> Purpose.REST
                    "meal" -> Purpose.MEAL
                    else -> Purpose.RESTROOM
                }
                VoiceIntent.OccupantNeed(occupantId, purpose)
            }
            "sleepy" -> VoiceIntent.Sleepy
            "want_rest" -> VoiceIntent.WantRest
            "hungry" -> VoiceIntent.Hungry
            "how_long" -> VoiceIntent.HowLong
            "accept" -> activePromptId?.let { VoiceIntent.Accept(it) }
            "decline" -> activePromptId?.let { VoiceIntent.Decline(it) }
            else -> null
        }
    }

    /** 응답에 설명이 섞여 와도 첫 JSON 덩어리만 꺼낸다 */
    private fun extractJson(raw: String): String {
        val start = raw.indexOfFirst { it == '{' || it == '[' }
        val end = raw.indexOfLast { it == '}' || it == ']' }
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }

    private companion object {
        const val TAG = "Shimpyo"
        const val FIRST_TIMEOUT_MS = 5_000L
        const val TIMEOUT_MS = 4_000L
        val TYPES = mapOf(
            "adult" to OccupantType.ADULT, "driver" to OccupantType.ADULT, "child" to OccupantType.CHILD,
            "infant" to OccupantType.INFANT, "elderly" to OccupantType.ELDERLY, "pregnant" to OccupantType.PREGNANT,
            "oab" to OccupantType.OAB, "ibs" to OccupantType.IBS, "dog" to OccupantType.DOG,
        )
        val LABELS = mapOf(
            OccupantType.ADULT to "어른", OccupantType.CHILD to "아이", OccupantType.INFANT to "아기",
            OccupantType.ELDERLY to "어르신", OccupantType.PREGNANT to "임신부", OccupantType.OAB to "과민성 방광",
            OccupantType.IBS to "과민성 장", OccupantType.DOG to "반려견",
        )
    }
}
