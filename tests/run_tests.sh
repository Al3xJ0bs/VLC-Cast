#!/bin/bash
# JVM unit tests for the platform-independent core (crypto, bplist, TLV,
# AutoCast share/auto-select logic). No Android device needed.
# Uses kotlinc 1.4.31 + JDK 11 (same as the app build).
#
# v0.6.0: mirror tests (MirrorTest.kt, FairPlayTest.kt) deleted with the
# mirror; WizardFlow/EventChannel/HapPairing files gone too.
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME=$HOME/tools/jdk11
export PATH=$HOME/tools/jdk11/bin:$PATH

python3 - <<'EOF'
import plistlib
doc = {
    "model": "AppleTV5,3",
    "features": 0x1234567890,
    "flag": True,
    "title": "Привет",
    "nested": {"arr": [1, 2, 3], "data": bytes([1, 2, 3, 4])},
}
open("tests/fixture.bplist", "wb").write(plistlib.dumps(doc, fmt=plistlib.FMT_BINARY))
EOF
rm -f tests/kotlin.bplist

KOTLINC=$HOME/tools/kotlinc/bin/kotlinc
# Never trust a stale test.jar: a kotlinc failure must fail this script,
# not leave yesterday's jar to run green.
rm -f tests/test.jar
# android.jar is compile-only (kept for parity with the app build).
AJAR=$HOME/android-sdk/platforms/android-34/android.jar
$KOTLINC -cp "$AJAR" -d tests/test.jar \
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
  app/src/main/java/com/alexey/aircast/Discovery.kt \
  app/src/main/java/com/alexey/aircast/LayoutContract.kt \
  app/src/main/java/com/alexey/aircast/VideoExtractor.kt \
  app/src/main/java/com/alexey/aircast/YouTube.kt \
  app/src/main/java/com/alexey/aircast/DashManifest.kt \
  app/src/main/java/com/alexey/aircast/StreamProxy.kt \
  app/src/main/java/com/alexey/aircast/StreamProbe.kt \
  app/src/main/java/com/alexey/aircast/EpisodeList.kt \
  app/src/main/java/com/alexey/aircast/Subtitles.kt \
  app/src/main/java/com/alexey/aircast/TitleCleaner.kt \
  app/src/main/java/com/alexey/aircast/YouTubePlaylist.kt \
  app/src/main/java/com/alexey/aircast/CastQueue.kt \
  app/src/main/java/com/alexey/aircast/PlaybackMemory.kt \
  app/src/main/java/com/alexey/aircast/OpenSubtitles.kt \
  app/src/main/java/com/alexey/aircast/UpdateCheck.kt \
  app/src/main/java/com/alexey/aircast/UpdateGate.kt \
  app/src/main/java/com/alexey/aircast/AppUpdater.kt \
  app/src/main/java/com/alexey/aircast/LocalFileCast.kt \
  app/src/main/java/com/alexey/aircast/MasterWait.kt \
  app/src/main/java/com/alexey/aircast/ResumePlaylist.kt \
  app/src/main/java/com/alexey/aircast/TvCheck.kt \
  app/src/main/java/com/alexey/aircast/LogShareActivity.kt \
  app/src/main/java/com/alexey/aircast/LogFileProvider.kt \
  app/src/main/java/com/alexey/aircast/AutoCast.kt \
  app/src/main/java/com/alexey/aircast/AudioCast.kt \
  app/src/main/java/com/alexey/aircast/FileShareRoute.kt \
  app/src/main/java/com/alexey/aircast/CastEngine.kt \
  app/src/main/java/com/alexey/aircast/CastService.kt \
  app/src/main/java/com/alexey/aircast/ShareReceiverActivity.kt \
  tests/TestMain.kt tests/OpackTest.kt 2>&1 | grep -v "illegal reflective" | grep -v "Please consider" | grep -v "Use --illegal" | grep -v "^WARNING"
# v0.9.0: -Djava.net.preferIPv4Stack=true — ОБЯЗАТЕЛЬНО: JVM этой
# песочницы по умолчанию ходит через IPv6, а IPv6-loopback здесь
# мёртв (Java-сокеты к 127.0.0.1 сбрасываются/таймаутятся, сервер
# запроса вообще не видит; curl к тому же серверу работает).
# Без флага probe-тесты против локального HTTP-сервера падают
# (HTTP_ERROR/TIMEOUT вместо OK). На телефоне Алексея это не влияет
# ни на что — флаг только для JVM-тестов здесь. Тот же флаг уже
# стоит в run_mock_test.sh (mock Companion 6/6).
java -Djava.net.preferIPv4Stack=true -cp tests/test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar com.alexey.aircast.test.TestMainKt
java -Djava.net.preferIPv4Stack=true -cp tests/test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar com.alexey.aircast.test.OpackTestKt
