#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Boots an emulator and runs the notification study device test.
# Everything is logged into ci-out/ so the results can be read without the Actions UI.
set -uo pipefail
mkdir -p ci-out/screenshots
exec > >(tee -a ci-out/emulator.log) 2>&1
set -x

API="${API_LEVEL:-35}"
IMAGE="system-images;android-${API};google_apis;x86_64"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

yes | "$SDKMANAGER" --licenses > /dev/null || true
for attempt in 1 2 3; do
  "$SDKMANAGER" --install emulator platform-tools "platforms;android-${API}" "$IMAGE" > ci-out/sdkmanager.log 2>&1 && break
  sleep 30
done
tail -5 ci-out/sdkmanager.log
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"

echo no | "$AVDMANAGER" create avd --force -n notifystudy -k "$IMAGE" -d pixel_6
printf 'hw.ramSize=3072\ndisk.dataPartition.size=6G\n' >> "$HOME/.android/avd/notifystudy.avd/config.ini"

emulator -avd notifystudy -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot \
  -camera-back none -camera-front none > ci-out/emulator-process.log 2>&1 &

adb wait-for-device
booted=0
for i in $(seq 1 180); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then booted=1; break; fi
  sleep 5
done
if [ "$booted" != "1" ]; then
  echo "emulator did not boot"
  tail -50 ci-out/emulator-process.log
  exit 1
fi
sleep 20
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put global hide_error_dialogs 1
adb shell svc power stayon true
adb shell input keyevent 82
adb shell wm dismiss-keyguard || true
adb shell rm -rf /sdcard/Download/notifystudy
adb logcat -c
adb logcat '*:I' > ci-out/logcat-full.txt 2>&1 &

./gradlew :AnkiDroid:connectedPlayDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ichi2.anki.notifystudy.NotifyStudyDeviceTest \
  --daemon > ci-out/device.log 2>&1
code=$?
echo "test exit code: $code"
tail -60 ci-out/device.log

adb pull /sdcard/Download/notifystudy/. ci-out/screenshots/ || true
adb exec-out screencap -p > ci-out/screenshots/zz_final_screen.png || true
cp -r AnkiDroid/build/outputs/androidTest-results ci-out/ 2>/dev/null || true
grep -E "notify study|NotifyStudy|AndroidRuntime|FATAL|TestRunner" ci-out/logcat-full.txt | tail -3000 > ci-out/logcat.txt || true
rm -f ci-out/logcat-full.txt
adb emu kill || true
exit $code
