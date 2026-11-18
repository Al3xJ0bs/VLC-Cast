package com.alexey.aircast.test

import com.alexey.aircast.CompanionClient
import com.alexey.aircast.CredStore
import com.alexey.aircast.HapCredentials
import com.alexey.aircast.Log
import java.io.File

/**
 * End-to-end test against tests/mock_companion.py (Companion _launchApp).
 * Proves the Kotlin Companion client connects with a pre-shared ChaCha
 * session (pyatv nonce layout) and sends the exact _launchApp dict
 * (api.py:279-289) with the full VLC deep link.
 *
 * v0.6.0: mirror sections (11-15: mock-ТВ 7010/7011/7012, FairPlay SAP,
 * RTSP, AVCC frames, TimeAnnounce, dump verifier) deleted with the
 * mirror — зеркало удалено из приложения полностью. Direct-cast
 * sections (1-10) were removed in v0.5.2 with the dead direct cast.
 *
 * The mock does NOT implement an SRP server — both sides use PRE-SHARED
 * keys (same constants as mock_companion.py); pairing against the real
 * TV is verified only by Alexey's live run.
 *
 * Run: tests/run_mock_test.sh
 */
class MemStore : CredStore {
    private val map = HashMap<String, HapCredentials>()
    override fun load(identity: String): HapCredentials? = map[identity]
    override fun save(identity: String, creds: HapCredentials) { map[identity] = creds }
    override fun has(identity: String): Boolean = map.containsKey(identity)
    override fun clear(identity: String) { map.remove(identity) }
}

fun main() {
    Log.init(File("tests"))
    var failures = 0
    fun check(name: String, cond: Boolean, extra: String = "") {
        if (cond) println("PASS $name") else { println("FAIL $name $extra"); failures++ }
    }
    fun mIntFrom(text: String, key: String): Int =
        Regex("\"$key\":\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    // v0.5.0: Companion + _launchApp против tests/mock_companion.py
    // (TCP 49153, OPACK из pyatv, ChaCha-сессия с pyatv-раскладкой nonce).
    // Мок НЕ реализует SRP-сервер — обе стороны используют PRE-SHARED
    // ключи (те же константы, что в mock_companion.py); спаривание против
    // реального ТВ проверяется только живым прогоном Алексея.
    // Проверяется точный словарь _launchApp (api.py:279-289) и полный deep link.
    val compOutKey = ByteArray(32) { it.toByte() } // == bytes(range(32)) in mock_companion.py
    val compInKey = ByteArray(32) { (32 + it).toByte() } // == bytes(range(32, 64))
    val compDeepLink = CompanionClient.vlcDeepLink(CompanionClient.VLC_MP4_URL)
    check("mock-companion-deeplink", compDeepLink == "vlc-x-callback://x-callback-url/stream?url=https%3A%2F%2Ftest-videos.co.uk%2Fvids%2Fbigbuckbunny%2Fmp4%2Fh264%2F1080%2FBig_Buck_Bunny_1080_10s_1MB.mp4",
        "deepLink=$compDeepLink")
    check("mock-companion-urls-key", CompanionClient.isUrlOrScheme(compDeepLink) && !CompanionClient.isUrlOrScheme("com.apple.tv"))
    val compClient = CompanionClient("127.0.0.1", 49153, testSessionKeys = compOutKey to compInKey)
    var compResp: Map<Any?, Any?>? = null
    var compError: String? = null
    try {
        compClient.connectTestSession()
        compResp = compClient.launchApp(compDeepLink)
    } catch (e: Exception) {
        compError = "${e.javaClass.simpleName}: ${e.message}"
        println("  companion launch error: $compError")
    } finally {
        compClient.close()
    }
    val compT = (compResp?.get("_t") as? Number)?.toLong()
        ?: (compResp?.get("_t") as? com.alexey.aircast.Opack.SizedInt)?.value
    val compXid = (compResp?.get("_x") as? Number)?.toLong()
        ?: (compResp?.get("_x") as? com.alexey.aircast.Opack.SizedInt)?.value
    check("mock-companion-launchapp-response", compError == null && compT == 3L && compResp?.get("_i") == "_launchApp",
        "error=$compError resp=$compResp")
    Thread.sleep(500)
    val compSum = runCatching { File("tests/mock_companion_summary.json").readText() }.getOrDefault("")
    check("mock-companion-launchapp-dict", compSum.contains("\"_i\": \"_launchApp\"") &&
        compSum.contains("\"_t\": 2") && compSum.contains("\"_urlS\": \"$compDeepLink\"") &&
        !compSum.contains("_bundleID"),
        "summary=${compSum.take(600)}")
    check("mock-companion-launchapp-xid", compXid != null && mIntFrom(compSum, "xid") == compXid.toInt(),
        "respXid=$compXid summaryXid=${mIntFrom(compSum, "xid")}")
    check("mock-companion-frames", mIntFrom(compSum, "frames") >= 1, "frames=${mIntFrom(compSum, "frames")}")

    // v0.9.3 (#14): FetchLaunchableApplicationsEvent — мок отдаёт _c
    // с VLC, isVlcInstalled() == true (та же тестовая сессия).
    val appClient = CompanionClient("127.0.0.1", 49153, testSessionKeys = compOutKey to compInKey)
    var vlcInstalled: Boolean? = null
    var appError: String? = null
    try {
        appClient.connectTestSession()
        vlcInstalled = appClient.isVlcInstalled()
    } catch (e: Exception) {
        appError = "${e.javaClass.simpleName}: ${e.message}"
        println("  companion app_list error: $appError")
    } finally {
        appClient.close()
    }
    check("mock-companion-applist-vlc", appError == null && vlcInstalled == true, "error=$appError vlc=$vlcInstalled")

    println(if (failures == 0) "MOCK TESTS PASSED" else "$failures MOCK TESTS FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}
