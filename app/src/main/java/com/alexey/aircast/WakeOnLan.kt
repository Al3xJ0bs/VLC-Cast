package com.alexey.aircast

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * v0.9.3.4 — Wake-on-LAN для пробуждения спящего Apple TV.
 *
 * Диагноз (живой тест 0.9.3.2, слово Алексея 2026-11-18 13:32):
 * пробуждение было ТОЛЬКО Companion turn_on (_hidC 13), а спящий
 * ТВ не слушает ни 7000, ни 49153 — turn_on просто некому
 * доставить (известный caveat Bug B с 0.9.2). WoL работает на
 * уровне сети: magic packet будит ТВ, даже когда все его TCP-порты
 * молчат. Apple TV на Ethernet/Wi-Fi с включённым «Wake on LAN»
 * (по умолчанию включён) на него отвечает.
 *
 * Pure Kotlin, no Android imports: тестируется на JVM
 * (байты пакета, парсинг ARP, broadcast, отправка на loopback).
 */
object WakeOnLan {
    /** Порт WoL по умолчанию (discard). */
    const val DEFAULT_PORT = 9
    /** Повторов magic packet за одно пробуждение. */
    const val DEFAULT_REPEATS = 3
    /** Пауза между повторами, мс. */
    const val DEFAULT_INTERVAL_MS = 1000L
    /** Путь к ARP-таблице на телефоне. */
    const val ARP_PATH = "/proc/net/arp"

    /** Magic packet: 6× 0xFF + MAC ×16 = ровно 102 байта. */
    fun buildMagicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "MAC must be 6 bytes, got ${mac.size}" }
        val out = ByteArray(102)
        for (i in 0 until 6) out[i] = 0xFF.toByte()
        for (r in 0 until 16) mac.copyInto(out, 6 + r * 6)
        return out
    }

    /** "aa:bb:cc:dd:ee:ff" (или через "-") → 6 байт; мусор → null. */
    fun parseMac(text: String?): ByteArray? {
        if (text == null) return null
        val parts = text.trim().split(':', '-')
        if (parts.size != 6) return null
        return try {
            ByteArray(6) { i ->
                val p = parts[i]
                if (p.length != 2) return null
                p.toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** 6 байт → "aa:bb:cc:dd:ee:ff" (строчные). */
    fun formatMac(mac: ByteArray): String {
        require(mac.size == 6) { "MAC must be 6 bytes" }
        return mac.joinToString(":") { "%02x".format(it) }
    }

    /** Маска для лога: видны только первый и последний октеты. */
    fun maskMac(macText: String?): String {
        val mac = parseMac(macText) ?: return "?"
        val parts = formatMac(mac).split(':')
        return "${parts[0]}:${parts[1]}:..:..:${parts[4]}:${parts[5]}"
    }

    /**
     * Парсинг /proc/net/arp: строки вида
     * "192.168.0.57  0x1  0x2  aa:bb:cc:dd:ee:ff  *  wlan0".
     * Возвращает MAC для ip или null (нет строки, либо запись
     * неполная: Flags 0x0 / MAC 00:00:00:00:00:00).
     */
    fun parseArpTable(arpText: String, ip: String): String? {
        for (raw in arpText.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("IP address")) continue
            val cols = line.split(Regex("\\s+"))
            if (cols.size < 4) continue
            if (cols[0] != ip) continue
            val mac = cols[3].toLowerCase()
            if (mac == "00:00:00:00:00:00") return null
            if (parseMac(mac) == null) return null
            return mac
        }
        return null
    }

    /** MAC ТВ из живой ARP-таблицы телефона; null — записи нет. */
    fun readArpMac(ip: String, arpPath: String = ARP_PATH): String? {
        return try {
            val text = File(arpPath).readText()
            parseArpTable(text, ip)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Broadcast-адрес подсети ТВ из его IP и длины префикса
     * (по умолчанию /24 → 192.168.0.255). Мусор → 255.255.255.255.
     */
    fun broadcastFor(ip: String, prefixLen: Int = 24): String {
        val octets = ip.trim().split('.')
        if (octets.size != 4) return "255.255.255.255"
        val addr = try {
            val b = IntArray(4) { i ->
                val v = octets[i].toInt()
                if (v !in 0..255) return "255.255.255.255"
                v
            }
            ((b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3])
        } catch (e: NumberFormatException) {
            return "255.255.255.255"
        }
        val prefix = prefixLen.coerceIn(0, 32)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val bc = addr or mask.inv()
        return "${(bc ushr 24) and 0xFF}.${(bc ushr 16) and 0xFF}.${(bc ushr 8) and 0xFF}.${bc and 0xFF}"
    }

    /**
     * Отправка magic packet на broadcast-адрес. Возвращает число
     * реально отправленных пакетов (0 — ни один не ушёл).
     * DatagramSocket с setBroadcast(true); при ошибке сокета —
     * молча 0, caller логирует сам.
     * createSocket — test seam: песочница блокирует любой UDP-send
     * (даже unicast на loopback: IOException Operation not
     * permitted), поэтому JVM-тест подставляет фейковый сокет и
     * проверяет пакет/адрес/повторы; в проде — реальный сокет.
     */
    fun sendMagicPacket(
        mac: ByteArray,
        broadcastIp: String,
        port: Int = DEFAULT_PORT,
        repeats: Int = DEFAULT_REPEATS,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        createSocket: () -> DatagramSocket = { DatagramSocket() },
    ): Int {
        val packet = try {
            buildMagicPacket(mac)
        } catch (e: IllegalArgumentException) {
            return 0
        }
        var sent = 0
        try {
            createSocket().use { sock ->
                sock.broadcast = true
                val addr = InetAddress.getByName(broadcastIp)
                for (i in 0 until repeats) {
                    try {
                        sock.send(DatagramPacket(packet, packet.size, addr, port))
                        sent++
                    } catch (e: Exception) {
                        // один повтор не ушёл — остальные всё равно пробуем
                    }
                    if (i + 1 < repeats && intervalMs > 0) sleep(intervalMs)
                }
            }
        } catch (e: Exception) {
            // сокет не открылся вообще — 0
        }
        return sent
    }
}
