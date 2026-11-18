#!/bin/bash
# End-to-end test of the Companion client against a mock Apple TV
# (Python). The mock speaks server-side Companion (OPACK, pre-shared
# keys); it proves protocol framing, NOT that a real tvOS 27.2 TV
# accepts the flow — that needs Alexey's live run.
#
# v0.6.0: mirror mocks 7010/7011/7012 removed with the mirror
# (FairPlay SAP, RTSP, ACD1 dump verifier, Annex-B negative control —
# all mirror-only). Direct-cast mocks 7001-7007 were removed in v0.5.2.
# Remaining mocks:
#   7000: plain HAP oracle for mock_strict_check.py (no MockTest section);
#   49153: mock Companion (OPACK, pre-shared keys) — _launchApp.
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME=$HOME/tools/jdk11
export PATH=$HOME/tools/jdk11/bin:$PATH

rm -f tests/mock_strict.log tests/mock_companion_summary.json tests/mock_companion.log
# The mock needs cryptography + srptools (system python3 has neither):
# use the project venv if present, else plain python3 (will fail loudly).
MOCK_PY=python3
if [ -x tests/venv/bin/python ]; then MOCK_PY=tests/venv/bin/python; fi
# v0.5.2: plain HAP oracle for the strict M3 check (see below) — no
# MockTest section runs against it.
setsid $MOCK_PY tests/mock_airplay.py --port 7000 --pin 1234 > tests/mock_strict.log 2>&1 < /dev/null &
MOCK_STRICT_PID=$!
# v0.5.0: mock Companion (TCP 49153) — OPACK из pyatv, pre-shared ключи
# (SRP-сервер мок не реализует, см. tests/mock_companion.py).
setsid $MOCK_PY tests/mock_companion.py --port 49153 --summary tests/mock_companion_summary.json > tests/mock_companion.log 2>&1 < /dev/null &
MOCK_COMP_PID=$!
trap 'kill $MOCK_STRICT_PID $MOCK_COMP_PID 2>/dev/null || true' EXIT
for i in $(seq 1 30); do
  if grep -q "MOCK READY" tests/mock_strict.log 2>/dev/null && grep -q "MOCK COMPANION READY" tests/mock_companion.log 2>/dev/null; then break; fi
  sleep 0.5
done
grep -q "MOCK READY" tests/mock_strict.log || { echo "mock 7000 (strict-check oracle) did not start"; cat tests/mock_strict.log; exit 1; }
grep -q "MOCK COMPANION READY" tests/mock_companion.log || { echo "mock companion 49153 did not start"; cat tests/mock_companion.log; exit 1; }

# Negative control for the strict mock (v0.1.3): proves the mock REJECTS a
# wrong-order M3 signature with M4 error 2 and ACCEPTS the correct order.
# If this fails, the mock is a rubber stamp again and its pass means nothing.
"$MOCK_PY" tests/mock_strict_check.py --port 7000 || { echo "MOCK STRICT CHECK FAILED"; cat tests/mock_strict.log; exit 1; }

KOTLINC=$HOME/tools/kotlinc/bin/kotlinc
# android.jar — compile-only (kept for parity with the app build).
"$KOTLINC" -cp "$HOME/android-sdk/platforms/android-34/android.jar" -d tests/mocktest.jar \
  app/src/main/java/com/alexey/aircast/Util.kt \
  app/src/main/java/com/alexey/aircast/Srp6a.kt \
  app/src/main/java/com/alexey/aircast/Crypto.kt \
  app/src/main/java/com/alexey/aircast/Curve25519.kt \
  app/src/main/java/com/alexey/aircast/Bplist.kt \
  app/src/main/java/com/alexey/aircast/Model.kt \
  app/src/main/java/com/alexey/aircast/Opack.kt \
  app/src/main/java/com/alexey/aircast/CompanionConnection.kt \
  app/src/main/java/com/alexey/aircast/CompanionClient.kt \
  app/src/main/java/com/alexey/aircast/AirPlayConnection.kt \
  app/src/main/java/com/alexey/aircast/LayoutContract.kt \
  app/src/main/java/com/alexey/aircast/VideoExtractor.kt \
  app/src/main/java/com/alexey/aircast/EpisodeList.kt \
  app/src/main/java/com/alexey/aircast/Subtitles.kt \
  app/src/main/java/com/alexey/aircast/AutoCast.kt \
  tests/MockTest.kt > tests/mocktest-kotlinc.log 2>&1
grep -E "error:" tests/mocktest-kotlinc.log && { echo "KOTLINC FAILED"; exit 1; } || true
# set -e: failing java must NOT abort the script before the log dumps.
set +e
java -Djava.net.preferIPv4Stack=true -cp tests/mocktest.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar com.alexey.aircast.test.MockTestKt
TEST_RC=$?
set -e
kill $MOCK_STRICT_PID $MOCK_COMP_PID 2>/dev/null || true
sleep 1
echo "--- mock log (49153 companion) ---"
cat tests/mock_companion.log 2>/dev/null || echo "(no log)"
echo "--- mock summary (49153 companion) ---"
cat tests/mock_companion_summary.json 2>/dev/null || echo "(no summary)"
exit $TEST_RC
