package com.alexey.aircast

/**
 * v0.9.1 — продолжить с того же места для HLS: обрезать
 * медиа-плейлист до позиции (сегменты, целиком закончившиеся
 * до positionSec, выбрасываются; первый оставшийся становится
 * началом), чтобы VLC начал не с нуля, а с запомненной позиции.
 *
 * Честно про ограничение: точное начало сегмента — с точностью
 * до сегмента (обычно 2–10 с); `#EXT-X-KEY`/`#EXT-X-MAP`
 * сохраняются, `#EXT-X-MEDIA-SEQUENCE` и `#EXT-X-TARGETDURATION`
 * пересчитываются, `#EXT-X-ENDLIST` сохраняется. Если позиция
 * невалидна (0, >= конца, плейлист пустой) — возвращается
 * исходный плейлист без изменений.
 *
 * Pure Kotlin, no Android imports: тестируется на JVM
 * (фикстура hls_resume.m3u8).
 */
object ResumePlaylist {
    data class TrimResult(val playlist: String, val keptSegments: Int, val skippedSegments: Int)

    /**
     * Обрезать медиа-плейлист до позиции positionSec.
     * positionSec — секунды от начала; сегменты с end <= position
     * выбрасываются. Возвращает исходный плейлист, если резать
     * нечего/нельзя.
     */
    fun trim(mediaPlaylist: String, positionSec: Long): TrimResult {
        if (positionSec <= 0) return TrimResult(mediaPlaylist, -1, 0)
        val lines = mediaPlaylist.lines()
        // Сегменты: список (extinfDuration, index строки EXTINF, index строки URI).
        data class Seg(val dur: Double, val extinfIdx: Int, val uriIdx: Int)
        val segs = ArrayList<Seg>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                val dur = line.substringAfter("#EXTINF:").substringBefore(",").trim().toDoubleOrNull()
                if (dur != null && i + 1 < lines.size) {
                    segs.add(Seg(dur, i, i + 1))
                }
            }
            i++
        }
        if (segs.isEmpty()) return TrimResult(mediaPlaylist, -1, 0)
        // Конец каждого сегмента; ищем первый, чей конец > position.
        var acc = 0.0
        var firstKept = -1
        for ((idx, s) in segs.withIndex()) {
            acc += s.dur
            if (acc > positionSec) {
                firstKept = idx
                break
            }
        }
        if (firstKept <= 0) return TrimResult(mediaPlaylist, segs.size, 0)
        val firstUriIdx = segs[firstKept].uriIdx
        val header = ArrayList<String>()
        var mediaSeq = 0L
        var targetDur = 0.0
        // Заголовок — все строки до первого сохранённого EXTINF,
        // кроме MEDIA-SEQUENCE (пересчитываем) и TARGETDURATION
        // (пересчитываем). MAP/KEY и прочее — как есть. URI выброшенных
        // сегментов — тоже выбросить (иначе в заголовке останутся
        // сиротские сегменты).
        val skippedUriIdx = segs.take(firstKept).map { it.uriIdx }.toSet()
        for (j in 0 until firstUriIdx) {
            if (j in skippedUriIdx) continue
            val line = lines[j]
            val t = line.trim()
            when {
                t.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSeq = t.substringAfter(":").trim().toLongOrNull() ?: 0L
                }
                t.startsWith("#EXT-X-TARGETDURATION:") -> { /* пересчитаем ниже */ }
                t.startsWith("#EXTINF:") -> { /* сегменты до firstKept — выброшены */ }
                else -> header.add(line)
            }
        }
        val kept = segs.subList(firstKept, segs.size)
        for (s in kept) {
            if (s.dur > targetDur) targetDur = s.dur
        }
        val out = StringBuilder()
        for (line in header) out.append(line).append('\n')
        out.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSeq + firstKept).append('\n')
        out.append("#EXT-X-TARGETDURATION:").append(targetDur.toInt().coerceAtLeast(1)).append('\n')
        // Всё от первого сохранённого EXTINF до конца файла — как есть:
        // сегменты, теги между ними (DISCONTINUITY и т.п.) и трейлер
        // (#EXT-X-ENDLIST). Выброшенные сегменты — все ДО этой точки.
        for (j in segs[firstKept].extinfIdx until lines.size) {
            out.append(lines[j]).append('\n')
        }
        return TrimResult(out.toString(), kept.size, firstKept)
    }
}
