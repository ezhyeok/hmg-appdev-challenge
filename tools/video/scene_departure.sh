#!/bin/bash
# 출발 전 장면 녹화 (일상 확장): 시연 시작(창 나누기) → 녹화 1 → 출발 전 카드 → '다녀올게요' → 문 열고 닫기 → (정차 3분) →
# 녹화 2 → 화장실 게이지가 다시 찬다 · 다음 화장실은 가까운 일상 정차 후보.
# 화장실 정차는 시연 시각으로 정차 3분이라 녹화 한 번(screenrecord 최대 175초)에 다 들어가지 않는다: 둘로 나눠 녹화한다.
# 좌표는 2560x1440 Pleos Connect 에뮬레이터의 나눈 창(오른쪽 portrait 창) 기준
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$REPO/build/video/clips"
ADB='C:\Users\User\hmg-appdev-challenge\sdk\platform-tools\adb.exe'
tap() { ssh hmg-laptop "$ADB shell input tap $1 $2" >/dev/null 2>&1; }
record() { # $1 파일 이름, $2 초
  ssh hmg-laptop "$ADB shell rm -f /sdcard/$1.mp4" >/dev/null 2>&1
  timeout $(( $2 + 25 )) ssh hmg-laptop "$ADB shell screenrecord --size 1920x1080 --bit-rate 10000000 --time-limit $2 /sdcard/$1.mp4"
}
pull() {
  ssh hmg-laptop "$ADB pull /sdcard/$1.mp4 C:\\Users\\User\\hmg-appdev-challenge\\logs\\$1.mp4" | tr -d '\r' | tail -1
  scp -q "hmg-laptop:C:/Users/User/hmg-appdev-challenge/logs/$1.mp4" "$OUT/$2" && ls -la "$OUT/$2"
}
mkdir -p "$OUT"
cd "$REPO"
tools/vc/vc.sh speed 0; tools/vc/vc.sh gear Park
tools/laptop/demo-smoke.sh departure --split
echo "demo since $(date +%T)"
( record rec_dep1 175 ) &
REC=$!
sleep 7
tap 1076 1065; echo "다녀올게요 $(date +%T)"; sleep 4
tools/vc/vc.sh door fl open; sleep 3; tools/vc/vc.sh door fl close; echo "door $(date +%T)"
wait $REC
record rec_dep2 45
pull rec_dep1 departure1.mp4
pull rec_dep2 departure2.mp4
