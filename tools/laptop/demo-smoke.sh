#!/bin/bash
# 시연 시나리오 점검 (개발 서버에서 실행). 앱을 다시 시작하며 시연을 켜고,
# 서비스 로그에 'demo started: <이름>'이 찍히는지 본다. 찍히지 않으면 실패로 끝난다.
#
# 회귀 방지: 2026-11-11 에뮬레이터 통합 실행에서 첫 실행 때 시연 명령이 사라졌다
# (명령 큐가 구독 전 값을 버리는 SharedFlow였음, 지금은 Channel + CoachStoreTest).
# 이미 떠 있는 앱 창에 다시 보내는 인텐트는 Pleos 홈 화면이 창을 다시 띄우는 것으로 처리해 서비스까지
# 오지 않을 수 있어서, 여기서는 `am start -S`(앱 재시작)로 보낸다.
#
# 사용: tools/laptop/demo-smoke.sh [holiday|fatigue|urgent|stop|departure|off] [--split]
#   off는 시연을 끄고 실제 신호로 돌아간다. 차량 상태(기어·차속)는 tools/vc/vc.sh로 따로 맞춘다
#   --split: 시연이 시작된 뒤 창 막대를 끌어 앱을 portrait 창으로 나누고(화면이 다시 만들어진다), 시연이 처음부터
#            다시 시작되지 않는지 본다 (2026-11-11 회귀: 화면 재생성 때 처음 실행 인텐트의 시연 옵션을 다시 보냈다).
#            앱이 landscape(두 창)로 떠 있을 때 쓴다. 좌표는 2560x1440 Pleos Connect 에뮬레이터 기준
set -u
ADB='C:\Users\User\hmg-appdev-challenge\sdk\platform-tools\adb.exe'
NAME="${1:-holiday}"
SPLIT=0; [ "${2:-}" = "--split" ] && SPLIT=1
EXTRA="--es demo $NAME"
[ "$NAME" = "off" ] && EXTRA="--es demo off"

adb_() { timeout "${T:-30}" ssh -o ConnectTimeout=10 hmg-laptop "$ADB $*" 2>&1 | tr -d '\r'; }

state=$(adb_ get-state)
if [ "$state" != "device" ]; then echo "FAIL: emulator not connected ($state)"; exit 2; fi
adb_ logcat -c >/dev/null
T=60 adb_ shell am start -S -n dev.shimpyo.app/.MainActivity $EXTRA | tail -1
for _ in $(seq 1 20); do
  line=$(adb_ logcat -d -s Shimpyo:I | grep -E "demo (requested|started)" | tail -1)
  case "$line" in
    *"demo started: $NAME"*|*"demo started: off"*) echo "OK  $line"; break ;;
  esac
  sleep 2
done
case "$line" in
  *"demo started:"*) ;;
  *) echo "FAIL: 'demo started: $NAME' not logged within 40 s. Last: ${line:-none}"
     echo "      check: tools/laptop/emu-health.ps1 (emulator suspended?) and adb logcat -s Shimpyo"
     exit 1 ;;
esac
# 정차 후보 표가 곧 올라와야 한다 (2026-11-11 회귀: JSON 6만여 행을 읽느라 1분 넘게 후보가 비었다 → stops.bin)
for _ in $(seq 1 10); do
  loaded=$(adb_ logcat -d -s Shimpyo:I | grep "stop table loaded" | tail -1)
  [ -n "$loaded" ] && break
  sleep 2
done
if [ -n "$loaded" ]; then echo "OK  ${loaded#*Shimpyo : }"; else echo "FAIL: stop table not loaded within 20 s of the demo start"; exit 1; fi
[ "$SPLIT" = "1" ] || exit 0

# 창 막대(오른쪽 끝)를 왼쪽으로 끌어 앱을 portrait 창으로 나눈다 → 화면이 다시 만들어진다
adb_ shell input swipe 2550 720 1700 720 1200 >/dev/null
sleep 12
starts=$(adb_ logcat -d -s Shimpyo:I | grep -c "demo started:")
if [ "$starts" -eq 1 ]; then echo "OK  window split: demo kept running (1 start)"; exit 0; fi
echo "FAIL: demo restarted after the window split ($starts starts)"; exit 1
