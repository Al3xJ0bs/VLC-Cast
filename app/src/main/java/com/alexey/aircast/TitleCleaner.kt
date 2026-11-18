package com.alexey.aircast

/**
 * v0.9.1 — чистка названий видео (уведомление «запущено» + поиск
 * субтитров в OpenSubtitles).
 *
 * Проблема: document.title на сайтах — билиберда вида
 * «Розаріо вампір 1 сезон 5 серія дивитись онлайн | YummyAnime»
 * или «Сериал — 5 серия | Сайт». Для уведомления нужен чистый
 * заголовок, для OpenSubtitles — чистое название сериала + сезон
 * и серия (поиск по «билиберде» ничего не находит).
 *
 * Метод — только эвристики по тексту (детерминированно, без LLM):
 * срезать суффикс сайта, выкинуть мусорные фразы («смотреть онлайн»,
 * «бесплатно», «в хорошем качестве»), вытащить сезон/серию по
 * шаблонам. Если чистое название не вытащилось — честно null:
 * вызывающий показывает host/URL без выдумывания.
 *
 * Pure Kotlin, no Android imports: тестируется на JVM.
 */
data class CleanTitle(
    /** Чистый заголовок для уведомления (или null — показывать host). */
    val display: String?,
    /** Название сериала/фильма для поиска субтитров (или null). */
    val series: String?,
    val season: Int?,
    val episode: Int?,
)

object TitleCleaner {
    private val JUNK_PHRASES = listOf(
        "смотреть онлайн", "смотреть бесплатно", "смотреть", "онлайн",
        "бесплатно", "в хорошем качестве", "в хорошем качестве hd",
        "хорошем качестве", "дивитись онлайн", "дивитися онлайн",
        "смотреть hd", "hd 720", "hd 1080", "full hd",
    )

    private val SEASON_EPISODE_RE = Regex(
        """(?i)(\d{1,2})\s*сезон[а]?\s*,?\s*(\d{1,4})\s*(?:серия|серія|эпизод|епізод)"""
    )
    private val SEASON_EPISODE_RE2 = Regex(
        """(?i)(\d{1,2})\s*сезон[а]?\s*(\d{1,4})\s*(?:серия|серія|эпизод|епізод)"""
    )
    private val S_E_RE = Regex("""(?i)[sс](\d{1,2})[eе](\d{1,4})""")
    private val EPISODE_RE = Regex("""(?i)(?:серия|серія|эпизод|епізод)\s*(\d{1,4})""")
    private val EPISODE_RE2 = Regex("""(?i)(\d{1,4})\s*(?:серия|серія|эпизод|епізод)""")
    private val SEASON_RE = Regex("""(?i)(\d{1,2})\s*сезон""")
    private val YEAR_RE = Regex("""\((\d{4})\)""")

    /**
     * Разобрать title страницы (+ URL как запасной источник номера
     * серии). Возвращает CleanTitle; display/series — null, если
     * ничего осмысленного не вытащилось.
     */
    fun parse(pageTitle: String, pageUrl: String): CleanTitle {
        var text = pageTitle.trim()
        if (text.isEmpty()) return CleanTitle(null, null, null, null)

        // 1. Суффикс сайта: последний сегмент после " | ", " — ", " – ",
        // " :: " — если в нём нет цифр и нет слов «серия/сезон», это
        // имя сайта, а не часть названия. Повторить один раз
        // («Название | Сайт | Ещё сайт» не бывает, но «|» внутри
        // названия — бывает).
        repeat(2) {
            val cut = cutSiteSuffix(text) ?: return@repeat
            text = cut
        }

        // 2. Мусорные фразы (регистронезависимо, только как отдельные
        // слова/фразы в конце или в середине). Длинные фразы — первыми:
        // иначе «онлайн» съест хвост «дивитись онлайн» раньше, чем
        // фраза целиком будет опробована, и «дивитись» останется.
        for (junk in JUNK_PHRASES.sortedByDescending { it.length }) {
            text = Regex("(?i)\\b" + Regex.escape(junk) + "\\b").replace(text, " ")
        }
        text = text.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '|', ':', ',', '.')

        // 3. Сезон/серия из текста.
        var season: Int? = null
        var episode: Int? = null
        var m = SEASON_EPISODE_RE.find(text) ?: SEASON_EPISODE_RE2.find(text)
        if (m != null) {
            season = m.groupValues[1].toIntOrNull()
            episode = m.groupValues[2].toIntOrNull()
        }
        if (episode == null) {
            val se = S_E_RE.find(text)
            if (se != null) {
                season = se.groupValues[1].toIntOrNull()
                episode = se.groupValues[2].toIntOrNull()
            }
        }
        if (episode == null) {
            episode = (EPISODE_RE.find(text) ?: EPISODE_RE2.find(text))?.groupValues?.get(1)?.toIntOrNull()
        }
        if (season == null) {
            season = SEASON_RE.find(text)?.groupValues?.get(1)?.toIntOrNull()
        }
        // «Название — 1080»: число в конце после тире — номер серии,
        // если в заголовке больше нет чисел и число разумное.
        if (episode == null) {
            val tail = Regex("""[—–-]\s*(\d{1,4})\s*$""").find(text)
            if (tail != null) episode = tail.groupValues[1].toIntOrNull()
        }
        // 4. Сезон/серия из URL, если в title нет (шаблоны /s1e5/,
        // /5-серия/, ?ep=5, /show/5).
        if (episode == null) {
            val fromUrl = episodeFromUrl(pageUrl)
            if (fromUrl != null) {
                season = fromUrl.first ?: season
                episode = fromUrl.second
            }
        }

        // 5. Имя сериала: убрать из текста фрагменты сезона/серии.
        var name = text
        name = SEASON_EPISODE_RE.replace(name, " ")
        name = SEASON_EPISODE_RE2.replace(name, " ")
        name = S_E_RE.replace(name, " ")
        name = EPISODE_RE.replace(name, " ")
        name = EPISODE_RE2.replace(name, " ")
        name = SEASON_RE.replace(name, " ")
        name = Regex("""[—–-]\s*\d{1,4}\s*$""").replace(name, " ")
        name = name.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '|', ':', ',', '.')
        // Год в скобках — оставить (помогает поиску субтитров).
        if (name.length < 2) return CleanTitle(null, null, season, episode)

        val display = buildDisplay(name, season, episode)
        return CleanTitle(display, name, season, episode)
    }

    /** «Название — 2 сезон, 5 серия» / «Название — 5 серия» / «Название». */
    fun buildDisplay(name: String, season: Int?, episode: Int?): String {
        return when {
            season != null && episode != null -> "$name — $season сезон, $episode серия"
            episode != null -> "$name — $episode серия"
            season != null -> "$name — $season сезон"
            else -> name
        }
    }

    /** Имя файла видео → чистое название (галерея/Telegram): убрать
     *  расширение, подчёркивания/точки — в пробелы, мусорные суффиксы. */
    fun cleanFileName(fileName: String): String {
        var name = fileName.substringAfterLast('/').substringBeforeLast('.')
        name = name.replace('_', ' ').replace('.', ' ').replace(Regex("\\s+"), " ").trim()
        name = Regex("(?i)\\b(720p|1080p|2160p|4k|web-dl|bluray|hdr|hevc|x264|x265)\\b").replace(name, " ")
        name = name.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—')
        return name
    }

    /** Срезать суффикс сайта после последнего разделителя, или null. */
    private fun cutSiteSuffix(text: String): String? {
        val seps = listOf(" | ", " — ", " – ", " :: ", " |", "— ", "– ")
        for (sep in seps) {
            val idx = text.lastIndexOf(sep)
            if (idx <= 0) continue
            val tail = text.substring(idx + sep.length).trim()
            val head = text.substring(0, idx).trim()
            if (head.length < 2) continue
            if (tail.isEmpty() || tail.length > 40) continue
            if (tail.any { it.isDigit() }) continue
            if (Regex("(?i)сери[яю]|сезон|эпизод").containsMatchIn(tail)) continue
            return head
        }
        return null
    }

    /** (season?, episode?) из URL, или null.
     *  Только явные шаблоны: /s1e5/ и ?ep=5. Голое число в конце
     *  пути (/movie/123, /show/5) НЕ берём: у фильма это id, а не
     *  серия, и «Фильм — 123 серия» в уведомлении — хуже, чем
     *  отсутствие номера серии (у сериалов номер обычно есть и
     *  в самом title страницы). */
    private fun episodeFromUrl(url: String): Pair<Int?, Int?>? {
        val se = Regex("""(?i)[sс](\d{1,2})[eе](\d{1,4})""").find(url)
        if (se != null) return se.groupValues[1].toIntOrNull() to se.groupValues[2].toIntOrNull()
        val ep = Regex("""[?&]ep=(\d{1,4})""").find(url)
        if (ep != null) return null to ep.groupValues[1].toIntOrNull()
        return null
    }

    /** Год из названия (для поиска субтитров — опционально). */
    fun yearOf(name: String): Int? = YEAR_RE.find(name)?.groupValues?.get(1)?.toIntOrNull()
}
