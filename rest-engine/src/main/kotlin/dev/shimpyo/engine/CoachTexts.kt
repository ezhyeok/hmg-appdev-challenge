package dev.shimpyo.engine

import kotlin.math.round
import kotlin.math.roundToInt

/**
 * 화면·음성 문구를 한곳에 모았다. 앱은 이 클래스를 상속해 바꾸고 싶은 함수만 고칠 수 있다.
 *
 * 질문(TTS)은 '짧은 상황 설명 + 예/아니오 질문' 형식이고, 대답은 "응/아니"로 받는다.
 * 이유(Reason.text)는 화면 카드에 한 줄로 보이는 짧은 문장이다. Reason.code는 바꾸지 않는다 (테스트·로그용).
 * 조사(이/가, 을/를, 이에요/예요)는 앞 글자의 받침으로 고른다.
 */
open class CoachTexts {

    // ---------------- 형식 ----------------

    /** 75 → "1시간 15분", 40 → "40분", 120 → "2시간" */
    open fun duration(min: Int): String {
        val m = maxOf(0, min)
        val h = m / 60
        val r = m % 60
        return when {
            h == 0 -> "${r}분"
            r == 0 -> "${h}시간"
            else -> "${h}시간 ${r}분"
        }
    }

    /** 800 → "800m", 3_450 → "3.5km", 12_300 → "12km" */
    open fun distance(m: Double): String = when {
        m < 950.0 -> "${maxOf(100L, Math.round(m / 100.0) * 100)}m"
        m < 9_950.0 -> {
            val tenths = Math.round(m / 100.0) // 반올림 (round half up)
            if (tenths % 10 == 0L) "${tenths / 10}km" else "${tenths / 10}.${tenths % 10}km"
        }
        else -> "${(m / 1000.0).roundToInt()}km"
    }

    /** 화면·음성에 쓰는 탑승자 이름. 이름표가 없으면 유형 이름 */
    open fun occupantName(o: Occupant): String = o.label.ifBlank { typeName(o) }

    open fun typeName(o: Occupant): String = when (o.type) {
        OccupantType.ADULT -> "어른"
        OccupantType.CHILD -> o.ageYears?.let { "${it}살 아이" } ?: "아이"
        OccupantType.INFANT -> "아기"
        OccupantType.PREGNANT -> "임신부"
        OccupantType.ELDERLY -> "어르신"
        OccupantType.OAB -> "과민성 방광 탑승자"
        OccupantType.IBS -> "과민성 장 탑승자"
        OccupantType.DOG -> "반려견"
    }

    // ---------------- 질문·안내 (TTS) ----------------

    /** "앞에 40분 정체가 있어요. 다음 안성휴게소가 정체 전 마지막 화장실이에요. 들를까요?" */
    open fun lastChanceCongestion(stop: String, congestionMin: Int): String =
        "앞에 ${duration(congestionMin)} 정체가 있어요. 다음 $stop${subj(stop)} 정체 전 마지막 화장실이에요. 들를까요?"

    /** "안성휴게소를 지나면 50분 동안 화장실이 없어요. 들를까요?" */
    open fun lastChanceGap(stop: String, gapMin: Int): String =
        "$stop${obj(stop)} 지나면 ${duration(gapMin)} 동안 화장실이 없어요. 들를까요?"

    /** 게이지가 NOW인데 마지막 기회 규칙이 없을 때 가장 가까운 화장실 */
    open fun restroomNowSuggestion(stop: String, distanceM: Double): String =
        "화장실 여유가 거의 없어요. ${distance(distanceM)} 앞 ${stop}에 들를까요?"

    /** "1시간 50분째 쉬지 않으셨어요. 12km 앞 졸음쉼터에서 쉬어 갈까요?" */
    open fun restSuggestion(drivenMin: Int, stop: String, distanceM: Double): String =
        "${duration(drivenMin)}째 쉬지 않으셨어요. ${distance(distanceM)} 앞 ${stop}에서 쉬어 갈까요?"

    open fun restSuggestionNoStop(drivenMin: Int): String =
        "${duration(drivenMin)}째 쉬지 않으셨어요. 가까운 쉼터에서 쉬어 갈까요?"

    /** 운전 시간이 아니라 시각·깨어 있은 시간(졸림 사전 곡선) 때문에 권할 때 */
    open fun restSuggestionBodyClock(stop: String?, distanceM: Double?): String =
        if (stop != null && distanceM != null) "지금은 졸음이 오기 쉬운 시간이에요. ${distance(distanceM)} 앞 ${stop}에서 쉬어 갈까요?"
        else "지금은 졸음이 오기 쉬운 시간이에요. 가까운 쉼터에서 쉬어 갈까요?"

    /**
     * "졸려"·상태 확인 "응"에 대한 응답 (강한 권고). 쉬기만 하는 것보다 커피 뒤 15분 이내 낮잠의 근거가 가장 강하고,
     * 운전을 바꿀 사람이 있으면 교대를 권한다 (ai-science.md 2장). 카페인은 선택지로만 말한다.
     */
    open fun restSuggestionSleepy(stop: String?, distanceM: Double?, canSwapDriver: Boolean = false): String {
        val advice = sleepyAdvice(canSwapDriver)
        return if (stop != null && distanceM != null) "$advice ${distance(distanceM)} 앞 ${stop}에 들를까요?"
        else "$advice 가까운 쉼터를 찾아 들를까요?"
    }

    /** 졸릴 때의 권고 한 문장: 커피 뒤 15분 이내 낮잠, 운전을 바꿀 사람이 있으면 교대 */
    open fun sleepyAdvice(canSwapDriver: Boolean): String =
        if (canSwapDriver) "졸릴 땐 커피 한 잔 뒤 15분 이내로 눈을 붙이거나 운전을 바꾸는 게 좋아요."
        else "졸릴 땐 쉬기만 하기보다 커피 한 잔 뒤 15분 이내로 눈을 붙이는 게 좋아요."

    /**
     * 사용자의 말("화장실 급해", "아이가 화장실 가고 싶대", "쉬고 싶어" 등)에 답할 곳이 이미 들르기로 한 곳일 때.
     * '들를까요?'를 다시 묻지 않는다: "가휴게소에 들르기로 했어요. 6km 남았어요."
     */
    open fun alreadyAccepted(stop: String, distanceM: Double?): String =
        if (distanceM != null) "${stop}에 들르기로 했어요. ${distance(distanceM)} 남았어요." else "${stop}에 들르기로 했어요."

    /** "좀 쉬고 싶어"에 대한 응답 */
    open fun restSuggestionWanted(stop: String?, distanceM: Double?): String =
        if (stop != null && distanceM != null) "${distance(distanceM)} 앞 ${stop}에서 쉬어 갈까요?"
        else "가까운 쉼터를 찾아 쉬어 갈까요?"

    /** "배고파"에 대한 응답 */
    open fun mealSuggestion(stop: String, distanceM: Double): String =
        "${distance(distanceM)} 앞 ${stop}에서 식사할 수 있어요. 들를까요?"

    open fun mealNone(): String = "경로 앞에서 식사할 수 있는 곳을 아직 찾지 못했어요."

    /** "아이가 화장실 가고 싶대"에 대한 응답: "다음 화장실은 3.5km 앞 가휴게소예요. 들를까요?" */
    open fun restroomRequested(stop: String, distanceM: Double): String =
        "다음 화장실은 ${distance(distanceM)} 앞 $stop${copula(stop)}. 들를까요?"

    /** 수유·반려견 요청에 대한 응답 */
    open fun careStopSuggestion(stop: String, distanceM: Double): String =
        "${distance(distanceM)} 앞 ${stop}에서 잠깐 쉬어 갈 수 있어요. 들를까요?"

    open fun restroomRequestedNone(): String = "경로 앞에서 들를 수 있는 곳을 아직 찾지 못했어요."

    /** 서 있는 곳 가까이(걸어갈 거리)에 화장실이 있을 때 "화장실 가고 싶대"의 답: "가까이에 화장실이 있어요. 가휴게소예요." */
    open fun restroomHere(stop: String): String = "가까이에 화장실이 있어요. $stop${copula(stop)}."

    /** 서 있는 동안 수유·반려견 요청의 답 */
    open fun careHere(stop: String): String = "지금 서 있는 ${stop}에서 잠깐 쉬어 갈 수 있어요."

    /** 주유 요청에 대한 응답 */
    open fun fuelSuggestion(stop: String, distanceM: Double): String =
        "${distance(distanceM)} 앞 ${stop}에서 주유할 수 있어요. 들를까요?"

    open fun fuelNone(): String = "경로 앞에서 주유할 수 있는 곳을 아직 찾지 못했어요."

    /**
     * 상태 확인. 졸림 자기보고가 카메라 없는 앱에서 가장 믿을 만한 신호다 (ai-science.md 1장).
     * byStyle: 운전 패턴 변화 때문에 묻는지, 아니면 운전 시간·시간대 때문에 묻는지
     */
    open fun checkIn(drivenMin: Int, byStyle: Boolean): String =
        if (byStyle) "운전 패턴이 이번 주행 초반과 조금 달라요. 혹시 졸리세요?"
        else "${duration(drivenMin)}째 운전 중이에요. 혹시 졸리세요?"

    /** 졸림 사전 곡선 때문에 묻는 상태 확인 */
    open fun checkInBodyClock(): String = "지금은 졸음이 오기 쉬운 시간이에요. 혹시 졸리세요?"

    /** 환기 권유. 내기순환이 졸음을 일으킨다고 말하지 않는다 (편의 기능) */
    open fun freshAir(recircMin: Int): String = "내기순환이 ${duration(recircMin)}째예요. 잠깐 환기할까요?"

    /** "OO주유소까지 약 3분이에요. 바로 안내할까요?" */
    open fun urgentResult(stop: String, minutes: Int): String = "${stop}까지 약 ${maxOf(1, minutes)}분이에요. 바로 안내할까요?"

    open fun urgentNone(): String = "가까운 곳에서 들를 수 있는 화장실을 찾지 못했어요."

    /** "화장실까지 얼마나 남았어?"에 대한 응답 */
    open fun howLong(restroomMin: Int?, who: String?, fatigueMin: Int?, nextStop: String?, nextDistanceM: Double?): String {
        val parts = ArrayList<String>(3)
        if (restroomMin != null) {
            parts += if (who != null) "$who 기준 화장실 여유는 약 ${duration(restroomMin)}이에요."
            else "화장실 여유는 약 ${duration(restroomMin)}이에요."
        }
        if (nextStop != null && nextDistanceM != null) parts += "다음 화장실은 ${distance(nextDistanceM)} 앞 $nextStop${copula(nextStop)}."
        if (fatigueMin != null) parts += "쉬지 않고 운전할 수 있는 시간은 약 ${duration(fatigueMin)}이에요."
        return if (parts.isEmpty()) "아직 계산할 정보가 없어요." else parts.joinToString(" ")
    }

    // ---------------- 이유: 화장실 게이지 ----------------

    open fun reasonRestroomNoOccupants(): String = "탑승자를 설정하면 화장실 게이지를 계산해요"
    open fun reasonRestroomElapsed(who: String, elapsedMin: Int): String =
        "$who 기준, 마지막 화장실 후 ${duration(elapsedMin)} 지났어요"
    open fun reasonRestroomVoiced(who: String): String = "$who${subj(who)} 화장실이 필요하다고 했어요"
    open fun reasonRestroomPersonal(who: String, n: Int): String = "${who}의 지난 기록 ${n}번을 반영했어요"

    /** 식사 정차 뒤 구간이라 게이지를 줄여 잡았을 때. 먹고 마신 양을 안다고 말하지 않는다 (need-covariates.md 9장) */
    open fun reasonRestroomAfterMeal(): String = "식사 뒤라 평소보다 조금 일찍 잡았어요"

    /** 밤에 잠든 아이라 게이지를 늘려 잡았을 때 */
    open fun reasonRestroomAsleep(who: String): String = "$who${subj(who)} 자는 동안은 여유를 조금 더 뒀어요"

    /** 정차 간 상한(카시트·걷기)이 게이지를 정할 때 */
    open fun reasonRestroomCap(o: Occupant, capMin: Int): String = when (o.type) {
        OccupantType.INFANT -> "아기는 카시트에 ${duration(capMin)} 넘게 있지 않는 게 좋아요"
        else -> {
            val name = occupantName(o)
            "$name${topic(name)} ${duration(capMin)}마다 차에서 내려 걷는 게 좋아요"
        }
    }

    // ---------------- 이유: 운전 스타일 ----------------

    open fun reasonStyleBaseline(doneMin: Int, totalMin: Int): String =
        "이번 주행의 평소 운전 패턴을 익히는 중이에요 ($doneMin/${totalMin}분)"
    open fun reasonStyleSteeringReversal(): String = "최근 10분 핸들 방향을 고치는 횟수가 평소보다 늘었어요"
    open fun reasonStyleSteeringMicro(): String = "최근 10분 핸들 미세 조정이 평소보다 늘었어요"
    open fun reasonStyleSpeed(): String = "최근 10분 속도 변화가 평소보다 커졌어요"
    open fun reasonStyleBrake(): String = "최근 10분 급제동이 평소보다 잦았어요"

    // ---------------- 이유: 피로 게이지 ----------------

    open fun reasonFatigueVoiced(): String = "졸리다고 하셨어요"
    open fun reasonFatigueDriving(min: Int): String = "${duration(min)}째 쉬지 않고 운전 중이에요"
    open fun reasonFatigueNight(): String = "새벽 2~6시는 졸음이 가장 많이 오는 시간이에요"
    open fun reasonFatigueAfternoon(): String = "오후 2~4시는 졸음이 오기 쉬운 시간이에요"
    open fun reasonFatigueShortSleep(hours: Float): String {
        val h = round(hours * 10f) / 10f
        val s = if (h == h.toInt().toFloat()) h.toInt().toString() else h.toString()
        return "지난밤 ${s}시간 주무셔서 기준을 앞당겼어요"
    }
    open fun reasonFatigueStyle(): String = "운전 패턴 변화가 이어져 휴식 권유를 앞당겼어요"
    open fun reasonFatigueStrong(): String = "졸릴 땐 쉬기만 해서는 부족해요. 커피 뒤 15분 이내 낮잠이 도움이 돼요"
    open fun reasonFatigueBodyClock(awakeHours: Int): String = "일어난 지 ${awakeHours}시간째, 하루 중 졸음이 오기 쉬운 때예요"
    open fun reasonFatigueAwakeLong(awakeHours: Int): String = "일어난 지 ${awakeHours}시간이 넘었어요"
    open fun reasonFatigueMonotony(): String = "크루즈로 단조롭게 달리는 중이에요"

    // ---------------- 이유: 실내 (피로와 별개인 편의 기능) ----------------

    open fun reasonCabinRecirc(min: Int): String = "내기순환이 ${duration(min)}째예요. 잠깐 환기하면 쾌적해요"
    open fun reasonCabinCoolDown(): String = "설정 온도를 조금 낮추면 쾌적해요"

    // ---------------- 이유: 급해 모드 ----------------

    open fun reasonUrgentTime(totalMin: Int, driveMin: Int, parkWalkMin: Int, uTurnMin: Int): String {
        val parts = ArrayList<String>(3)
        parts += "주행 ${driveMin}분"
        if (parkWalkMin > 0) parts += "주차·도보 ${parkWalkMin}분"
        if (uTurnMin > 0) parts += "유턴 ${uTurnMin}분"
        return "약 ${totalMin}분 (${parts.joinToString(", ")})"
    }
    open fun reasonUrgentOpen24h(): String = "24시간 열려 있어요"
    open fun reasonUrgentOpen(): String = "지금 영업 중이에요"
    open fun reasonUrgentOpenUnknown(): String = "영업 여부를 확인하지 못했어요"
    open fun reasonUrgentDirection(): String = "유턴 없이 진행 방향에 있어요"
    open fun reasonUrgentUTurn(): String = "유턴이 필요해요"
    open fun reasonUrgentSafety(bell: Boolean, cctv: Boolean): String = when {
        bell && cctv -> "비상벨과 CCTV가 있어요"
        bell -> "비상벨이 있어요"
        else -> "CCTV가 있어요"
    }
    open fun reasonUrgentDiaper(): String = "기저귀 교환대가 있어요"
    open fun reasonUrgentShelterSafety(): String = "졸음쉼터는 비상벨·CCTV가 기본 시설이에요"

    // ---------------- 이유: 다음 추천 ----------------

    open fun reasonNextAccepted(): String = "경유지로 넣기로 한 곳이에요"
    open fun reasonNextNearestRestroom(): String = "가장 빨리 갈 수 있는 화장실이에요"

    /** "화장실 가고 싶대"에 답한 곳 (코드 NEXT_REQUESTED_RESTROOM) */
    open fun reasonNextRequestedRestroom(): String = "화장실이 필요하다고 해서 찾은 다음 화장실이에요"

    /** 수유·반려견 요청에 답한 곳 (코드 NEXT_REQUESTED_CARE) */
    open fun reasonNextRequestedCare(): String = "잠깐 쉬어 갈 수 있는 다음 정차지예요"
    open fun reasonLastChanceCongestion(congestionMin: Int): String =
        "이곳을 지나면 ${duration(congestionMin)} 정체 구간에 화장실이 없어요"
    open fun reasonLastChanceGap(gapMin: Int): String = "이곳을 지나면 다음 화장실까지 ${duration(gapMin)} 걸려요"
    open fun reasonLastChanceGroup(occupants: Int): String = "${occupants}명이 타서, 그 사이 누군가 필요해질 수 있어요"
    open fun reasonLastChancePreference(gapMin: Int, limitMin: Int): String =
        "다음 화장실까지 ${duration(gapMin)}, 기준 ${duration(limitMin)}보다 길어요"
    open fun reasonNextRest(): String = "쉬어 가기 좋은 가장 가까운 곳이에요"
    /** 일상 길에서 고른 가까운 화장실 (CoachInput.nearby) */
    open fun reasonNextNearbyRestroom(): String = "가까운 화장실이에요"
    open fun reasonNextMeal(): String = "식사할 수 있는 가장 가까운 곳이에요"

    // ---------------- 피트스톱 계획 메모 ----------------

    open fun planNoCandidates(): String = "경로 앞 정차 후보가 없어요"
    open fun planGapTooLong(): String = "화장실 사이가 먼 구간이 있어 기준을 다 지킬 수는 없어요. 가장 덜 기다리는 계획이에요"
    open fun planNow(stop: String): String = "지금 쉬어야 할 때예요. ${stop}에 바로 들르세요"

    // ---------------- 출발 전 한마디 ----------------

    /**
     * 서 있고 목적지가 있을 때. [gaugeMin]: 화장실 게이지(가장 급한 사람이 필요할 확률 50%까지), [who]: 그 사람 (혼자면 null).
     * 판단은 더 이른 30% 마감으로 했으므로 게이지가 도착보다 길 수도 있다: 그때는 사람·분을 말하지 않는다
     */
    open fun departureCheck(tripMin: Int, gaugeMin: Int?, who: String?): String {
        val trip = duration(tripMin)
        val head = "도착까지 약 $trip${copula(trip)}."
        return when {
            gaugeMin != null && gaugeMin < tripMin && who != null ->
                "$head $who${topic(who)} ${duration(gaugeMin)}쯤 뒤 화장실을 찾을 것 같아요. 출발 전에 다녀오세요"
            gaugeMin != null && gaugeMin < tripMin -> "$head ${duration(gaugeMin)}쯤 뒤 화장실이 필요해질 것 같아요. 출발 전에 다녀오세요"
            else -> "$head 가는 길에 화장실이 필요할 수 있어요. 출발 전에 다녀오세요"
        }
    }
    open fun reasonDepartureTrip(tripMin: Int): String = "도착까지 ${duration(tripMin)}"
    /** 출발 전 질문에 '응' 했을 때 */
    open fun departureAccepted(): String = "좋아요. 다녀오세요"

    /** 서 있는 곳에서 할 일 ("화장실·휴식·식사") */
    open fun planHereWhat(purposes: Set<Purpose>): String = Purpose.entries.filter { it in purposes }.joinToString("·") {
        when (it) {
            Purpose.RESTROOM -> "화장실"
            Purpose.REST -> "휴식"
            Purpose.MEAL -> "식사"
            Purpose.FUEL -> "주유"
            Purpose.FEEDING -> "수유"
            Purpose.PET -> "반려견 산책"
        }
    }

    /** 지금 필요가 다음 후보보다 먼저 온다: 서 있는 곳에서 마치고 출발 */
    open fun planHereNow(stop: String, purposes: Set<Purpose>, dwellMin: Int): String {
        val what = planHereWhat(purposes)
        return "지금 쉬어야 할 때예요. 서 있는 ${stop}에서 ${what}${obj(what)} 마치고 출발하세요(${duration(dwellMin)})"
    }

    /** 급하지는 않다: 서 있는 곳에서 다녀온다고 보고 세운 계획 */
    open fun planHere(stop: String, purposes: Set<Purpose>): String {
        val what = planHereWhat(purposes)
        return "서 있는 ${stop}에서 ${what}${obj(what)} 마치고 출발한다고 보고 세운 계획이에요"
    }
    open fun planNoneNeeded(hasDestination: Boolean): String =
        if (hasDestination) "목적지까지 쉬지 않고 가도 돼요" else "앞의 후보까지는 쉬지 않고 가도 돼요"
    open fun planMeal(stop: String): String = "식사 시간에 맞춰 ${stop}에서 식사해요"
    open fun planFuel(stop: String): String = "${stop}에서 주유해요"
    open fun planFuelShort(): String = "남은 주행가능거리 안에 경로 위 주유할 곳이 없어요. 가까운 주유소를 따로 찾아 주세요"
    open fun planNoDestination(): String = "목적지가 없어 앞의 후보까지만 계획했어요"
    open fun planPartial(km: Int): String = "앞 ${km}km 안의 휴게소까지만 계획했어요"
    open fun planSleepyTime(): String = "도착 전에 졸음이 오기 쉬운 시간에 들어가요. 쉬는 곳에서 커피와 짧은 낮잠을 계획해 보세요"

    // ---------------- 조사 ----------------

    /** 이/가 */
    protected fun subj(word: String): String = if (hasFinalConsonant(word)) "이" else "가"

    /** 을/를 */
    protected fun obj(word: String): String = if (hasFinalConsonant(word)) "을" else "를"

    /** 은/는 */
    protected fun topic(word: String): String = if (hasFinalConsonant(word)) "은" else "는"

    /** 이에요/예요 */
    protected fun copula(word: String): String = if (hasFinalConsonant(word)) "이에요" else "예요"

    /** 마지막 글자에 받침이 있는지. 괄호 주석("안성휴게소(부산방향)")은 건너뛰고, 숫자는 읽는 소리로 본다 */
    internal fun hasFinalConsonant(word: String): Boolean {
        var i = word.length - 1
        while (i >= 0 && word[i].isWhitespace()) i--
        if (i >= 0 && word[i] == ')') {
            val open = word.lastIndexOf('(', i)
            if (open > 0) i = open - 1
        }
        while (i >= 0 && word[i].isWhitespace()) i--
        if (i < 0) return false
        val c = word[i]
        if (c in '가'..'힣') return (c.code - 0xAC00) % 28 != 0
        if (c.isDigit()) return c in "013678" // 영, 일, 삼, 육, 칠, 팔
        return c.lowercaseChar() in "ln" // 영문은 대략: ~l(을), ~n(은). m은 '미터'로 읽으니 받침 없음
    }
}
