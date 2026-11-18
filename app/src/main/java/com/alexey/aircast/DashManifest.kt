package com.alexey.aircast

/**
 * v0.9.3 (#1): синтез DASH-манифеста (MPD) из раздельных видео/аудио
 * дорожек (YouTube adaptiveFormats: video-only fMP4 + audio-only
 * fMP4 — VISIONOS и др., где видео и аудио идут раздельно).
 *
 * VLC на tvOS играет такой MPD: обе дорожки — через SegmentBase
 * (initRange/indexRange из InnerTube), сегменты VLC тянет напрямую
 * с googlevideo (Referer не нужен). Это та же структура, что у
 * собственного dashManifestUrl YouTube.
 *
 * Честные ограничения:
 * - без initRange манифест не строится (null): VLC без него не найдёт
 *   moov-бокс. indexRange опционален (без него — только Initialization).
 * - Generic pairing «видео+аудио со страницы» не делается: надёжный
 *   сигнал о паре есть только у YouTube adaptiveFormats (один ответ
 *   /player). Страницы с раздельными дорожками без такого сигнала —
 *   вне скопа.
 * - Длительность — из lengthSeconds; неизвестна — MPD без
 *   mediaPresentationDuration (VLC играет, таймер конца — по факту).
 *
 * Pure Kotlin, JVM-тесты.
 */
object DashManifest {
    data class Track(
        val url: String,
        /** "video/mp4" / "audio/mp4" / "audio/webm" (до ';' из mimeType). */
        val mimeType: String,
        /** "avc1.640028" / "mp4a.40.2" / "opus" (из codecs="..." mimeType). */
        val codecs: String,
        val bandwidth: Long,
        val width: Int? = null,
        val height: Int? = null,
        val frameRate: Int? = null,
        val initStart: Long? = null,
        val initEnd: Long? = null,
        val indexStart: Long? = null,
        val indexEnd: Long? = null,
    )

    /** Собрать MPD, или null (нет initRange у видео или аудио). */
    fun build(video: Track, audio: Track, durationSec: Long?): String? {
        if (video.initStart == null || video.initEnd == null) return null
        if (audio.initStart == null || audio.initEnd == null) return null
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\"")
        val dur = durationSec?.takeIf { it > 0 }?.let { ptDuration(it) }
        if (dur != null) sb.append(" mediaPresentationDuration=\"$dur\"")
        sb.append(" minBufferTime=\"PT1.5S\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n")
        sb.append("  <Period>\n")
        appendAdaptation(sb, video, "v")
        appendAdaptation(sb, audio, "a")
        sb.append("  </Period>\n</MPD>\n")
        return sb.toString()
    }

    private fun appendAdaptation(sb: StringBuilder, t: Track, id: String) {
        sb.append("    <AdaptationSet mimeType=\"${xmlEscape(t.mimeType)}\"")
        if (t.codecs.isNotBlank()) sb.append(" codecs=\"${xmlEscape(t.codecs)}\"")
        if (t.width != null) sb.append(" width=\"${t.width}\"")
        if (t.height != null) sb.append(" height=\"${t.height}\"")
        if (t.frameRate != null) sb.append(" frameRate=\"${t.frameRate}\"")
        sb.append(" segmentAlignment=\"true\">\n")
        sb.append("      <Representation id=\"$id\" bandwidth=\"${t.bandwidth}\">\n")
        sb.append("        <BaseURL>${xmlEscape(t.url)}</BaseURL>\n")
        sb.append("        <SegmentBase")
        if (t.indexStart != null && t.indexEnd != null) sb.append(" indexRange=\"${t.indexStart}-${t.indexEnd}\"")
        sb.append(">\n")
        sb.append("          <Initialization range=\"${t.initStart}-${t.initEnd}\"/>\n")
        sb.append("        </SegmentBase>\n")
        sb.append("      </Representation>\n")
        sb.append("    </AdaptationSet>\n")
    }

    /** Длительность в ISO 8601 (PT1H2M3S, нулевые части пропускаются). */
    fun ptDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val sb = StringBuilder("PT")
        if (h > 0) sb.append("${h}H")
        if (m > 0) sb.append("${m}M")
        sb.append("${s}S")
        return sb.toString()
    }

    fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
