#!/bin/bash
# 귀성길 장면 녹화: 시연 시작(창 나누기) → 녹화 → 카드 뜨면 9초 뒤 '들르기' → 길안내 시작 뒤 15초 → 녹화 끝 → 서버로 가져오기
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$REPO/build/video/clips"
ADB='C:\Users\User\hmg-appdev-challenge\sdk\platform-tools\adb.exe'
mkdir -p "$OUT"
cd "$REPO"
tools/vc/vc.sh gear Drive; tools/vc/vc.sh speed 100 20
tools/laptop/demo-smoke.sh holiday --split
ssh hmg-laptop "$ADB shell rm -f /sdcard/rec_holiday.mp4" >/dev/null 2>&1
( timeout 200 ssh hmg-laptop "$ADB shell screenrecord --size 1920x1080 --bit-rate 10000000 --time-limit 170 /sdcard/rec_holiday.mp4" ) &
REC=$!
echo "recording since $(date +%T)"
timeout 230 ssh hmg-laptop 'powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\User\hmg-appdev-challenge\repo\tools\video\tap-after.ps1 -DelayMs 9000 -WaitS 170' | tr -d '\r'
sleep 15
ssh hmg-laptop "$ADB shell pkill -INT screenrecord" >/dev/null 2>&1
wait $REC
sleep 2
ssh hmg-laptop "$ADB pull /sdcard/rec_holiday.mp4 C:\\Users\\User\\hmg-appdev-challenge\\logs\\rec_holiday.mp4" | tr -d '\r' | tail -1
scp -q 'hmg-laptop:C:/Users/User/hmg-appdev-challenge/logs/rec_holiday.mp4' "$OUT/holiday.mp4" && ls -la "$OUT/holiday.mp4"
tools/vc/vc.sh speed 0; tools/vc/vc.sh gear Park
