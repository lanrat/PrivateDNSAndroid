#!/usr/bin/env bash
# Runs the instrumented tests on the booted emulator and collects screenshots, logcat and
# reports into emulator-output/, whether or not the tests pass.
# Used by .github/workflows/emulator.yml; also works locally with a device or emulator attached.
set -u

out=emulator-output
mkdir -p "$out"
adb shell rm -rf /data/local/tmp/screenshots
adb logcat -c

status=0
./gradlew connectedDebugAndroidTest --console=plain || status=$?

python3 .github/scripts/print-test-failures.py app/build/outputs/androidTest-results || true
echo "--- app crashes in logcat ---"
adb logcat -d -b crash || true

adb pull /data/local/tmp/screenshots "$out/" || true
adb logcat -d > "$out/logcat.txt" || true
cp -r app/build/reports/androidTests "$out/reports" 2>/dev/null || true
cp -r app/build/outputs/androidTest-results "$out/results" 2>/dev/null || true

exit "$status"
