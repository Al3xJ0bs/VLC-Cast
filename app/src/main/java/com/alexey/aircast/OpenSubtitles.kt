package com.alexey.aircast

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v0.9.1 — субтитры из внешних баз: OpenSubtitles API v1
 * (https://api.opensubtitles.com/api/v1).
 *
 * Честно про ключ: REST API v1 требует заголовок `Api-Key`
 * (бесплатная регистрация consumer на opensubtitles.com — см.
 * README). Без ключа /subtitles отвечает 401, и поиск просто
 * пропускается (лог «нужен API-ключ») — страница со своими
 * дорожками работает как раньше. Без логина лимит скачиваний —
 * 5 в день, с бесплатным аккаунтом — 20 в день (по документации);
 * ссылки на скачивание временные, берутся свежими каждый раз.
 *
 * Поиск — по чистому названию (TitleCleaner) + сезон/серия;
 * точный метод — moviehash, но он требует сам файл видео, а у
 * нас только поток, поэтому поиск по названию. Скачанный .srt
 * конвертируется в VTT (Subtitles.srtToVtt) и отдаётся во VLC
 * через прокси (sub= / HLS-мастер), как дорожки со страницы.
 *
 * Pure Kotlin, no Android imports: парсинг — regex (как в
 * YouTube.kt), сеть — HttpURLConnection в потоках движка.
 * Тестируется на JVM (парсинг по фикстуре).
 */
object OpenSubtitles {
    const val BASE_URL = "https://api.opensubtitles.com/api/v1"
    const val USER_AGENT = "VLC Cast"

    data class FoundSubtitle(
        val fileId: Long,
        val lang: String,
        val fileName: String,
        val downloadCount: Long,
    )

    /** GET /subtitles?query=..&languages=..&season_number=..&episode_number=.. */
    fun searchUrl(query: String, season: Int?, episode: Int?, languages: String = "ru,en"): String {
        val sb = StringBuilder(BASE_URL + "/subtitles?query=" + URLEncoder.encode(query, "UTF-8"))
        sb.append("&languages=").append(URLEncoder.encode(languages, "UTF-8"))
        if (season != null) sb.append("&season_number=").append(season)
        if (episode != null) sb.append("&episode_number=").append(episode)
        sb.append("&order_by=download_count&order_direction=desc")
        return sb.toString()
    }

    /** Тело POST /download. */
    fun downloadRequestJson(fileId: Long): String = """{"file_id":$fileId}"""

    /**
     * Разобрать ответ /subtitles: по каждому attributes-блоку —
     * language, download_count и files[] (file_id, file_name).
     * Сортировка — по download_count (самые скачиваемые первыми).
     * Никогда не бросает исключение.
     */
    fun parseSearchResponse(json: String?): List<FoundSubtitle> {
        if (json.isNullOrBlank()) return emptyList()
        val out = ArrayList<FoundSubtitle>()
        try {
            // attributes-объекты: от "attributes":{ до балансной }.
            var idx = 0
            while (true) {
                val attrIdx = json.indexOf("\"attributes\"", idx)
                if (attrIdx < 0) break
                val open = json.indexOf('{', attrIdx)
                if (open < 0) break
                val block = YouTube.balanced(json, open, '{', '}') ?: break
                idx = open + block.length
                val lang = Regex(""""language"\s*:\s*"([a-zA-Z-]{2,5})"""").find(block)?.groupValues?.get(1) ?: ""
                val count = Regex(""""download_count"\s*:\s*(\d+)""").find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val filesArr = YouTube.extractArray(block, "\"files\"") ?: continue
                val fileIdRe = Regex(""""file_id"\s*:\s*(\d+)""")
                val fileNameRe = Regex(""""file_name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                val idM = fileIdRe.find(filesArr) ?: continue
                val nameM = fileNameRe.find(filesArr)
                val fileId = idM.groupValues[1].toLongOrNull() ?: continue
                val fileName = nameM?.groupValues?.get(1)?.let { VideoUrls.decodeJsResult("\"$it\"") ?: it } ?: ""
                if (lang.isBlank()) continue
                out.add(FoundSubtitle(fileId, lang, fileName, count))
            }
        } catch (e: Exception) {
            // Частичный результат лучше, чем ничего.
        }
        return out.sortedByDescending { it.downloadCount }
    }

    /** Временная ссылка из ответа POST /download, или null. */
    fun parseDownloadResponse(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return Regex(""""link"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)
            ?.groupValues?.get(1)?.let { VideoUrls.decodeJsResult("\"$it\"") ?: it }
            ?.takeIf { it.isNotBlank() }
    }

    /** Выбрать лучший файл: сначала язык из preferred (ru), потом по счёту. */
    fun pickBest(found: List<FoundSubtitle>, preferredLang: String?): FoundSubtitle? {
        if (found.isEmpty()) return null
        if (!preferredLang.isNullOrBlank()) {
            val match = found.firstOrNull { it.lang.equals(preferredLang, ignoreCase = true) }
            if (match != null) return match
        }
        return found[0]
    }

    // ---------- Сеть (вызывать из потоков движка, не из UI) ----------

    private fun headers(apiKey: String?): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = USER_AGENT
        h["Accept"] = "application/json"
        h["Content-Type"] = "application/json"
        if (!apiKey.isNullOrBlank()) h["Api-Key"] = apiKey
        return h
    }

    /**
     * Поиск субтитров. Без ключа — запрос всё равно уходит (API
     * ответит 401, и это честно вернётся как пустой список с логом
     * у вызывающего). null — сеть/HTTP не 2xx.
     */
    fun search(apiKey: String?, query: String, season: Int?, episode: Int?, languages: String = "ru,en"): List<FoundSubtitle>? {
        return try {
            val c = URL(searchUrl(query, season, episode, languages)).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            for ((k, v) in headers(apiKey)) c.setRequestProperty(k, v)
            if (c.responseCode !in 200..299) {
                Log.add("opensubs", "поиск: HTTP ${c.responseCode} (без ключа — 401: нужен бесплатный consumer-ключ)")
                return null
            }
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseSearchResponse(body)
        } catch (e: Exception) {
            Log.add("opensubs", "поиск error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Временная ссылка на скачивание файла субтитров, или null. */
    fun downloadLink(apiKey: String?, fileId: Long): String? {
        return try {
            val c = URL("$BASE_URL/download").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            for ((k, v) in headers(apiKey)) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(downloadRequestJson(fileId).toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) {
                Log.add("opensubs", "download: HTTP ${c.responseCode}")
                return null
            }
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseDownloadResponse(body)
        } catch (e: Exception) {
            Log.add("opensubs", "download error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Скачать текст субтитров по временной ссылке (кап 2 МБ), или null. */
    fun fetchSubtitleText(link: String): String? {
        return try {
            val c = URL(link).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (c.responseCode !in 200..299) return null
            val out = StringBuilder()
            val buf = CharArray(8192)
            val reader = c.inputStream.bufferedReader(Charsets.UTF_8)
            var total = 0
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                total += n
                if (total > 2 * 1024 * 1024) return null
                out.append(buf, 0, n)
            }
            out.toString().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.add("opensubs", "fetch текста error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
