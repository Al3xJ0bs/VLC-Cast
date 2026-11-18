package com.alexey.aircast

import java.net.HttpURLConnection
import java.net.URL

/**
 * v0.9.1 — проверка обновлений в приложении.
 *
 * v0.9.2: источник по умолчанию — публичный репозиторий
 * Al3xJ0bs/VLC-Cast, latest.json в корне ветки main:
 *   https://raw.githubusercontent.com/Al3xJ0bs/VLC-Cast/main/latest.json
 * Формат latest.json:
 *   {"versionCode":33,"versionName":"0.9.3","url":"https://github.com/Al3xJ0bs/VLC-Cast/releases/download/v0.9.3/VLC-Cast-0.9.3.apk"}
 * URL можно изменить в настройках (блок «Настройка один раз»);
 * пустое поле = обратно к умолчанию. latest.json опубликован
 * в репозитории (с v0.9.2); если файла нет (404) — честно
 * «источник обновлений недоступен», без падения:
 * fetchLatestResult различает недоступный источник (404) и
 * ошибку сети/мусор в JSON.
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

    /** v0.9.2: источник обновлений по умолчанию (GitHub, Al3xJ0bs/VLC-Cast). */
    const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/Al3xJ0bs/VLC-Cast/main/latest.json"

    /** v0.9.2: исход проверки — файл есть (Ok), файла пока нет
     *  (Unavailable — 404), остальное (сеть / мусор) — Error. */
    sealed class FetchResult {
        data class Ok(val latest: Latest) : FetchResult()
        object Unavailable : FetchResult()
        object Error : FetchResult()
    }

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
        return when (val r = fetchLatestResult(checkUrl)) {
            is FetchResult.Ok -> r.latest
            else -> null
        }
    }

    /**
     * v0.9.2: GET latest.json с различением исхода. 404 — файла нет
     * (источник недоступен); другой не-2xx, мусор в JSON,
     * переполнение капа, исключение сети — Error.
     */
    fun fetchLatestResult(checkUrl: String): FetchResult {
        return try {
            val c = URL(checkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "VLC Cast")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode == 404) {
                Log.add("update", "проверка: источник недоступен (404): $checkUrl")
                return FetchResult.Unavailable
            }
            if (c.responseCode !in 200..299) {
                Log.add("update", "проверка: HTTP ${c.responseCode}: $checkUrl")
                return FetchResult.Error
            }
            val bytes = c.inputStream.readBytes()
            if (bytes.size > 64 * 1024) return FetchResult.Error
            val latest = parseLatest(bytes.toString(Charsets.UTF_8)) ?: return FetchResult.Error
            FetchResult.Ok(latest)
        } catch (e: Exception) {
            Log.add("update", "проверка error: ${e.javaClass.simpleName}: ${e.message}")
            FetchResult.Error
        }
    }
}
