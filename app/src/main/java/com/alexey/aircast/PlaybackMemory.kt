package com.alexey.aircast

import java.security.MessageDigest

/**
 * v0.9.1 — память воспроизведения: озвучка, субтитры, позиция.
 *
 * Что запоминается (на сериал / на видео, в SharedPreferences
 * "aircast", префикс "mem:"):
 *  - озвучка: аудио-дорожка (label из Playerjs {Rus}/{Eng} и т.п.),
 *    запущенная в прошлый раз для этого сериала — в следующий раз
 *    кандидат с той же дорожкой предпочитается;
 *  - субтитры: вкл/выкл + язык дорожки для этого сериала;
 *  - позиция: секунда, на которой остановился просмотр этого видео
 *    (ключ — страница серии; для прямой ссылки — сам URL).
 *
 * Ключ сериала: host + шаблон URL с последним числом, заменённым
 * на "#" (та же идея, что EpisodeList.templateOf): серии одного
 * сериала (/show/5, /show/6, ?ep=5/6) дают один ключ, разные
 * сериалы — разные.
 *
 * Хранилище — через лямбды (get/put/remove): движок передаёт
 * SharedPreferences, тесты — HashMap. Чистая логика (ключи,
 * кодирование значений) — здесь и тестируется на JVM.
 */
object PlaybackMemory {
    const val PREFIX = "mem:"

    /** Ключ сериала из URL страницы серии (host + шаблон). */
    fun seriesKey(pageUrl: String): String {
        return try {
            val u = java.net.URL(pageUrl)
            val pq = u.path + if (u.query != null) "?" + u.query else ""
            val m = Regex("(\\d+)(?!.*\\d)").find(pq)
            val template = if (m != null) pq.substring(0, m.range.first) + "#" + pq.substring(m.range.last + 1) else pq
            u.host.toLowerCase() + template
        } catch (e: Exception) {
            pageUrl
        }
    }

    /** Короткий хэш ключа для SharedPreferences (без странных символов). */
    fun hashKey(raw: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            raw.hashCode().toString(16)
        }
    }

    fun dubPrefKey(seriesKey: String): String = PREFIX + "dub:" + hashKey(seriesKey)

    fun subsPrefKey(seriesKey: String): String = PREFIX + "subs:" + hashKey(seriesKey)

    fun positionPrefKey(pageUrl: String): String = PREFIX + "pos:" + hashKey(pageUrl)

    // ---------- Позиция ----------

    data class Position(val positionSec: Long, val durationSec: Long?, val savedAtMs: Long)

    fun encodePosition(positionSec: Long, durationSec: Long?, savedAtMs: Long): String =
        "$positionSec|${durationSec ?: 0}|$savedAtMs"

    /** Разобрать сохранённую позицию, или null (мусор / неположительная). */
    fun decodePosition(raw: String?): Position? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('|')
        if (parts.size != 3) return null
        val pos = parts[0].toLongOrNull() ?: return null
        val dur = parts[1].toLongOrNull() ?: return null
        val at = parts[2].toLongOrNull() ?: return null
        if (pos <= 0 || at <= 0) return null
        return Position(pos, if (dur > 0) dur else null, at)
    }

    /**
     * Можно ли продолжать с этой позиции: больше минуты с начала
     * и минимум минута до конца (если длительность известна).
     */
    fun isResumable(pos: Position): Boolean {
        if (pos.positionSec < 60) return false
        val dur = pos.durationSec ?: return true
        return pos.positionSec < dur - 60
    }

    // ---------- Субтитры ----------

    data class SubsPref(val enabled: Boolean, val lang: String?)

    fun encodeSubs(enabled: Boolean, lang: String?): String =
        (if (enabled) "1" else "0") + "|" + (lang ?: "")

    fun decodeSubs(raw: String?): SubsPref? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('|')
        if (parts.size != 2) return null
        val enabled = when (parts[0]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val lang = parts[1].takeIf { it.isNotBlank() }
        return SubsPref(enabled, lang)
    }
}

/**
 * Хранилище памяти воспроизведения поверх лямбд get/put/remove.
 * Движок строит его на SharedPreferences, тесты — на HashMap.
 */
class PlaybackStore(
    private val get: (String) -> String?,
    private val put: (String, String) -> Unit,
    private val remove: (String) -> Unit,
) {
    // --- озвучка ---
    fun rememberDub(seriesKey: String, label: String) {
        if (label.isBlank()) return
        put(PlaybackMemory.dubPrefKey(seriesKey), label.trim())
    }

    fun preferredDub(seriesKey: String): String? = get(PlaybackMemory.dubPrefKey(seriesKey))

    // --- субтитры ---
    fun rememberSubs(seriesKey: String, enabled: Boolean, lang: String?) {
        put(PlaybackMemory.subsPrefKey(seriesKey), PlaybackMemory.encodeSubs(enabled, lang))
    }

    fun subsPref(seriesKey: String): PlaybackMemory.SubsPref? =
        PlaybackMemory.decodeSubs(get(PlaybackMemory.subsPrefKey(seriesKey)))

    /** Переключить вкл/выкл субтитров для сериала. Возвращает новое состояние. */
    fun toggleSubs(seriesKey: String, currentTracks: List<SubTrack>): Boolean {
        val cur = subsPref(seriesKey)
        val nextEnabled = !(cur?.enabled ?: true)
        val lang = cur?.lang ?: currentTracks.firstOrNull()?.lang?.takeIf { it.isNotBlank() }
        rememberSubs(seriesKey, nextEnabled, lang)
        return nextEnabled
    }

    // --- позиция ---
    fun savePosition(pageUrl: String, positionSec: Long, durationSec: Long?) {
        if (positionSec < 60) return
        put(
            PlaybackMemory.positionPrefKey(pageUrl),
            PlaybackMemory.encodePosition(positionSec, durationSec, System.currentTimeMillis())
        )
    }

    fun loadPosition(pageUrl: String): PlaybackMemory.Position? =
        PlaybackMemory.decodePosition(get(PlaybackMemory.positionPrefKey(pageUrl)))

    fun clearPosition(pageUrl: String) = remove(PlaybackMemory.positionPrefKey(pageUrl))
}
