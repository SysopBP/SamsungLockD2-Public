#!/usr/bin/env bash
set -euo pipefail

adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put system screen_off_timeout 1800000
adb logcat -c
adb logcat -v time > guardian-runtime.log 2>&1 &
D2_LOGCAT_PID=$!
cleanup() {
  kill "$D2_LOGCAT_PID" 2>/dev/null || true
  wait "$D2_LOGCAT_PID" 2>/dev/null || true
}
trap cleanup EXIT
D2_TEST_EXIT=0
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=app.d2lock.ComboScreenshotTest,app.d2lock.ScreenshotCaptureTest --stacktrace || D2_TEST_EXIT=$?
cleanup
trap - EXIT
grep -E 'SamsungLockD2|GUARDIAN_|PinUi|PIN_' guardian-runtime.log || true
printf 'Runtime test exit: %s\n' "$D2_TEST_EXIT"
exit "$D2_TEST_EXIT"
