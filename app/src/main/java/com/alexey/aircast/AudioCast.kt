package com.alexey.aircast

/**
 * v0.9.2 — каст музыки: детект аудио, SoundCloud / YouTube Music /
 * Bandcamp / радио, длительность mp3, метаданные.
 *
 * Принцип тот же, что у видео: прямая ссылка на аудио → во VLC тем
 * же deep link'ом (vlc-x-callback-url/stream); страница аудио-сервиса
 * → фоновый WebView (перехват сети + JS-скан) → аудио-кандидат.
 * Видео в приоритете: если на странице есть и видео, и аудио —
 * играет видео (см. AutoCast.orderedAttempts).
 *
 * Pure Kotlin, no Android imports: тестируется на JVM.
 */
object AudioCast {
    private val AUDIO_EXT = Regex("\\.(mp3|aac|flac|ogg|oga|m4a|wav|opus|mka)([?#]|$)", RegexOption.IGNORE_CASE)

    /**
     * Это прямая ссылка на аудио (не страница). Расширение в пути —
     * или хост/путь, где аудио без расширения по конструкции:
     * SoundCloud media API (api-v2.../media/.../stream/hls|progressive),
     * SoundCloud HLS CDN (cf-hls-media.sndcdn.com — плейлист .m3u8),
     * Bandcamp (tN.bcbits.com/stream/... — mp3-128 без .mp3 в пути).
     * Обычный .m3u8 здесь НЕ аудио: без контекста страницы это
     * видео-HLS (видео — по умолчанию, аудио — только со страницы
     * аудио-сервиса, см. isAudioPage и CastEngine.audioPage).
     */
    fun isAudioUrl(url: String): Boolean {
        val u = url.toLowerCase()
        val path = u.substringBefore('?').substringBefore('#')
        if (AUDIO_EXT.containsMatchIn(path)) return true
        if (u.contains("api-v2.soundcloud.com/media/")) return true
        if (u.contains("cf-hls-media.sndcdn.com")) return true
        if (u.contains("bcbits.com/stream/")) return true
        return false
    }

    /** Страница аудио-сервиса (трек/альбом/радио): кандидаты с неё — аудио. */
    fun isAudioPage(url: String): Boolean {
        val host = url.toLowerCase().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        return host == "soundcloud.com" || host.endsWith(".soundcloud.com") ||
            host == "music.youtube.com" ||
            host == "bandcamp.com" || host.endsWith(".bandcamp.com")
    }

    /**
     * Музыкальные сервисы с DRM — потоки зашифрованы, прямую ссылку
     * вытащить нельзя и во VLC не сыграть. Честно, без попыток обхода:
     * движок показывает понятную ошибку вместо «не найдено».
     */
    fun isUnsupportedMusicService(url: String): Boolean {
        val host = url.toLowerCase().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        return host == "spotify.com" || host.endsWith(".spotify.com") ||
            host == "music.apple.com" || host.endsWith(".music.apple.com")
    }

    /** SoundCloud media API: .../media/soundcloud:tracks:ID/KEY/stream/hls|progressive. */
    fun isSoundCloudMediaUrl(url: String): Boolean =
        url.toLowerCase().contains("api-v2.soundcloud.com/media/")

    private val SC_STREAM_URL_RE = Regex(""""url"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    /**
     * SoundCloud media API отдаёт JSON {"url": "<реальный поток>"}:
     * для HLS — плейлист .m3u8, для progressive — прямой mp3.
     * client_id уже в самом API-URL (его запросил плеер) — свой не нужен.
     */
    fun extractSoundCloudStreamUrl(json: String?): String? {
        if (json.isNullOrBlank()) return null
        val m = SC_STREAM_URL_RE.find(json) ?: return null
        val v = VideoUrls.decodeJsResult("\"${m.groupValues[1]}\"") ?: m.groupValues[1]
        return if (v.isBlank()) null else v
    }

    /** Плейлист радио (.pls/.m3u) — текстовый файл со ссылкой на поток. */
    fun isRadioPlaylistUrl(url: String): Boolean {
        val path = url.toLowerCase().substringBefore('?').substringBefore('#')
        return path.endsWith(".pls") || path.endsWith(".m3u")
    }

    /**
     * Первая ссылка на поток из .pls (File1=http://...) или .m3u
     * (строка с http, # — комментарии). null — потока нет.
     */
    fun parseRadioPlaylist(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            val eq = line.indexOf('=')
            if (eq > 0) {
                val key = line.substring(0, eq).trim().toLowerCase()
                if (key.startsWith("file")) {
                    val v = line.substring(eq + 1).trim()
                    if (v.startsWith("http")) return v
                }
                continue
            }
            if (line.startsWith("http")) return line
        }
        return null
    }

    /** Формат аудио по расширению ("?" — неизвестно, например googlevideo). */
    fun audioFormatOf(url: String): String {
        val u = url.toLowerCase()
        return when {
            u.contains(".flac") -> "flac"
            u.contains(".mp3") -> "mp3"
            u.contains(".aac") -> "aac"
            u.contains(".m4a") -> "m4a"
            u.contains(".ogg") || u.contains(".oga") -> "ogg"
            u.contains(".wav") -> "wav"
            u.contains(".opus") -> "opus"
            u.contains(".mka") -> "mka"
            else -> "?"
        }
    }

    /**
     * Качество аудио по URL (та же эвристика, что qualityOf у видео):
     * FLAC — без потерь, дальше по битрейту в пути/имени.
     * "?" — неизвестно (тогда решает формат/порядок).
     */
    fun audioQualityLabel(url: String): String {
        val u = url.toLowerCase()
        return when {
            u.contains(".flac") -> "FLAC"
            u.contains("320") -> "320 kbps"
            u.contains("256") -> "256 kbps"
            u.contains("192") -> "192 kbps"
            u.contains("128") -> "128 kbps"
            else -> "?"
        }
    }

    /** Это аудиофайл по имени (галерея/файловый менеджер). */
    fun isAudioFileName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val ext = name.substringAfterLast('.', "").toLowerCase()
        return ext in setOf("mp3", "aac", "flac", "ogg", "oga", "m4a", "wav", "opus", "mka")
    }

    // ---------- Длительность mp3 ----------

    /** Один MPEG-аудиофрейм (заголовок): версия, битрейт (kbps), частота (Гц). */
    data class Mp3Frame(val version: Int, val bitrate: Int, val sampleRate: Int)

    private val MP3_BR_MPEG1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val MP3_BR_MPEG2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val MP3_SR_MPEG1 = intArrayOf(44100, 48000, 32000)
    private val MP3_SR_MPEG2 = intArrayOf(22050, 24000, 16000)
    private val MP3_SR_MPEG25 = intArrayOf(11025, 12000, 8000)

    /**
     * Заголовок MPEG Layer III по смещению. null — не фрейм
     * (нет sync, reserved-версия, не Layer III, битрейт/частота
     * вне таблицы).
     */
    fun parseMp3FrameHeader(b: ByteArray, off: Int): Mp3Frame? {
        if (off < 0 || off + 4 > b.size) return null
        if (b[off] != 0xFF.toByte() || (b[off + 1].toInt() and 0xE0) != 0xE0) return null
        val versionBits = (b[off + 1].toInt() shr 3) and 0x3
        val layerBits = (b[off + 1].toInt() shr 1) and 0x3
        if (versionBits == 1 || layerBits != 1) return null
        val version = when (versionBits) { 3 -> 1; 2 -> 2; else -> 25 }
        val bitrateIdx = (b[off + 2].toInt() shr 4) and 0xF
        val srIdx = (b[off + 2].toInt() shr 2) and 0x3
        if (bitrateIdx == 0 || bitrateIdx == 15 || srIdx == 3) return null
        val bitrates = if (version == 1) MP3_BR_MPEG1 else MP3_BR_MPEG2
        val srs = when (version) { 1 -> MP3_SR_MPEG1; 2 -> MP3_SR_MPEG2; else -> MP3_SR_MPEG25 }
        return Mp3Frame(version, bitrates[bitrateIdx], srs[srIdx])
    }

    /** Первый валидный фрейм от смещения (поиск sync 0xFF 0xE?). */
    fun findFirstMp3Frame(b: ByteArray, from: Int): Mp3Frame? {
        var i = maxOf(0, from)
        while (i + 4 <= b.size) {
            if (b[i] == 0xFF.toByte() && (b[i + 1].toInt() and 0xE0) == 0xE0) {
                parseMp3FrameHeader(b, i)?.let { return it }
            }
            i++
        }
        return null
    }

    private fun readU32(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 4 > b.size) return -1
        var v = 0L
        for (k in 0..3) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
        return v
    }

    /**
     * Длительность mp3 по первым байтам файла/потока и полному размеру.
     * Точный путь — заголовок Xing/Info (число фреймов × 1152/576
     * сэмплов); без него — CBR-оценка размер/битрейт (для VBR врёт,
     * честно: оценка, а не точное значение). null — фрейм не найден.
     * Нужна для таймера конца трека (очередь/авто-следующая), иначе
     * трек висел бы на cap-таймере 3 ч.
     */
    fun mp3DurationSec(head: ByteArray, totalSize: Long): Long? {
        var off = 0
        // ID3v2: "ID3" + 2 байта версии + флаги + synchsafe размер.
        if (head.size >= 10 && head[0] == 'I'.toByte() && head[1] == 'D'.toByte() && head[2] == '3'.toByte()) {
            val size = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            off = 10 + size
        }
        // Xing/Info — в первом фрейме (ищем в первых 8 КБ после off).
        val searchEnd = minOf(head.size, off + 8192)
        var i = off
        while (i + 12 <= searchEnd) {
            val isXing = head[i] == 'X'.toByte() && head[i + 1] == 'i'.toByte() &&
                head[i + 2] == 'n'.toByte() && head[i + 3] == 'g'.toByte()
            val isInfo = head[i] == 'I'.toByte() && head[i + 1] == 'n'.toByte() &&
                head[i + 2] == 'f'.toByte() && head[i + 3] == 'o'.toByte()
            if (isXing || isInfo) {
                // Фрейм — за 36/21/13 байт до Xing (MPEG1 stereo/mono,
                // MPEG2/2.5 stereo/mono): пробуем все, берём первый валидный.
                val frame = listOf(36, 21, 13).mapNotNull { parseMp3FrameHeader(head, i - it) }.firstOrNull()
                if (frame != null) {
                    val flags = readU32(head, i + 4)
                    if (flags and 0x1L != 0L) {
                        val frames = readU32(head, i + 8)
                        if (frames > 0 && frame.sampleRate > 0) {
                            val samplesPerFrame = if (frame.version == 1) 1152 else 576
                            return frames * samplesPerFrame / frame.sampleRate
                        }
                    }
                }
            }
            i++
        }
        // Без Xing — CBR-оценка по первому фрейму и размеру файла.
        val frame = findFirstMp3Frame(head, off) ?: return null
        if (frame.bitrate <= 0 || totalSize <= 0) return null
        return totalSize * 8 / (frame.bitrate * 1000L)
    }
}
