package com.alexey.aircast

import java.net.URL

/**
 * v0.9.0 — список серий на странице (авто-следующая серия).
 *
 * Generic, без привязки к сайтам: следующий эпизод ищется двумя
 * способами — (1) ссылка-якорь «Следующая серия / Next» и (2) группа
 * ссылок, отличающихся от текущей страницы только номером эпизода
 * (серия-50/51/52, ?ep=5/6, s01e05/e06). Фильмы без списка серий
 * не трогаются: если списка нет — parse возвращает null, и
 * авто-следующая серия просто не вооружается.
 *
 * Pure Kotlin, no Android imports: тестируется на JVM.
 */
data class PageLink(val href: String, val text: String)

data class EpisodeInfo(
    val currentUrl: String,
    val nextUrl: String?,
    /** Сколько эпизодов в найденной группе (0 — нашли только якорь «следующая»). */
    val total: Int,
)

object EpisodeList {
    private val NEXT_TEXT = Regex("следующ|next", RegexOption.IGNORE_CASE)

    /**
     * Найти следующий эпизод. pageUrl — URL текущей страницы серии,
     * links — ссылки <a> со страницы (href + текст, в порядке документа).
     * Возвращает null, если списка серий нет.
     */
    fun parse(pageUrl: String, links: List<PageLink>): EpisodeInfo? {
        val pageHost = hostOf(pageUrl) ?: return null
        // Абсолютные URL, тот же хост, без дублей, без текущей страницы
        // в кандидатах на «следующую» (сама страница — для поиска группы).
        val abs = ArrayList<PageLink>()
        val seen = HashSet<String>()
        for (l in links) {
            val u = VideoUrls.resolveUrl(pageUrl, l.href.trim())
            if (!u.startsWith("http")) continue
            if (hostOf(u) != pageHost) continue
            if (!seen.add(u)) continue
            abs.add(PageLink(u, l.text))
        }
        // (1) Якорь «Следующая серия»: текст ссылки, другой URL.
        for (l in abs) {
            if (l.href != pageUrl && NEXT_TEXT.containsMatchIn(l.text)) {
                return EpisodeInfo(pageUrl, l.href, 0)
            }
        }
        // (2) Группа по шаблону: последняя группа цифр в path+query
        // заменяется на "#"; группа, чей шаблон совпадает с шаблоном
        // текущей страницы и где >= 2 ссылок — список серий.
        val pageTemplate = templateOf(pageUrl) ?: return null
        val pageNumber = lastNumber(pageUrl)
        val group = ArrayList<Pair<String, Long>>()
        for (l in abs) {
            if (templateOf(l.href) != pageTemplate) continue
            val n = lastNumber(l.href) ?: continue
            group.add(l.href to n)
        }
        if (group.size < 2) return null
        group.sortBy { it.second }
        val total = group.size
        // Текущая страница может не быть в ссылках — ищем по номеру.
        val current = pageNumber
        var next: String? = null
        if (current != null) {
            for ((href, n) in group) {
                if (n > current) {
                    next = href
                    break
                }
            }
        } else {
            // Номер из текущей страницы не вытащился — берём ссылку
            // сразу после совпадающего URL, если он в группе.
            val idx = group.indexOfFirst { it.first == pageUrl }
            if (idx >= 0 && idx + 1 < group.size) next = group[idx + 1].first
        }
        return EpisodeInfo(pageUrl, next, total)
    }

    private fun hostOf(url: String): String? {
        return try {
            val u = URL(url)
            u.host.toLowerCase()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Шаблон URL: path+query с последней группой цифр, заменённой на "#".
     * Хост в шаблон не входит (он уже проверен отдельно). Цифры в хосте
     * (hd1.example.test) шаблон не ломают — берём только path+query.
     */
    private fun templateOf(url: String): String? {
        return try {
            val u = URL(url)
            val pq = u.path + if (u.query != null) "?" + u.query else ""
            val m = Regex("(\\d+)(?!.*\\d)").find(pq) ?: return null
            pq.substring(0, m.range.first) + "#" + pq.substring(m.range.last + 1)
        } catch (e: Exception) {
            null
        }
    }

    /** Последняя группа цифр в path+query URL. */
    private fun lastNumber(url: String): Long? {
        return try {
            val u = URL(url)
            val pq = u.path + if (u.query != null) "?" + u.query else ""
            Regex("(\\d+)(?!.*\\d)").find(pq)?.groupValues?.get(1)?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
