package com.alexey.aircast

/**
 * v0.9.1 — плейлисты YouTube: шаринг ссылки на плейлист → видео
 * играют одно за другим (через тот же поток каста).
 *
 * Метод: POST youtubei/v1/browse с browseId "VL<playlistId>"
 * (тот же InnerTube-клиент VISIONOS, что и /player — см. YouTube.kt,
 * yt-dlp: youtube.py _extract_playlist / playlist endpoint).
 * Ответ: contents.twoColumnBrowseResultsRenderer.tabs[].tabRenderer
 * .content.sectionListRenderer.contents[].itemSectionRenderer
 * .contents[].playlistVideoListRenderer.contents[] —
 * каждый playlistVideoRenderer несёт videoId.
 *
 * Честные ограничения: только первая страница плейлиста (обычно
 * до 100 видео) — continuation-токены не гоняются; приватные /
 * удалённые видео в списке пропускаются (videoId всё равно есть,
 * InnerTube на них вернёт UNPLAYABLE — движок честно скажет
 * «нет прямого потока» и перейдёт к следующему по очереди).
 *
 * Pure Kotlin, no Android imports: запрос строится строкой, ответ
 * парсится regex — тестируется на JVM.
 */
object YouTubePlaylist {
    const val BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"

    /** Максимум видео из одной страницы плейлиста (первая страница). */
    const val MAX_ITEMS = 100

    /** playlistId из /playlist?list=, watch?v=..&list=, music/playlist. */
    fun extractPlaylistId(url: String): String? {
        val u = url.trim()
        val m = Regex("[?&]list=([A-Za-z0-9_-]+)").find(u) ?: return null
        return m.groupValues[1].takeIf { it.isNotBlank() }
    }

    fun isPlaylistUrl(url: String): Boolean = extractPlaylistId(url) != null

    /** Тело POST youtubei/v1/browse для плейлиста (VISIONOS). */
    fun browseRequestJson(playlistId: String, visitorData: String?): String {
        return """{"context":{${YouTube.clientJson(YouTube.Client.VISIONOS, visitorData)}},"browseId":"VL${escapeJson(playlistId)}"}"""
    }

    private fun escapeJson(s: String): String {
        val out = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * videoId из ответа /browse: только те, что идут в
     * playlistVideoRenderer (а не случайные videoId из шапки/
     * рекомендаций/алертов ответа). Порядок — как в плейлисте,
     * дубли — один раз, кап MAX_ITEMS.
     */
    fun parseVideoIds(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        val rendererRe = Regex(""""playlistVideoRenderer"\s*:\s*\{""")
        val videoIdRe = Regex(""""videoId"\s*:\s*"([A-Za-z0-9_-]{11})"""")
        for (m in rendererRe.findAll(json)) {
            if (out.size >= MAX_ITEMS) break
            val window = json.substring(m.range.last, minOf(json.length, m.range.last + 4000))
            val v = videoIdRe.find(window) ?: continue
            val id = v.groupValues[1]
            if (seen.add(id)) out.add(id)
        }
        return out
    }

    /** URL watch-страницы видео из плейлиста. */
    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
}
