package com.alexey.aircast

import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID

class CompanionException(message: String) : Exception(message)

/**
 * Companion client (pyatv 0.18.0): TCP 49153 -> Pair-Setup (SRP, same
 * cryptography as HapPairing, but TLV messages inside OPACK PS/PV frames
 * over raw TCP instead of HTTP POST /pair-setup) -> Pair-Verify -> encrypted
 * session (ChaCha20-Poly1305, pyatv nonce layout) -> connect() steps from
 * pyatv/protocols/companion/api.py:135-159 -> _launchApp (api.py:279-289).
 *
 * Credentials are stored SEPARATELY from AirPlay HAP credentials, under
 * identity "companion:<host>" in the same CredStore. The stored ORDER is
 * the app's HapCredentials order (clientId:clientSecret:serverId:
 * serverPublic) for internal consistency — pyatv serializes the same four
 * fields in a DIFFERENT order (hex(ltpk):hex(ltsk):hex(atv_id):
 * hex(client_id), hap_pairing.py HapCredentials.__str__), so a pyatv
 * credential string must NOT be pasted into this store verbatim.
 *
 * Port: pyatv takes the port from mDNS _companion-link._tcp.local and
 * NEVER hardcodes 49153; 49153 is only the observed value, used here as
 * the default with an explicit TODO (this test build has no mDNS lookup
 * for the Companion service yet).
 *
 * XID: random start in 0..2^16, +1 per message (protocol.py); responses
 * are matched by _x, events (_t=1) are logged, never fatal. Response
 * timeout 5.0 s (protocol.py DEFAULT_TIMEOUT). An error reply carries
 * "_em" (pyatv raises ProtocolError on it); "_ec"/"_ed" pyatv does NOT
 * parse — this client reports them too (on tvOS 27.2 a refusal may come
 * as a code without a message).
 *
 * Live-TV caveat (pyatv #2939, and the direct-cast history in this app):
 * _launchApp may return success while the TV shows nothing. Judge ONLY
 * by the TV screen, never by the returned dict.
 */
class CompanionClient(
    val host: String,
    val port: Int = DEFAULT_PORT,
    private val credStore: CredStore? = null,
    private val deviceName: String = "AirCast",
    /**
     * Pre-shared session keys (out, in) — TEST ONLY (tests/mock_companion.py).
     * Skips Pair-Setup/Pair-Verify entirely: the mock cannot run an SRP
     * server, so both sides use these fixed keys. A real TV never takes
     * this path.
     */
    private val testSessionKeys: Pair<ByteArray, ByteArray>? = null,
) {
    private var connection: CompanionConnection? = null
    private var xid: Int = SecureRandom().nextInt(65536)
    private var pairing: PairingState? = null
    private var creds: HapCredentials? = null
    var sessionId: Long = 0
        private set

    val identity: String get() = companionIdentity(host)
    fun isPaired(): Boolean = credStore?.has(identity) == true

    // ---------- low level ----------

    fun connect() {
        if (connection?.isConnected == true) return
        val c = CompanionConnection(host, port)
        c.connect()
        c.setReadTimeout(RESPONSE_TIMEOUT_MS)
        connection = c
    }

    fun close() {
        runCatching { connection?.close() }
        connection = null
        pairing = null
    }

    /** Test-only: TCP connect + enable encryption with pre-shared keys, no pairing. */
    fun connectTestSession() {
        val keys = testSessionKeys ?: throw CompanionException("testSessionKeys not set")
        connect()
        connection!!.enableEncryption(keys.first, keys.second)
    }

    private fun conn(): CompanionConnection =
        connection ?: throw CompanionException("не подключено к Companion")

    /** Fire-and-forget OPACK send (pyatv protocol.send_opack): adds _x. */
    fun sendOpack(frameType: Int, data: LinkedHashMap<Any?, Any?>) {
        if (!data.containsKey("_x")) data["_x"] = xid++
        conn().sendFrame(frameType, Opack.pack(data))
    }

    /**
     * OPACK request/response (pyatv protocol.exchange_opack): sends an
     * E_OPACK frame with a fresh _x, waits up to 5 s for the Response
     * (_t=3) with the same _x. Events (_t=1) are logged and skipped.
     */
    fun exchangeOpack(frameType: Int, data: LinkedHashMap<Any?, Any?>): Map<Any?, Any?> {
        val myXid = xid
        data["_x"] = myXid
        xid++
        conn().sendFrame(frameType, Opack.pack(data))
        val deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS
        while (true) {
            val (type, payload) = try {
                conn().readFrame()
            } catch (e: SocketTimeoutException) {
                throw CompanionException("нет ответа Companion за 5 с (xid $myXid)")
            }
            if (System.currentTimeMillis() > deadline) throw CompanionException("нет ответа Companion за 5 с (xid $myXid)")
            val unpacked = try {
                Opack.unpack(payload).first
            } catch (e: Exception) {
                Log.add("companion", "не удалось разобрать OPACK-ответ (тип $type): ${e.message}")
                continue
            }
            if (unpacked !is Map<*, *>) {
                Log.add("companion", "OPACK-ответ не словарь (тип $type): $unpacked")
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val resp = unpacked as Map<Any?, Any?>
            val msgType = (resp["_t"] as? Number)?.toInt()
                ?: (resp["_t"] as? Opack.SizedInt)?.value?.toInt()
            if (msgType == 1) {
                Log.add("companion", "событие ${resp["_i"]}: ${resp["_c"]}")
                continue
            }
            if (msgType == 3) {
                val respXid = (resp["_x"] as? Number)?.toLong()
                    ?: (resp["_x"] as? Opack.SizedInt)?.value
                if (respXid != myXid.toLong()) {
                    Log.add("companion", "ответ с чужим _x=$respXid (ждали $myXid), пропуск")
                    continue
                }
                checkResponseError(resp)
                return resp
            }
            Log.add("companion", "OPACK-кадр с неизвестным _t=$msgType: $resp")
        }
    }

    /**
     * Auth exchange (pyatv protocol.exchange_auth): PS/PV frames carry no
     * _x; the reply is dispatched by FRAME TYPE — PS_Start is answered by
     * PS_Next, PV_Start by PV_Next. A TLV Error inside _pd is an
     * authentication failure (auth.py _get_pairing_data).
     */
    fun exchangeAuth(frameType: Int, data: LinkedHashMap<Any?, Any?>): Map<Any?, Any?> {
        val expectedReply = when (frameType) {
            CompanionFrameType.PS_START -> CompanionFrameType.PS_NEXT
            CompanionFrameType.PV_START -> CompanionFrameType.PV_NEXT
            else -> frameType
        }
        conn().sendFrame(frameType, Opack.pack(data))
        val deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS
        while (true) {
            val (type, payload) = try {
                conn().readFrame()
            } catch (e: SocketTimeoutException) {
                throw CompanionException("нет ответа Companion за 5 с (auth-кадр $frameType)")
            }
            if (System.currentTimeMillis() > deadline) throw CompanionException("нет ответа Companion за 5 с (auth-кадр $frameType)")
            if (type != expectedReply) {
                Log.add("companion", "auth: ждали кадр $expectedReply, пришёл $type — пропуск")
                continue
            }
            val unpacked = Opack.unpack(payload).first
            if (unpacked !is Map<*, *>) throw CompanionException("auth-ответ не словарь: $unpacked")
            @Suppress("UNCHECKED_CAST")
            val resp = unpacked as Map<Any?, Any?>
            checkResponseError(resp)
            val pd = resp["_pd"] as? ByteArray
                ?: throw CompanionException("auth-ответ без _pd: $resp")
            val tlv = Tlv8.decode(pd)
            tlv[TLV_ERROR]?.let {
                throw CompanionException("ТВ отклонил спаривание (TLV error ${it.firstOrNull()})")
            }
            return resp
        }
    }

    /** "_em" -> ProtocolError in pyatv; "_ec" without "_em" is also an error, not silence. */
    private fun checkResponseError(resp: Map<Any?, Any?>) {
        val em = resp["_em"] as? String
        if (em != null) throw CompanionException("ТВ: $em")
        val ec = resp["_ec"]
        if (ec != null) {
            val code = (ec as? Number)?.toLong() ?: (ec as? Opack.SizedInt)?.value ?: ec.toString()
            val domain = resp["_ed"] as? String
            val msg = resp["_em"] as? String
            throw CompanionException("ТВ отказал: код $code" + (if (domain != null) ", домен $domain" else "") + (if (msg != null) ": $msg" else " (без текста ошибки)"))
        }
    }

    private fun pairingData(resp: Map<Any?, Any?>): Map<Int, ByteArray> {
        val pd = resp["_pd"] as? ByteArray ?: throw CompanionException("ответ без _pd: $resp")
        return Tlv8.decode(pd)
    }

    // ---------- Pair-Setup ----------

    private class PairingState(
        val salt: ByteArray,
        val serverSrpPublic: ByteArray,
    )

    /**
     * Pair-Setup step 1 (auth.py CompanionPairSetupProcedure.start_pairing):
     * PS_Start with TLV {Method:0, SeqNo:1} + "_pwTy":1. The TV shows the
     * PIN on screen at this point — there is NO separate /pair-pin-start
     * for Companion (pairing.py: device_provides_pin=True). The reply
     * carries the SRP salt + server public key. The connection stays open:
     * finishPairing() continues it.
     */
    fun startPairing() {
        connect()
        val m1 = Tlv8().apply {
            add(TLV_METHOD, byteArrayOf(0x00))
            add(TLV_SEQNO, byteArrayOf(0x01))
        }.encode()
        val resp = exchangeAuth(
            CompanionFrameType.PS_START,
            linkedMapOf<Any?, Any?>("_pd" to m1, "_pwTy" to 1),
        )
        val tlv = pairingData(resp)
        val salt = tlv[TLV_SALT] ?: throw CompanionException("PS ответ без salt")
        val serverPub = tlv[TLV_PUBLIC_KEY] ?: throw CompanionException("PS ответ без public key")
        pairing = PairingState(salt, serverPub)
        Log.add("companion", "Pair-Setup M2: salt ${salt.size} B, server key ${serverPub.size} B — PIN должен быть на экране ТВ")
    }

    /**
     * Pair-Setup steps M3–M6 (auth.py finish_pairing + hap_srp.py
     * step1/step2/step3/step4). SRP proof of the server is NOT verified
     * here — same as the existing HapPairing.finishSetup in this app
     * (Srp6a has no server-proof check); the M4 proof is only logged.
     * Returns the credentials and saves them to the CredStore.
     */
    fun finishPairing(pin: String): HapCredentials {
        val state = pairing ?: throw CompanionException("сначала «Показать PIN на ТВ»")
        val pin4 = pin.trim().padStart(4, '0') // pyatv: str(pin).zfill(4)
        if (pin4.length != 4 || !pin4.all { it.isDigit() }) throw CompanionException("PIN — 4 цифры с экрана ТВ")
        val srp = Srp6a("Pair-Setup", pin4)
        srp.process(state.salt, state.serverSrpPublic)
        val m3 = Tlv8().apply {
            add(TLV_SEQNO, byteArrayOf(0x03))
            add(TLV_PUBLIC_KEY, srp.publicKey())
            add(TLV_PROOF, srp.proof())
        }.encode()
        val m4resp = exchangeAuth(
            CompanionFrameType.PS_NEXT,
            linkedMapOf<Any?, Any?>("_pd" to m3, "_pwTy" to 1),
        )
        val m4 = pairingData(m4resp)
        if (m4[TLV_PROOF] == null) Log.add("companion", "Pair-Setup M4: ответ без Proof (проверка подписи сервера в Srp6a отсутствует — как в HapPairing)")
        else Log.add("companion", "Pair-Setup M4: proof ТВ получен (${m4[TLV_PROOF]!!.size} B)")
        val k = srp.sharedKey()
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ltpk = Curve25519.ed25519PublicKey(seed)
        val clientId = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)
        val controllerX = Hkdf.expand("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", k)
        val sessionKey = Hkdf.expand("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", k)
        val signature = Curve25519.ed25519Sign(seed, controllerX + clientId + ltpk)
        val inner = Tlv8().apply {
            add(TLV_IDENTIFIER, clientId)
            add(TLV_PUBLIC_KEY, ltpk)
            add(TLV_SIGNATURE, signature)
            // hap_srp.step3: Name — OPACK {"name": <имя>} внутри TLV.
            add(TLV_NAME, Opack.pack(linkedMapOf<Any?, Any?>("name" to deviceName)))
        }.encode()
        val cipher = ChaCha20Poly1305(sessionKey, sessionKey)
        val m5 = Tlv8().apply {
            add(TLV_SEQNO, byteArrayOf(0x05))
            add(TLV_ENCRYPTED_DATA, cipher.encrypt(inner, nonce = "PS-Msg05".toByteArray()))
        }.encode()
        val m6resp = exchangeAuth(
            CompanionFrameType.PS_NEXT,
            linkedMapOf<Any?, Any?>("_pd" to m5, "_pwTy" to 1),
        )
        val m6 = pairingData(m6resp)
        val encrypted = m6[TLV_ENCRYPTED_DATA] ?: throw CompanionException("M6 без данных")
        val server = Tlv8.decode(cipher.decrypt(encrypted, nonce = "PS-Msg06".toByteArray()))
        val serverId = server[TLV_IDENTIFIER] ?: throw CompanionException("M6 без identifier")
        val serverLtpk = server[TLV_PUBLIC_KEY] ?: throw CompanionException("M6 без public key")
        // pyatv step4 returns HapCredentials(atv_pub_key, auth_private,
        // atv_identifier, pairing_id); the app's HapCredentials field order
        // is (clientId, clientSecret, serverId, serverPublic) — same four
        // values, app order (see class doc: do NOT paste pyatv strings).
        val creds = HapCredentials(clientId, seed, serverId, serverLtpk)
        credStore?.save(identity, creds)
        this.creds = creds
        Log.add("companion", "Pair-Setup готов; спарен с ${String(serverId)}")
        close()
        return creds
    }

    // ---------- Pair-Verify + session keys ----------

    /**
     * Pair-Verify (auth.py CompanionPairVerifyProcedure.verify_credentials
     * + hap_srp.py verify1/verify2). Derives the session keys
     * (HKDF-SHA512, salt "", info "ClientEncrypt-main"/"ServerEncrypt-main",
     * 32 B) and enables session encryption. The M4 reply to PV_Next is
     * read by exchangeAuth; pyatv leaves a "TODO: check status code" there
     * — we check the TLV Error inside _pd like any other auth reply.
     */
    fun pairVerify(creds: HapCredentials) {
        connect()
        val ephemeral = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ourPublic = Curve25519.x25519Public(ephemeral)
        val v1 = Tlv8().apply {
            add(TLV_SEQNO, byteArrayOf(0x01))
            add(TLV_PUBLIC_KEY, ourPublic)
        }.encode()
        val r1 = exchangeAuth(
            CompanionFrameType.PV_START,
            linkedMapOf<Any?, Any?>("_pd" to v1, "_auTy" to 4),
        )
        val m2 = pairingData(r1)
        val serverPublic = m2[TLV_PUBLIC_KEY] ?: throw CompanionException("verify M2 без ключа")
        val encrypted = m2[TLV_ENCRYPTED_DATA] ?: throw CompanionException("verify M2 без данных")
        val shared = Curve25519.x25519(ephemeral, serverPublic)
        val sessionKey = Hkdf.expand("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
        val cipher = ChaCha20Poly1305(sessionKey, sessionKey)
        val inner = Tlv8.decode(cipher.decrypt(encrypted, nonce = "PV-Msg02".toByteArray()))
        val id = inner[TLV_IDENTIFIER] ?: throw CompanionException("verify: нет identifier")
        val sig = inner[TLV_SIGNATURE] ?: throw CompanionException("verify: нет signature")
        if (!id.contentEquals(creds.serverId)) throw CompanionException("verify: другое устройство")
        // hap_srp.verify1: подпись сервера над server_pubkey + identifier + our_pubkey.
        if (!Curve25519.ed25519Verify(sig, creds.serverPublic, serverPublic + id + ourPublic)) {
            throw CompanionException("verify: плохая подпись устройства")
        }
        Log.add("companion", "Pair-Verify M2: подпись сервера ПРОВЕРЕНА")
        // M3: подпись контроллера — НАШ публичный ключ + наш id + публичный ключ сервера
        // (тот же порядок, что в HapPairing.verify).
        val ourSig = Curve25519.ed25519Sign(creds.clientSecret, ourPublic + creds.clientId + serverPublic)
        val m3inner = Tlv8().apply {
            add(TLV_IDENTIFIER, creds.clientId)
            add(TLV_SIGNATURE, ourSig)
        }.encode()
        val v3 = Tlv8().apply {
            add(TLV_SEQNO, byteArrayOf(0x03))
            add(TLV_ENCRYPTED_DATA, cipher.encrypt(m3inner, nonce = "PV-Msg03".toByteArray()))
        }.encode()
        exchangeAuth(CompanionFrameType.PV_NEXT, linkedMapOf<Any?, Any?>("_pd" to v3))
        val outKey = Hkdf.expand("", "ClientEncrypt-main", shared)
        val inKey = Hkdf.expand("", "ServerEncrypt-main", shared)
        conn().enableEncryption(outKey, inKey)
        this.creds = creds
        Log.add("companion", "Pair-Verify готов; сессия Companion зашифрована")
    }

    // ---------- connect() steps (api.py:135-159) ----------

    /**
     * Connect steps in pyatv order (api.py connect()):
     * system_info() -> _touch_start() -> _session_start() ->
     * _tv_rc_session_start() -> _text_input_start() -> subscribe _iMC.
     * TVRCSessionStart is best-effort in pyatv itself ("not supported"
     * is swallowed). _tiStart is best-effort here TOO, deliberately
     * deviating from api.py: it only serves text input, which _launchApp
     * does not need — a _tiStart failure must not block launching VLC.
     * _touch_start and _session_start stay required, as in api.py.
     */
    fun connectSteps() {
        val c = creds ?: throw CompanionException("нет credentials для system_info")
        val clientIdStr = String(c.clientId, Charsets.UTF_8)
        sendCommand("_systemInfo", linkedMapOf<Any?, Any?>(
            "_bf" to 0,
            "_cf" to 512,
            "_clFl" to 128,
            "_i" to clientIdStr,
            "_idsID" to c.clientId,
            "_pubID" to clientIdStr,
            "_sf" to 256,
            "_sv" to "170.18",
            "model" to "AirCast",
            "name" to deviceName,
        ))
        Log.add("companion", "_systemInfo ok")
        sendCommand("_touchStart", linkedMapOf<Any?, Any?>(
            "_height" to 1000.0, "_tFl" to 0, "_width" to 1000.0,
        ))
        Log.add("companion", "_touchStart ok")
        val localSid = SecureRandom().nextLong() and 0xFFFFFFFFL
        val sessResp = sendCommand("_sessionStart", linkedMapOf<Any?, Any?>(
            "_srvT" to "com.apple.tvremoteservices", "_sid" to localSid,
        ))
        val content = sessResp["_c"] as? Map<*, *>
            ?: throw CompanionException("_sessionStart без _c")
        val remoteSid = (content["_sid"] as? Number)?.toLong()
            ?: (content["_sid"] as? Opack.SizedInt)?.value
            ?: throw CompanionException("_sessionStart без _sid")
        sessionId = (remoteSid shl 32) or localSid
        Log.add("companion", "_sessionStart ok, sid=0x${sessionId.toString(16)}")
        try {
            sendCommand("TVRCSessionStart", linkedMapOf<Any?, Any?>("ProtocolVersionKey" to "1.2"))
            Log.add("companion", "TVRCSessionStart ok")
        } catch (e: Exception) {
            // api.py _tv_rc_session_start: "TVRCSessionStart not supported" — swallowed.
            Log.add("companion", "TVRCSessionStart не поддержан: ${e.message} (не блокирует запуск)")
        }
        try {
            sendCommand("_tiStart", linkedMapOf())
            Log.add("companion", "_tiStart ok")
        } catch (e: Exception) {
            Log.add("companion", "_tiStart не удался: ${e.message} (нужен только для ввода текста — запуск VLC не блокирует)")
        }
        // subscribe_event("_iMC"): fire-and-forget event, no response waited for.
        sendOpack(CompanionFrameType.E_OPACK, linkedMapOf<Any?, Any?>(
            "_i" to "_interest", "_t" to 1,
            "_c" to linkedMapOf<Any?, Any?>("_regEvents" to listOf("_iMC")),
        ))
    }

    // ---------- commands ----------

    /**
     * _send_command (api.py:160-176): {"_i": id, "_t": 2, "_c": content}
     * as an E_OPACK frame; _x is added by exchangeOpack.
     */
    fun sendCommand(identifier: String, content: Map<Any?, Any?>): Map<Any?, Any?> {
        val data = LinkedHashMap<Any?, Any?>()
        data["_i"] = identifier
        data["_t"] = 2
        data["_c"] = content
        return exchangeOpack(CompanionFrameType.E_OPACK, data)
    }

    /**
     * _launchApp (api.py:279-289): content is EXACTLY ONE key — "_urlS"
     * if the argument has a URL scheme (pyatv support/url.py
     * is_url_or_scheme: urlparse + bool(scheme)), else "_bundleID".
     * A vlc-x-callback:// deep link therefore goes as "_urlS" with the
     * whole string as the value.
     */
    fun launchApp(target: String): Map<Any?, Any?> {
        val key = if (isUrlOrScheme(target)) "_urlS" else "_bundleID"
        Log.add("companion", "_launchApp $key=$target")
        val resp = sendCommand("_launchApp", linkedMapOf<Any?, Any?>(key to target))
        Log.add("companion", "_launchApp: ТВ принял команду (ответ _t=3). Это НЕ подтверждение воспроизведения — смотри на экран ТВ.")
        return resp
    }

    /**
     * v0.9.1: пробуждение ТВ перед кастом (Companion turn_on, НЕ
     * Wake-on-LAN: WoL по Wi-Fi на Apple TV ненадёжен — см. RESEARCH).
     * pyatv/protocols/companion/api.py turn_on = hid_command(False,
     * HidCommand.Wake) → _send_command("_hidC", {"_hBtS": 2,
     * "_hidC": 13}), Wake = 13. Подключение + Pair-Verify +
     * connect steps (best-effort) + _hidC, затем close.
     * Возвращает true, если команда ушла (ответ _t=3) — но даже
     * при false движок всё равно опрашивает доступность: команда
     * могла дойти при таймауте ответа.
     * v0.9.2: ВАЖНО — wake работает только при живом Companion
     * (connect на 49153): пока ТВ спит, connect падает и _hidC
     * не уходит вообще. Спящий ТВ будится коннектом после mDNS
     * (sleep proxy: ТВ виден в mDNS и отвечает на connect),
     * а не этой функцией — см. CastEngine.ensureTvReady.
     */
    fun wakeTv(): Boolean {
        return try {
            connect()
            if (testSessionKeys != null) {
                conn().enableEncryption(testSessionKeys.first, testSessionKeys.second)
            } else {
                val c = credStore?.load(identity)
                    ?: run { Log.add("companion", "wake: ТВ не спарен"); return false }
                pairVerify(c)
            }
            runCatching { connectSteps() }
            val resp = sendCommand("_hidC", linkedMapOf<Any?, Any?>("_hBtS" to 2, "_hidC" to 13))
            val ok = resp["_t"]?.toString() == "3"
            Log.add("companion", "wake: _hidC отправлен, ответ _t=${resp["_t"]}")
            ok
        } catch (e: Exception) {
            Log.add("companion", "wake error: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            close()
        }
    }

    /**
     * Full one-shot flow for the UI: connect -> Pair-Verify (or pre-shared
     * test keys) -> connect steps -> _launchApp with the VLC deep link.
     * Closes the connection afterwards. Throws CompanionException with a
     * human-readable message on any failure.
     */
    fun connectAndLaunchVlc(streamUrl: String, subUrl: String? = null): Map<Any?, Any?> {
        try {
            connect()
            if (testSessionKeys != null) {
                conn().enableEncryption(testSessionKeys.first, testSessionKeys.second)
            } else {
                val c = credStore?.load(identity)
                    ?: throw CompanionException("Companion не спарен — сначала «Показать PIN» и «Спарить по PIN»")
                pairVerify(c)
            }
            connectSteps()
            return launchApp(vlcDeepLink(streamUrl, subUrl))
        } finally {
            close()
        }
    }

    /**
     * v0.9.3 (#14): список запускаемых приложений (pyatv companion
     * api.py:291 app_list → FetchLaunchableApplicationsEvent, ответ
     * _c = {bundle_id: name}). Возвращает карту или null (ошибка /
     * нет ответа — это НЕ «VLC нет»: вызывающий трактует null как
     * UNKNOWN и не блокирует каст). Требует живого соединения
     * (connect + Pair-Verify — до вызова).
     */
    fun fetchLaunchableApps(): Map<String, String>? {
        return try {
            val resp = sendCommand("FetchLaunchableApplicationsEvent", emptyMap<Any?, Any?>())
            @Suppress("UNCHECKED_CAST")
            (resp["_c"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                if (k is String && v is String) k to v else null
            }?.toMap()
        } catch (e: Exception) {
            Log.add("companion", "app_list error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * v0.9.3 (#14): VLC установлен? true/false/null (неизвестно —
     * не блокировать каст). Совпадение — по bundle id (содержит
     * "vlc") или имени ("VLC").
     */
    fun isVlcInstalled(): Boolean? {
        val apps = fetchLaunchableApps() ?: return null
        Log.add("companion", "app_list: ${apps.size} приложений")
        return isVlcInApps(apps)
    }

    /**
     * v0.9.3 (#14): одноразовая проверка установки VLC (connect →
     * Pair-Verify → FetchLaunchableApplicationsEvent → close).
     * true/false/null (неизвестно: не спарен / ошибка / нет ответа).
     */
    fun checkVlcInstalled(): Boolean? {
        return try {
            connect()
            if (testSessionKeys != null) {
                conn().enableEncryption(testSessionKeys.first, testSessionKeys.second)
            } else {
                val c = credStore?.load(identity)
                    ?: run { Log.add("companion", "app_list: ТВ не спарен"); return null }
                pairVerify(c)
            }
            runCatching { connectSteps() }
            isVlcInstalled()
        } catch (e: Exception) {
            Log.add("companion", "app_list check error: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            close()
        }
    }

    companion object {
        const val DEFAULT_PORT = 49153
        const val RESPONSE_TIMEOUT_MS = 5000

        // Тестовые URL, проверенные curl 2026-11-10 (см. бриф v0.5.0).
        // Google sample bucket BigBuckBunny — 403, НЕ использовать.
        const val VLC_MP4_URL = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_1MB.mp4"
        const val VLC_HLS_URL = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8"

        fun companionIdentity(host: String): String = "companion:$host"

        /**
         * v0.9.3 (#14): есть ли VLC в карте app_list (чистая логика,
         * JVM-тесты): bundle id содержит "vlc" или имя — "VLC".
         */
        fun isVlcInApps(apps: Map<String, String>): Boolean =
            apps.any { (id, name) -> id.contains("vlc", ignoreCase = true) || name.equals("VLC", ignoreCase = true) }

        /** vlc-x-callback://x-callback-url/stream?url=<URL-encoded stream URL> (полный encode, включая :// и ?/&).
         * v0.9.0: опциональный subUrl — внешний файл субтитров
         * (параметр sub=, changelog VLC iOS 3.6.6: «URL decode the
         * subtitles parameter for x-callback-url»; сторонние
         * интеграции передают &sub=<encoded> и для VLC, и для
         * Infuse; на tvOS — живая проверка, см. README).
         * sub= — страховка к HLS-мастеру с SUBTITLES-группой:
         * если один из путей не сработает на tvOS, сработает
         * другой; для mp4 sub= — единственный путь субтитров. */
        fun vlcDeepLink(streamUrl: String, subUrl: String? = null): String {
            var link = "vlc-x-callback://x-callback-url/stream?url=" + URLEncoder.encode(streamUrl, "UTF-8")
            if (!subUrl.isNullOrBlank()) link += "&sub=" + URLEncoder.encode(subUrl, "UTF-8")
            return link
        }

        /** pyatv support/url.py is_url_or_scheme: urlparse succeeds and has a scheme. */
        fun isUrlOrScheme(s: String): Boolean =
            Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*$").matches(s)

        private const val TLV_METHOD = 0x00
        private const val TLV_IDENTIFIER = 0x01
        private const val TLV_SALT = 0x02
        private const val TLV_PUBLIC_KEY = 0x03
        private const val TLV_PROOF = 0x04
        private const val TLV_ENCRYPTED_DATA = 0x05
        private const val TLV_SEQNO = 0x06
        private const val TLV_ERROR = 0x07
        private const val TLV_SIGNATURE = 0x0A
        private const val TLV_NAME = 0x11
    }
}
