#!/usr/bin/env bash
# Runs the instrumented tests on the booted emulator and collects screenshots, logcat and the
# test output into emulator-output/, whether or not the tests pass.
# Used by .github/workflows/emulator.yml; also works locally with a device or emulator attached.
#
# The tests run through `am instrument` rather than connectedAndroidTest, so each test's
# result shows in the log as it finishes, and a hanging test is visible and limited.
set -u

out=emulator-output
app_package=com.vorsk.privatednstoggle.dev
mkdir -p "$out"

./gradlew installDebug installDebugAndroidTest --console=plain || exit 1

adb shell rm -rf /data/local/tmp/screenshots
adb logcat -c

# Each test may take 3 minutes, the whole run 20
timeout 1200 adb shell am instrument -w \
    -e timeout_msec 180000 \
    "$app_package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrument.txt"

status=0
grep -q '^OK (' "$out/instrument.txt" || status=1

echo "--- what was on screen when tests failed ---"
adb logcat -d -s FailureEvidence:E || true
echo "--- app crashes in logcat ---"
adb logcat -d -b crash || true
adb pull /data/local/tmp/screenshots "$out/" || true
adb logcat -d > "$out/logcat.txt" || true

exit "$status"
