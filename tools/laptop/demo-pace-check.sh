#!/bin/bash
# 시연 빠르기 점검 (개발 서버에서 실행). 귀성길 시연에서 질문 카드가 뜨면 시연이 4배 느려지고(로그 'demo pace 0.25'),
# 카드가 내려가면 원래대로('demo pace 1.0') 돌아오는지, 카드가 실제로 몇 초 떠 있었는지 서비스 로그로 본다.
#
# 회귀 방지: 2026-11-13 귀성길 카드가 실제 13~25초 만에 지나가 PT에서 카드를 설명하고 누를 시간이 빠듯했다.
# 이제 카드가 떠 있는 동안 시연 시각과 위치가 함께 느려진다 (CoachWorld.questionShown, CoachCoreTest가 가상 시간으로 지킨다).
#
# 먼저: tools/vc/vc.sh up; tools/vc/vc.sh gear Drive; tools/vc/vc.sh speed 100   (시연은 D + 차속에서만 묻는다)
#       tools/laptop/demo-smoke.sh holiday   (앱을 다시 시작하며 시연을 켠다. 로그를 비운다)
# 그 뒤 카드에 대답하지 않고 이 스크립트를 돌린다. 사용: tools/laptop/demo-pace-check.sh [최대 대기 초, 기본 240]
set -u
ADB='C:\Users\User\hmg-appdev-challenge\sdk\platform-tools\adb.exe'
WAIT="${1:-240}"
adb_() { timeout "${T:-30}" ssh -o ConnectTimeout=10 hmg-laptop "$ADB $*" 2>&1 | tr -d '\r'; }

state=$(adb_ get-state)
if [ "$state" != "device" ]; then echo "FAIL: emulator not connected ($state)"; exit 2; fi

# 로그 한 줄의 시각(epoch 초, 소수 셋째 자리까지). -v epoch는 줄 앞에 '   1763000000.123'을 붙인다
ts() { awk '{print $1}' <<<"$1"; }

deadline=$(( $(date +%s) + WAIT ))
shown=""; hidden=""; slow=""; normal=""
while [ "$(date +%s)" -lt "$deadline" ]; do
  log=$(adb_ logcat -d -v epoch -s Shimpyo:I)
  shown=$(grep "prompt shown: LAST_CHANCE" <<<"$log" | head -1)
  if [ -n "$shown" ]; then
    after=$(awk -v t="$(ts "$shown")" '$1 >= t' <<<"$log")
    slow=$(grep "demo pace 0.25" <<<"$after" | head -1)
    hidden=$(grep "prompt hidden" <<<"$after" | head -1)
    [ -n "$hidden" ] && normal=$(awk -v t="$(ts "$hidden")" '$1 >= t' <<<"$log" | grep "demo pace 1.0" | head -1)
    [ -n "$normal" ] && break
  fi
  sleep 3
done

if [ -z "$shown" ]; then echo "FAIL: no LAST_CHANCE card within ${WAIT}s (gear D and speed set? demo-smoke.sh holiday run?)"; exit 1; fi
echo "OK  ${shown#*Shimpyo : }"
if [ -z "$slow" ]; then echo "FAIL: the demo did not slow down when the card was shown (no 'demo pace 0.25')"; exit 1; fi
gap=$(awk -v a="$(ts "$shown")" -v b="$(ts "$slow")" 'BEGIN{printf "%.1f", b-a}')
echo "OK  slowed ${gap}s after the card: ${slow#*Shimpyo : }"
if [ -z "$hidden" ] || [ -z "$normal" ]; then echo "FAIL: the card did not go down, or the demo did not return to full speed, within ${WAIT}s"; exit 1; fi
up=$(awk -v a="$(ts "$shown")" -v b="$(ts "$hidden")" 'BEGIN{printf "%.0f", b-a}')
echo "OK  card up ${up}s, then ${normal#*Shimpyo : }"
# 진입로 2km 전에 물어도 500m 앞까지 1.5km ÷ 50m/s = 30초. 카드 자체의 수명(45초 + 음성 뒤 누를 시간 10초)보다 길지 않다
if [ "$up" -lt 30 ]; then echo "FAIL: the card was up only ${up}s (expected 30 s or more)"; exit 1; fi
if [ "$up" -gt 70 ]; then echo "FAIL: the card stayed ${up}s (expected at most about 57 s)"; exit 1; fi
echo "PASS"
