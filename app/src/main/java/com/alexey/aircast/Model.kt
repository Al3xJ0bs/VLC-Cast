package com.alexey.aircast

/**
 * Discovered AirPlay receiver. `features` is the 64-bit bitmask from the
 * "ft"/"features" TXT record ("0xLOW,0xHIGH"): bit 0 VideoV1, bit 38
 * UnifiedMediaControl, bit 43 SystemPairing, bit 48 CoreUtils, bit 49 VideoV2
 * (pyatv protocols/airplay/utils.py AirPlayFlags).
 *
 * Pure Kotlin (no Android imports) so the AirPlay client is unit-testable on JVM.
 */
data class AirPlayDevice(
    val name: String,
    val host: String,
    val port: Int,
    val txt: Map<String, String>,
    val features: Long,
    val model: String,
    val deviceId: String,
) {
    // Identity for credential storage: the host IP. Stable across discovery
    // and /server-info (deviceId can arrive later and must not change the key).
    val identity: String get() = host
    val supportsV2: Boolean get() = (features and ((1L shl 38) or (1L shl 48))) != 0L
    val supportsV1: Boolean get() = (features and 1L) != 0L
    override fun toString(): String =
        "$name ($host:$port, model=$model, features=0x${features.toString(16)}, V1=$supportsV1 V2=$supportsV2)"
}

object Features {
    /** "0xLOW" or "0xLOW,0xHIGH" -> 64-bit bitmask. */
    fun parse(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return try {
            val parts = s.split(",")
            val low = parts[0].trim().removePrefix("0x").ifEmpty { "0" }.toLong(16) and 0xFFFFFFFFL
            val high = if (parts.size > 1) parts[1].trim().removePrefix("0x").ifEmpty { "0" }.toLong(16) and 0xFFFFFFFFL else 0L
            (high shl 32) or low
        } catch (e: Exception) {
            Log.add("discovery", "features parse error for '$s': ${e.message}")
            0L
        }
    }

    fun describe(features: Long): String {
        val bits = ArrayList<String>()
        if (features and 1L != 0L) bits.add("VideoV1")
        if (features and (1L shl 1) != 0L) bits.add("Photo")
        if (features and (1L shl 7) != 0L) bits.add("Screen")
        if (features and (1L shl 9) != 0L) bits.add("Audio")
        if (features and (1L shl 27) != 0L) bits.add("LegacyPairing")
        if (features and (1L shl 38) != 0L) bits.add("UnifiedMediaControl")
        if (features and (1L shl 43) != 0L) bits.add("SystemPairing")
        if (features and (1L shl 48) != 0L) bits.add("CoreUtils")
        if (features and (1L shl 49) != 0L) bits.add("VideoV2")
        return bits.joinToString(", ").ifEmpty { "нет известных битов" }
    }
}

/** Minimal credential-store interface (so the AirPlay client is testable on JVM). */
interface CredStore {
    fun load(identity: String): HapCredentials?
    fun save(identity: String, creds: HapCredentials)
    fun has(identity: String): Boolean
    /** Drop stored credentials for one device (v0.1.3: "Спарить заново" after M4 error 2). */
    fun clear(identity: String)
}

/**
 * Long-term HAP pairing credentials (what pyatv calls HapCredentials).
 * v0.6.0: moved here from HapPairing.kt — the AirPlay Pair-Setup class
 * is gone with mirroring/direct cast, but Companion pairing still stores
 * its credentials in this shape (same SharedPreferences keys, so
 * update-over-install keeps the pairing).
 */
data class HapCredentials(
    val clientId: ByteArray,
    val clientSecret: ByteArray, // Ed25519 seed (32 bytes)
    val serverId: ByteArray,
    val serverPublic: ByteArray, // Ed25519 public key (32 bytes)
) {
    fun serialize(): String = listOf(clientId, clientSecret, serverId, serverPublic).joinToString(":") { hex(it) }

    companion object {
        fun parse(s: String?): HapCredentials? {
            val parts = s?.split(":") ?: return null
            if (parts.size != 4) return null
            return runCatching {
                HapCredentials(unhex(parts[0]), unhex(parts[1]), unhex(parts[2]), unhex(parts[3]))
            }.getOrNull()
        }

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * Last-known facts about one TV, persisted across runs (v0.2.2: a manually
 * entered TV keeps the model/features from the last successful mDNS
 * discovery instead of showing features=0x0, V1=false V2=false).
 * Stored in SharedPreferences "aircast" as lastDeviceHost / lastDeviceName /
 * lastDeviceFeatures (hex) / lastDeviceModel / lastDeviceId. Pure Kotlin.
 */
data class RememberedDevice(
    val host: String,
    val name: String,
    val features: Long,
    val model: String,
    val deviceId: String,
) {
    /** True when the remembered facts are usable (a real discovery was seen). */
    val known: Boolean get() = features != 0L
    fun toDevice(): AirPlayDevice =
        AirPlayDevice(name, host, 7000, emptyMap(), features, model, deviceId)
    companion object {
        fun from(dev: AirPlayDevice): RememberedDevice =
            RememberedDevice(dev.host, dev.name, dev.features, dev.model, dev.deviceId)
    }
}

/**
 * Fallback CredStore без KeyStore: только на случай, если конструктор
 * CredentialStore (SharedPreferences) бросит исключение. Спаривание
 * при этом не сохраняется между запусками, но запуск не падает.
 * Отдельный класс (не в MainActivity), чтобы его могли использовать
 * и CastService, и MainActivity, и JVM-тесты.
 */
class MemoryCredStore : CredStore {
    private val map = HashMap<String, HapCredentials>()
    override fun load(identity: String): HapCredentials? = map[identity]
    override fun save(identity: String, creds: HapCredentials) { map[identity] = creds }
    override fun has(identity: String): Boolean = map.containsKey(identity)
    override fun clear(identity: String) { map.remove(identity) }
}

/**
 * Build the device for a manually entered IP. When this exact TV was
 * discovered before (remembered != null, known, same host), reuse its
 * model/features/V1/V2 instead of the blank "?" / 0x0 placeholder;
 * otherwise return the blank manual device. Never invents facts: a
 * different IP, or no remembered facts, stays unknown (0x0).
 */
fun rememberedManualDevice(ip: String, remembered: RememberedDevice?): AirPlayDevice {
    if (remembered != null && remembered.known && remembered.host == ip) return remembered.toDevice()
    return AirPlayDevice("Apple TV (вручную)", ip, 7000, emptyMap(), 0L, "?", ip)
}
