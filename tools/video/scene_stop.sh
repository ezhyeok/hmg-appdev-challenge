#!/bin/bash
# 정차 장면 녹화: 시연 시작(창 나누기) → 녹화 → 계획 세우기 → 주행 화면 → 문 열고 닫기 → (정차 2분 넘게) → 출발('식사 뒤') → 다시 정차 → 기록
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$REPO/build/video/clips"
ADB='C:\Users\User\hmg-appdev-challenge\sdk\platform-tools\adb.exe'
tap() { ssh hmg-laptop "$ADB shell input tap $1 $2" >/dev/null 2>&1; }
mkdir -p "$OUT"
cd "$REPO"
tools/vc/vc.sh speed 0; tools/vc/vc.sh gear Park
tools/laptop/demo-smoke.sh stop --split
T0=$(date +%s)
ssh hmg-laptop "$ADB shell rm -f /sdcard/rec_stop.mp4" >/dev/null 2>&1
( timeout 200 ssh hmg-laptop "$ADB shell screenrecord --size 1920x1080 --bit-rate 10000000 --time-limit 175 /sdcard/rec_stop.mp4" ) &
REC=$!
echo "recording since $(date +%T)"
sleep 8
tap 1074 170; sleep 2; tap 1124 378; sleep 4; tap 1274 528; echo "plan $(date +%T)"; sleep 12
tap 1074 170; sleep 2; tap 1124 278; echo "drive $(date +%T)"; sleep 4
tools/vc/vc.sh door fl open; sleep 4; tools/vc/vc.sh door fl close; echo "door $(date +%T)"
# 시연은 10배속: 정차 시작(시연 시작)부터 실제 125초 = 가상 20분 넘게 → 식사 정차
while [ $(( $(date +%s) - T0 )) -lt 128 ]; do sleep 2; done
tools/vc/vc.sh gear Drive; tools/vc/vc.sh speed 60 20; echo "depart $(date +%T)"; sleep 12
tools/vc/vc.sh speed 0; tools/vc/vc.sh gear Park; sleep 3
tap 1074 170; sleep 2; tap 1124 578; echo "history $(date +%T)"; sleep 9
ssh hmg-laptop "$ADB shell pkill -INT screenrecord" >/dev/null 2>&1
wait $REC
sleep 2
ssh hmg-laptop "$ADB pull /sdcard/rec_stop.mp4 C:\\Users\\User\\hmg-appdev-challenge\\logs\\rec_stop.mp4" | tr -d '\r' | tail -1
scp -q 'hmg-laptop:C:/Users/User/hmg-appdev-challenge/logs/rec_stop.mp4' "$OUT/stop.mp4" && ls -la "$OUT/stop.mp4"
