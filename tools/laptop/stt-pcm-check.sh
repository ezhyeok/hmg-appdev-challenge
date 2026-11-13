#!/bin/bash
# 음성 인식(STT) 점검 (개발 서버에서 실행, debug 빌드가 깔려 있어야 한다).
# 에뮬레이터를 오디오 없이(-no-audio) 띄우므로 마이크로는 인식 결과를 볼 수 없다. Windows 한국어 음성으로 만든 짧은 대답
# (make-speech-wav.ps1)을 앱 내부 저장소에 넣고, 디버그 점검(SdkProbe `stt-pcm`)이 SttKit.recognizePcm(sendAudio, ON_DEVICE)으로
# 인식시킨 결과를 logcat에서 읽는다. 앱의 대답 해석(IntentParser)이 그 문장을 '응/아니'로 읽는지는 코치 테스트가 따로 지킨다.
#
# 사용: tools/laptop/stt-pcm-check.sh [이름...]   (기본: eung ne ani geuphae)
set -u
ADB='C:\Users\User\hmg-appdev-challenge\sdk\platform-tools\adb.exe'
PS1='C:\Users\User\hmg-appdev-challenge\repo\tools\laptop\make-speech-wav.ps1'
WAVDIR='C:\Users\User\hmg-appdev-challenge\logs\stt'
NAMES=("$@"); [ ${#NAMES[@]} -eq 0 ] && NAMES=(eung ne ani geuphae)
adb_() { timeout "${T:-40}" ssh -o ConnectTimeout=10 hmg-laptop "$ADB $*" 2>&1 | tr -d '\r'; }

state=$(adb_ get-state)
if [ "$state" != "device" ]; then echo "FAIL: emulator not connected ($state)"; exit 2; fi
if ! adb_ shell pm path --user 10 dev.shimpyo.app | grep -q base.apk; then echo "FAIL: dev.shimpyo.app is not installed"; exit 2; fi

T=120 timeout 130 ssh hmg-laptop "powershell -NoProfile -ExecutionPolicy Bypass -File $PS1" 2>&1 | tr -d '\r' | grep -av "^$" || { echo "FAIL: could not make the speech files"; exit 1; }

fail=0
for n in "${NAMES[@]}"; do
  adb_ push "$WAVDIR\\$n.wav" /data/local/tmp/$n.wav >/dev/null
  adb_ shell chmod 644 /data/local/tmp/$n.wav >/dev/null
  # 앱(user 10)의 uid로 복사한다. run-as는 debug 빌드에서만 된다
  out=$(adb_ shell run-as dev.shimpyo.app --user 10 cp /data/local/tmp/$n.wav files/$n.wav)
  if [ -n "$out" ]; then echo "FAIL: copy into the app failed (debug build installed?): $out"; exit 2; fi
  adb_ logcat -c >/dev/null
  adb_ shell am broadcast --user current -a dev.shimpyo.app.DEV -p dev.shimpyo.app --es probe stt-pcm:$n.wav >/dev/null
  line=""
  for _ in $(seq 1 15); do
    sleep 2
    line=$(adb_ logcat -d -s PleosApp:I | grep "PROBE|stt-pcm|result" | tail -1)
    [ -n "$line" ] && break
  done
  if [ -z "$line" ]; then echo "FAIL $n: no result within 30 s"; fail=1; continue; fi
  result=${line#*PROBE|stt-pcm|}
  echo "$n: $result"
  case "$result" in
    "result 'null'"*|*"error="[!n]*) fail=1 ;;
  esac
done
[ $fail -eq 0 ] && echo "PASS (every file was recognized; compare the text with the spoken phrase above)" || { echo "FAIL (see above)"; exit 1; }
