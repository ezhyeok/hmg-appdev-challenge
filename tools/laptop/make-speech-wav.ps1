# STT 점검용 음성 파일을 만든다 (노트북에서 실행). Windows에 들어 있는 한국어 음성(SAPI, ko-KR)으로 짧은 대답을 합성해
# 16kHz·16bit·mono WAV로 저장한다. Pleos STT의 sendAudio가 받는 형식이다 (가이드 sending-audio-data.md).
# 에뮬레이터를 오디오 없이(-no-audio) 띄우므로 음성 인식 결과는 이 파일을 넣어서만 확인할 수 있다 (tools/laptop/stt-pcm-check.sh).
# 네트워크를 쓰지 않는다. 결과: logs\stt\<이름>.wav
param([string]$OutDir = "$PSScriptRoot\..\..\..\logs\stt")
Add-Type -AssemblyName System.Speech
$phrases = [ordered]@{
  "eung"   = "응"
  "ne"     = "네, 들를게요"
  "ani"    = "아니, 괜찮아"
  "geuphae" = "화장실 급해"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$ko = $s.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq "ko-KR" } | Select-Object -First 1
if (-not $ko) {
  "FAIL: 한국어(ko-KR) 음성이 없다. 설치된 음성:"
  $s.GetInstalledVoices() | ForEach-Object { "  " + $_.VoiceInfo.Name + " " + $_.VoiceInfo.Culture }
  exit 1
}
$s.SelectVoice($ko.VoiceInfo.Name)
"voice: " + $ko.VoiceInfo.Name
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
foreach ($k in $phrases.Keys) {
  $path = Join-Path $OutDir "$k.wav"
  $s.SetOutputToWaveFile($path, $fmt)
  # 앞뒤에 짧은 무음을 둔다: 말 시작을 잡는 끝점 검출(EPD)이 첫 음절을 놓치지 않게
  $pb = New-Object System.Speech.Synthesis.PromptBuilder
  $pb.AppendBreak([TimeSpan]::FromMilliseconds(400))
  $pb.AppendText($phrases[$k])
  $pb.AppendBreak([TimeSpan]::FromMilliseconds(600))
  $s.Speak($pb)
  $s.SetOutputToNull()
  "{0} {1} bytes '{2}'" -f $path, (Get-Item $path).Length, $phrases[$k]
}
$s.Dispose()
