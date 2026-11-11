package dev.shimpyo.app.ui

import dev.shimpyo.coach.CoachUiState

/**
 * 서비스가 이번 프로세스에서 한 번이라도 계산을 냈는지. `serviceRunning = false`가 '시작 전'인지 '돌다가 멈춤'인지 가른다
 * (시작 전에 "코치가 멈췄어요"라고 하지 않게). CoachStore는 프로세스 안에서만 살아 있어서 프로세스가 새로 뜨면 false다.
 */
fun coachEverRan(state: CoachUiState): Boolean = state.restroom != null || state.fatigue != null
