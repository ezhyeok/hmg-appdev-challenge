package dev.shimpyo.coach.voice

/** 받침에 맞는 조사 ("안성휴게소를", "주유소로", "졸음쉼터는"). 조사 규칙은 여기 한 곳에만 둔다 */
object Hangul {
    private fun lastSyllable(word: String): Char? = word.trim().lastOrNull { it in '가'..'힣' }

    /** 받침이 있으면 true. 한글이 아니면 false */
    fun hasBatchim(word: String): Boolean {
        val c = lastSyllable(word) ?: return false
        return (c - '가') % 28 != 0
    }

    /** 받침이 ㄹ이면 true ("~로" 판단용) */
    private fun batchimIsRieul(word: String): Boolean {
        val c = lastSyllable(word) ?: return false
        return (c - '가') % 28 == 8
    }

    fun eulReul(word: String) = word + if (hasBatchim(word)) "을" else "를"
    fun iGa(word: String) = word + if (hasBatchim(word)) "이" else "가"
    fun eunNeun(word: String) = word + if (hasBatchim(word)) "은" else "는"
    fun euRo(word: String) = word + if (hasBatchim(word) && !batchimIsRieul(word)) "으로" else "로"
}
