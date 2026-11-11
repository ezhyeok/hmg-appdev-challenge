package dev.shimpyo.coach

import dev.shimpyo.engine.StopKind

/**
 * 휴게소·졸음쉼터 이름 비교 (Pleos 내비 TBT의 이름 ↔ 공공데이터 이름).
 *
 * 키 = 이름에서 방향 표기와 시설어를 지운 고유 부분. 방향은 어디에 어떻게 쓰든 지운다: 괄호("(부산방향)", "[부산]",
 * "안성(부산)휴게소"), 띄어 쓴 낱말("부산방향", "서울방면", "상행", "하행선", "방향"), 시설어 뒤에 붙여 쓴 것
 * ("안성휴게소부산방향"), 앞에 쓴 것("서울방향 안성휴게소"). 시설어(휴게소·졸음쉼터·쉼터·휴게시설) 앞이 고유 이름이다.
 *
 * 2026-11-11 데이터 감사 #2: 예전에는 접미어를 지운 뒤 `[가-힣]+방향$`을 지워서 "안성휴게소 부산방향"이 ""가 됐다
 * (TBT 후보가 차 좌표에 편의시설 없이 들어감). 구별되는 부분이 없는 이름("졸음쉼터")은 [key]가 null이다.
 */
object StopNames {

    /** 비교 키. 시설어나 방향만 있는 이름이면 null (아무 시설도 가리키지 않는다) */
    fun key(name: String): String? {
        // 방향만 있는 낱말은 버린다 ("안성휴게소부산방향"처럼 시설어와 붙은 낱말은 아래에서 시설어로 자른다)
        val tokens = name.replace(BRACKETS, " ").split(SEPARATORS)
            .filter { it.isNotEmpty() && !(DIRECTION_TOKEN.matches(it) && FACILITY.find(it) == null) }
        val s = tokens.joinToString("")
        val m = FACILITY.find(s)
        var base = when {
            m == null -> s
            m.range.first > 0 -> s.substring(0, m.range.first)     // 시설어 뒤(붙여 쓴 방향 등)는 버린다
            else -> s.substring(m.range.last + 1)                  // "휴게소안성" 같은 드문 꼴
        }
        base = base.replace(LEADING_DIRECTION, "")
        return base.takeIf { it.isNotEmpty() && !DIRECTION_TOKEN.matches(it) && FACILITY.find(it)?.value != it }
    }

    /** 예전 API (RouteOpportunities.normalizeName): 구별되는 부분이 없으면 "" */
    fun normalizeName(name: String): String = key(name) ?: ""

    /** 이름의 시설어로 본 종류. 시설어가 없으면 null */
    fun facilityKind(name: String): StopKind? = when {
        "졸음쉼터" in name -> StopKind.DROWSY_SHELTER
        "휴게소" in name || "휴게시설" in name -> StopKind.HIGHWAY_REST_AREA
        "쉼터" in name -> StopKind.DROWSY_SHELTER
        else -> null
    }

    /**
     * 공공데이터에서 찾을 종류: 이름의 시설어가 TBT 종류보다 정확하다. 쉼터를 SERVICEAREA로 보내면 예전에는 같은 이름의
     * 휴게소와 맞았다(옥산졸음쉼터(서울방향) ↔ 옥산휴게소(부산방향) 0.3km, 신탄진 0.2km, 홍천강 0.1km. 데이터 감사 #5)
     */
    fun kindFor(name: String, tbtKind: StopKind): StopKind = facilityKind(name) ?: tbtKind

    /** TBT 항목이 화장실이 있는 정차 시설인지. 하이패스센터처럼 화장실 없는 '서비스 시설'도 SERVICEAREA로 온다 */
    fun isRestStop(name: String, kind: StopKind): Boolean = when (kind) {
        // '휴게시설'은 facilityKind가 휴게소로 본다. 같은 규칙으로 정차 시설로 받는다
        StopKind.HIGHWAY_REST_AREA -> name.contains("휴게소") || name.contains("쉼터") || name.contains("휴게시설")
        StopKind.DROWSY_SHELTER -> name.contains("쉼터") || name.contains("휴게")
        else -> false
    }

    private val BRACKETS = Regex("\\(.*?\\)|\\[.*?]|（.*?）|［.*?］")
    private val SEPARATORS = Regex("[\\s\\-_/·,.]+")
    private val DIRECTION_TOKEN = Regex("(상행|하행|상행선|하행선|양방향|방향|방면|[가-힣]{1,8}(방향|방면))")
    private val FACILITY = Regex("졸음쉼터|휴게소|휴게시설|쉼터")
    private val LEADING_DIRECTION = Regex("^([가-힣]{1,6}?(방향|방면)|상행|하행)")
}
