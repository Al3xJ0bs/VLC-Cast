package com.alexey.aircast

import java.io.File
import java.io.RandomAccessFile

/**
 * v0.9.1 — шаринг видеофайлов: Telegram и галерея отдают сам
 * файл (content:// URI), а не ссылку. Файл отдаётся во VLC через
 * локальный HTTP-сервер телефона (StreamProxy /file) с Range —
 * перемотка работает.
 *
 * Источник файла: интерфейс FileSource (size + чтение с позиции).
 * Android: FdFileSource поверх ParcelFileDescriptor (живёт в
 * StreamProxy.kt, рядом с сервером). JVM: JavaFileSource поверх
 * RandomAccessFile — для тестов.
 *
 * Автоудаление (по слову Алексея): временная копия файла
 * удаляется после каста (и при неудаче каста), а stale-файлы
 * чистятся при следующем запуске — см. TempFiles.cleanup и
 * вызовы в CastEngine/CastService.
 */
interface FileSource {
    fun size(): Long
    fun readAt(offset: Long, length: Int): ByteArray?
    fun close()
}

/** FileSource поверх обычного файла (JVM-тесты, temp-копии). */
class JavaFileSource(private val file: File) : FileSource {
    private val raf = RandomAccessFile(file, "r")
    private val sz = file.length()

    override fun size(): Long = sz

    override fun readAt(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || offset >= sz) return null
        return try {
            raf.seek(offset)
            val n = minOf(length, (sz - offset).toInt())
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = raf.read(buf, read, n - read)
                if (r < 0) break
                read += r
            }
            if (read == 0) null else if (read == n) buf else buf.copyOf(read)
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        try { raf.close() } catch (e: Exception) { /* ignore */ }
    }
}

/** HTTP Range: парсинг заголовка Range и ответные параметры. */
object RangeServe {
    data class Range(val start: Long, val end: Long, val status: Int) {
        val length: Long get() = end - start + 1
        fun contentRange(total: Long): String = "bytes $start-$end/$total"
    }

    /**
     * Разобрать Range заголовок. Возвращает диапазон для
     * ответа 206, или null — отдать весь файл (200).
     * Формы: "bytes=START-END", "bytes=START-", "bytes=-SUFFIX".
     * Невалидный диапазон — null (200), как у обычных серверов.
     */
    fun parseRange(header: String?, totalSize: Long): Range? {
        if (header == null || totalSize <= 0) return null
        val m = Regex("^bytes=(\\d*)-(\\d*)\$").find(header.trim()) ?: return null
        val startS = m.groupValues[1]
        val endS = m.groupValues[2]
        return try {
            if (startS.isNotEmpty() && endS.isNotEmpty()) {
                val s = startS.toLong()
                val e = endS.toLong()
                if (s > e || s >= totalSize) return null
                Range(s, minOf(e, totalSize - 1), 206)
            } else if (startS.isNotEmpty()) {
                val s = startS.toLong()
                if (s >= totalSize) return null
                Range(s, totalSize - 1, 206)
            } else if (endS.isNotEmpty()) {
                val suffix = endS.toLong()
                if (suffix <= 0) return null
                val s = maxOf(0L, totalSize - suffix)
                Range(s, totalSize - 1, 206)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Временные копии файлов для каста (cacheDir/aircast-temp). */
object TempFiles {
    const val TEMP_DIR_NAME = "aircast-temp"

    /** Имя временной копии: aircast-<millis>-<random>.tmp */
    fun tempName(): String = "aircast-${System.currentTimeMillis()}-${(0..9999).random()}.tmp"

    /**
     * Чистка stale-файлов в папке temp (старше maxAgeMs, по
     * умолчанию 1 час). Вызывается при старте сервиса и при
     * завершении каста. Возвращает число удалённых.
     */
    fun cleanup(dir: File, maxAgeMs: Long = 3_600_000L): Int {
        if (!dir.isDirectory) return 0
        val now = System.currentTimeMillis()
        var deleted = 0
        for (f in dir.listFiles() ?: emptyArray()) {
            if (f.isFile && f.name.startsWith("aircast-") && now - f.lastModified() > maxAgeMs) {
                if (f.delete()) deleted++
            }
        }
        return deleted
    }
}
