package com.alexey.aircast

/**
 * v0.9.0 — субтитры во VLC.
 *
 * Метод: дорожки субтитров (.vtt/.srt/.ass) ищутся в странице
 * (<track>, скрипты, перехват сети). VLC по deep link'у внешнюю
 * дорожку не принимает вообще — поэтому прокси на телефоне отдаёт
 * телевизору СИНТЕТИЧЕСКИЙ HLS-master: исходный поток как единственный
 * вариант + группа SUBTITLES (WebVTT). SRT конвертируется в VTT
 * в приложении, ASS — базово (диалоги + тайминги, стили теряются).
 *
 * Честные ограничения: только HLS (.m3u8) — для mp4 субтитры не
 * встраиваются; DRM-потоки не трогаем; показывает ли VLC на tvOS
 * дорожки из такого мастера — по документации HLS/VLC да, живым
 * тестом не проверено (проверит Алексей).
 *
 * Pure Kotlin, no Android imports: тестируется на JVM.
 */
data class SubTrack(val url: String, val lang: String, val label: String)

object Subtitles {
    /** Это URL субтитров по расширению пути (.vtt/.srt/.ass). */
    fun isSubtitleUrl(url: String): Boolean {
        val path = url.toLowerCase().substringBefore('?').substringBefore('#')
        return path.endsWith(".vtt") || path.endsWith(".srt") || path.endsWith(".ass")
    }

    /**
     * Конвертировать тело субтитров по расширению URL.
     * Возвращает (Content-Type, тело) или null, если конвертация не
     * нужна (VTT — passthrough).
     */
    fun convertIfNeeded(url: String, body: String): Pair<String, String>? {
        val path = url.toLowerCase().substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".srt") -> "text/vtt" to srtToVtt(body)
            path.endsWith(".ass") -> "text/vtt" to assToVtt(body)
            else -> null
        }
    }

    /**
     * SRT → WebVTT: запятые в таймингах — в точки, шапка WEBVTT.
     * Порядковые номера и текст — как есть (в VTT они валидны).
     */
    fun srtToVtt(srt: String): String {
        val clean = srt.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder("WEBVTT\n\n")
        val blocks = clean.split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            // Первая строка — номер (только цифры), вторая — тайминг.
            var idx = 0
            if (lines[0].trim().matches(Regex("\\d+"))) idx = 1
            if (idx >= lines.size) continue
            val timing = lines[idx].trim()
            val m = Regex("(\\d{2}:\\d{2}:\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2})[,.](\\d{3})").find(timing)
                ?: continue
            out.append(m.groupValues[1]).append('.').append(m.groupValues[2])
                .append(" --> ")
                .append(m.groupValues[3]).append('.').append(m.groupValues[4]).append('\n')
            for (i in idx + 1 until lines.size) out.append(lines[i]).append('\n')
            out.append('\n')
        }
        return out.toString()
    }

    /**
     * ASS → WebVTT (базово): берём секцию [Events], по Format находим
     * Start/End/Text, Dialogue → cue. Override-теги {\...} и \N
     * убираются, стили/позиции теряются (честно: это не полный ASS).
     * Если диалогов нет — пустой VTT (только шапка).
     */
    fun assToVtt(ass: String): String {
        val out = StringBuilder("WEBVTT\n\n")
        val lines = ass.replace("\r\n", "\n").replace('\r', '\n').lines()
        var inEvents = false
        var format: List<String>? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                format = null
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:")) {
                format = line.substringAfter("Format:").split(',').map { it.trim().toLowerCase() }
                continue
            }
            if (!line.startsWith("Dialogue:")) continue
            val f = format ?: continue
            val startIdx = f.indexOf("start")
            val endIdx = f.indexOf("end")
            val textIdx = f.indexOf("text")
            if (startIdx < 0 || endIdx < 0 || textIdx < 0) continue
            // Dialogue: поля через запятую; Text — последнее, в нём
            // запятые разрешены, поэтому режем по числу полей до Text.
            val body = line.substringAfter("Dialogue:")
            val parts = splitAssFields(body, textIdx + 1)
            if (parts.size <= textIdx) continue
            val start = assTimeToVtt(parts[startIdx].trim()) ?: continue
            val end = assTimeToVtt(parts[endIdx].trim()) ?: continue
            var text = parts[textIdx]
            text = Regex("\\{[^}]*\\}").replace(text, "")
            text = text.replace("\\N", "\n").replace("\\n", "\n")
            if (text.isBlank()) continue
            out.append(start).append(" --> ").append(end).append('\n')
            out.append(text.trim()).append("\n\n")
        }
        return out.toString()
    }

    /** Разбить Dialogue-строку на N полей (Text — последнее, запятые внутри). */
    private fun splitAssFields(body: String, count: Int): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var commas = 0
        var i = 0
        while (i < body.length && out.size < count - 1) {
            if (body[i] == ',') {
                out.add(body.substring(start, i))
                start = i + 1
                commas++
            }
            i++
        }
        out.add(body.substring(start))
        return out
    }

    /** ASS-время "0:01:02.50" → VTT "00:01:02.500". */
    fun assTimeToVtt(t: String): String? {
        val m = Regex("^(\\d+):(\\d{2}):(\\d{2})\\.(\\d{1,3})$").find(t.trim()) ?: return null
        val h = m.groupValues[1].padStart(2, '0')
        val min = m.groupValues[2]
        val sec = m.groupValues[3]
        val ms = m.groupValues[4].padEnd(3, '0')
        return "$h:$min:$sec.$ms"
    }

    /**
     * Синтетический HLS-master: исходный поток (уже финальный URL для
     * запуска — прямой или через прокси) как единственный вариант +
     * группа субтитров. proxyTrack — прокси-URL дорожки (через
     * /stream, с конвертацией .srt/.ass в VTT на лету).
     * NAME/LANGUAGE чистятся от кавычек и переводов строк.
     * v0.9.1: preferredLang — запомненный язык субтитров: дорожка
     * с этим языком получает DEFAULT=YES (VLC включит её сам).
     */
    fun buildSubsMaster(
        streamUrl: String,
        tracks: List<SubTrack>,
        proxyTrack: (SubTrack) -> String,
        preferredLang: String? = null,
    ): String {
        val out = StringBuilder()
        out.append("#EXTM3U\n")
        out.append("#EXT-X-VERSION:3\n")
        for (t in tracks) {
            val lang = t.lang.ifBlank { "und" }.replace(Regex("[^A-Za-z-]"), "").ifBlank { "und" }
            val name = t.label.ifBlank { t.lang.ifBlank { "Субтитры" } }
                .replace("\"", "").replace("\n", " ").replace("\r", " ").trim().take(48)
            val def = if (!preferredLang.isNullOrBlank() &&
                lang.equals(preferredLang, ignoreCase = true)) ",DEFAULT=YES" else ""
            out.append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"")
                .append(name).append("\",LANGUAGE=\"").append(lang)
                .append("\"").append(def)
                .append(",URI=\"").append(proxyTrack(t)).append("\"\n")
        }
        out.append("#EXT-X-STREAM-INF:BANDWIDTH=800000,SUBTITLES=\"subs\"\n")
        out.append(streamUrl).append('\n')
        return out.toString()
    }
}
