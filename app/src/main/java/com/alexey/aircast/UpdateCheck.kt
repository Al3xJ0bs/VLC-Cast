package com.alexey.aircast

import java.net.HttpURLConnection
import java.net.URL

/**
 * v0.9.1 — проверка обновлений в приложении.
 *
 * Честно про источник: у проекта нет своего сервера и нет
 * публичного репозитория (локальный git без remote — см. README),
 * поэтому источник обновлений — ЗАДАВАЕМЫЙ URL в настройках
 * (блок «Настройка один раз»): JSON-файл вида
 *   {"versionCode":31,"versionName":"0.9.1","url":"https://...apk"}
 * Пока URL не задан — проверка честно сообщает «источник не задан»,
 * а не выдумывает, что обновлений нет. Как только появится
 * репозиторий/хостинг — достаточно положить туда latest.json и
 * вписать URL один раз.
 *
 * Проверка: при запуске (не чаще раза в сутки) + кнопка
 * «Проверить обновления». Если versionCode из JSON больше
 * текущего — «Доступна версия X» + кнопка «Скачать» (браузер
 * по url из JSON). Если равен/меньше — «У вас последняя версия».
 * Сеть — в фоновом потоке, таймаут 10 с.
 *
 * Pure Kotlin, no Android imports (кроме Log): парсинг — regex,
 * тестируется на JVM.
 */
object UpdateCheck {
    data class Latest(val versionCode: Int, val versionName: String, val url: String)

    /** Разобрать latest.json, или null (мусор / нет versionCode). */
    fun parseLatest(json: String?): Latest? {
        if (json.isNullOrBlank()) return null
        val code = Regex(""""versionCode"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val name = Regex(""""versionName"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1) ?: ""
        val url = Regex(""""url"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)
            ?.groupValues?.get(1)?.let { VideoUrls.decodeJsResult("\"$it\"") ?: it } ?: ""
        return Latest(code, name, url)
    }

    fun isNewer(currentCode: Int, latest: Latest?): Boolean =
        latest != null && latest.versionCode > currentCode

    /** GET latest.json (кап 64 КБ), или null (сеть / не 2xx / мусор). */
    fun fetchLatest(checkUrl: String): Latest? {
        return try {
            val c = URL(checkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "VLC Cast")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) {
                Log.add("update", "проверка: HTTP ${c.responseCode}: $checkUrl")
                return null
            }
            val bytes = c.inputStream.readBytes()
            if (bytes.size > 64 * 1024) return null
            parseLatest(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.add("update", "проверка error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
