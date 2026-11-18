package com.alexey.aircast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager

/**
 * mDNS discovery of _airplay._tcp (NsdManager). Also supports manual IP entry:
 * if mDNS finds nothing (гостевая сеть, изоляция клиентов), the user can type
 * the TV's IP and the app probes /server-info directly.
 *
 * v0.3.2: держим WifiManager.MulticastLock на время поиска — без него
 * NsdManager на realme/OPPO (агрессивное энергосбережение Wi-Fi)
 * периодически не видит вообще ничего: живые логи — ТВ находился за
 * ~1 с, а потом три окна по 15 с пустые без единой ошибки.
 * Разрешение CHANGE_WIFI_MULTICAST_STATE уже в манифесте.
 */
class Discovery(private val context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    private val found = LinkedHashMap<String, AirPlayDevice>()
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile var running = false
        private set

    fun start(onDevice: (AirPlayDevice) -> Unit, onLost: (String) -> Unit) {
        if (running) return
        running = true
        found.clear()
        // v0.3.2: MulticastLock — до discoverServices, чтобы multicast
        // не фильтровался прошивкой Wi-Fi. Не взят — пишем в лог и
        // ищем как раньше (хуже, чем ничего).
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("aircast-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.add("discovery", "MulticastLock ${if (multicastLock?.isHeld == true) "взят" else "НЕ взят (lock=null или не удерживается)"}")
        } catch (e: Exception) {
            Log.add("discovery", "MulticastLock не взят: ${e.message} — поиск без него")
            multicastLock = null
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.add("discovery", "mDNS поиск _airplay._tcp начат")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.add("discovery", "найдена служба: ${service.serviceName} (${service.serviceType})")
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        Log.add("discovery", "resolve failed ${si.serviceName}: $errorCode")
                    }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val host = si.host?.hostAddress ?: return
                        val txt = HashMap<String, String>()
                        for ((k, v) in si.attributes) txt[k] = String(v, Charsets.UTF_8)
                        val features = Features.parse(txt["ft"] ?: txt["features"])
                        val dev = AirPlayDevice(
                            name = si.serviceName,
                            host = host,
                            port = si.port,
                            txt = txt,
                            features = features,
                            model = txt["model"] ?: "?",
                            deviceId = txt["deviceid"] ?: host,
                        )
                        found[dev.identity] = dev
                        Log.add("discovery", "Apple TV: $dev | ${Features.describe(features)}")
                        onDevice(dev)
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                Log.add("discovery", "служба пропала: ${service.serviceName}")
                onLost(service.serviceName)
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.add("discovery", "mDNS поиск остановлен")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.add("discovery", "mDNS старт failed: $errorCode")
                running = false
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.add("discovery", "mDNS стоп failed: $errorCode")
            }
        }
        listener = l
        try {
            nsd.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.add("discovery", "discoverServices error: ${e.message}")
            running = false
        }
    }

    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (ignored: Exception) { } }
        listener = null
        running = false
        // v0.3.2: отпускаем MulticastLock вместе с остановкой поиска.
        try { multicastLock?.release() } catch (ignored: Exception) { }
        multicastLock = null
    }

    fun snapshot(): List<AirPlayDevice> = found.values.toList()
}

/**
 * HAP credentials storage: AES/GCM in AndroidKeyStore, ciphertext in
 * SharedPreferences. Falls back to obfuscated plaintext storage only if the
 * KeyStore is unavailable (logged loudly — credentials are long-term secrets).
 */
class CredentialStore(context: Context) : CredStore {
    private val prefs = context.getSharedPreferences("aircast_creds", Context.MODE_PRIVATE)
    private val keyAlias = "aircast-hap-key"

    override fun load(identity: String): HapCredentials? {
        val raw = prefs.getString("cred:" + identity.hashCode(), null) ?: return null
        return try {
            HapCredentials.parse(decrypt(raw))
        } catch (e: Exception) {
            Log.add("pair", "не удалось расшифровать сохранённые данные для $identity: ${e.message}")
            null
        }
    }

    override fun save(identity: String, creds: HapCredentials) {
        prefs.edit().putString("cred:" + identity.hashCode(), encrypt(creds.serialize())).apply()
        Log.add("pair", "Данные сопряжения сохранены для $identity (повторный PIN не понадобится)")
    }

    override fun clear(identity: String) {
        prefs.edit().remove("cred:" + identity.hashCode()).apply()
    }

    override fun has(identity: String): Boolean = prefs.contains("cred:" + identity.hashCode())

    private fun encrypt(plain: String): String {
        return try {
            val key = getOrCreateKey()
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            android.util.Base64.encodeToString(iv + ct, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.add("pair", "ВНИМАНИЕ: KeyStore недоступен (${e.message}) — храню данные как есть")
            "PLAIN:" + android.util.Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }
    }

    private fun decrypt(stored: String): String {
        if (stored.startsWith("PLAIN:")) {
            return String(android.util.Base64.decode(stored.removePrefix("PLAIN:"), android.util.Base64.NO_WRAP), Charsets.UTF_8)
        }
        val key = getOrCreateKey()
        val raw = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): java.security.Key {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (!ks.containsAlias(keyAlias)) {
            val gen = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(android.security.keystore.KeyGenParameterSpec.Builder(
                keyAlias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            gen.generateKey()
        }
        return ks.getKey(keyAlias, null)
    }
}
