package com.alexey.aircast

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.io.File

/**
 * VLC Cast v0.7.0 — прозрачный приёмник шаринга.
 *
 * Chrome → Поделиться → VLC Cast попадает СЮДА, а не в MainActivity.
 * Тема Theme.NoDisplay: у activity нет UI, она не открывается на
 * экране — пользователь остаётся в Chrome. Задача activity — только
 * вытащить URL из intent, стартовать фоновый CastService и сразу
 * finish(). Весь каст (загрузка страницы, детект видео, запуск VLC)
 * происходит в сервисе в фоне; на экране — только уведомление.
 *
 * Если в тексте нет ссылки — тост и тихий выход (приложение всё равно
 * не открывается).
 *
 * v0.9.1: ACTION_SEND с видео-файлом (Telegram отдаёт сам файл, а не
 * ссылку; то же — галерея): content:// URI уходит в сервис
 * (fileCastIntent — data URI + grant), оригинал не трогается.
 *
 * v0.9.2 (БАГ 0.9.1, шаринг из Telegram — «Не удалось открыть файл»):
 * grant на URI живёт только пока жива activity — сервис его уже не
 * получает (finish() сразу после старта), поэтому openFileDescriptor
 * и openInputStream в сервисе возвращали null, хотя DISPLAY_NAME
 * здесь же читался. Теперь activity САМА копирует файл в
 * cacheDir/aircast-temp (TempFiles.tempName()), пока grant жив,
 * и сервису отдаёт путь к копии (EXTRA_FILE_PATH). Старый путь
 * через content URI оставлен как fallback на случай ошибки копии.
 *
 * v0.9.3 (ХОТФИКС 0.9.2 — шаринг из Telegram: тост «Копирую файл…»
 * мелькает, дальше тишина): в 0.9.2 сервис стартовал только ПОСЛЕ
 * копии, из runOnUiThread фонового потока — к тому моменту activity
 * уже мертва, приложение в фоне, и startForegroundService падает
 * (Android 12+ ForegroundServiceStartNotAllowedException) без UI.
 * Теперь сервис стартует СИНХРОННО в onCreate, ДО копии (FILE_WAIT,
 * уведомление «Копирую файл…» сразу), а путь копии доставляется
 * вторым интентом уже работающему foreground-сервису (FILE_DELIVER).
 * Весь старт и доставка — в try/catch с логом. Копия — с таймаутом
 * 60 с: если openInputStream висит дольше — fallback на URI-путь,
 * а не вечная тишина (ошибка файла в сервисе — с «Поделиться логом»).
 */
class ShareReceiverActivity : Activity() {
    companion object {
        /** v0.9.3: копия расшаренного файла дольше этого — fallback на URI. */
        const val COPY_TIMEOUT_MS = 60_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(filesDir)
        // v0.9.1: видеофайл (Telegram/галерея) — content:// URI.
        // v0.9.2: то же — для аудиофайлов (audio/* по MIME: файл
        // кастится как аудио, а не как видео).
        val streamUri = if (intent?.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        } else null
        val mimeType = intent?.type ?: ""
        if (streamUri != null && (mimeType.startsWith("video/") || mimeType.startsWith("audio/"))) {
            val name = queryDisplayName(streamUri)
            val isAudio = mimeType.startsWith("audio/")
            Log.add("share", "получен ${if (isAudio) "аудиофайл" else "видеофайл"}: ${name ?: streamUri}")
            // v0.9.3: сервис стартует СИНХРОННО, ДО копии (см. шапку
            // класса). Отложенный старт из фонового потока умирал
            // молча вместе с NoDisplay-activity.
            Toast.makeText(this, "Копирую файл…", Toast.LENGTH_SHORT).show()
            var waitStarted = false
            try {
                startForegroundService(CastService.fileWaitIntent(this, name, isAudio))
                waitStarted = true
                Log.add("share", "сервис стартовал синхронно (FILE_WAIT)")
            } catch (e: Exception) {
                Log.add("share", "FILE_WAIT старт error: ${e.javaClass.simpleName}: ${e.message} — доставка попробует startForegroundService")
            }
            Thread {
                val tempPath = copySharedFileWithTimeout(streamUri)
                runOnUiThread {
                    if (tempPath != null) {
                        deliverFilePath(tempPath, name, isAudio, waitStarted)
                    } else {
                        Log.add("share", "копия не удалась — fallback на URI для сервиса")
                        deliverFileUri(streamUri, name, isAudio, waitStarted)
                    }
                    finish()
                }
            }.start()
            return
        }
        val url = when (intent?.action) {
            Intent.ACTION_SEND -> AutoCast.extractFirstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            Intent.ACTION_VIEW -> intent.data?.toString()?.takeIf { it.isNotBlank() }
            else -> null
        }
        if (url == null) {
            Toast.makeText(this, "Это не ссылка — поделись ссылкой на страницу с видео или трек", Toast.LENGTH_SHORT).show()
            Log.add("share", "share без ссылки (action=${intent?.action})")
        } else {
            Log.add("share", "получена ссылка: ${VideoUrls.safeHost(url)}")
            startForegroundService(CastService.castIntent(this, url))
        }
        finish()
    }

    /** Имя файла из content:// URI (DisplayName из MediaStore/провайдера). */
    private fun queryDisplayName(uri: android.net.Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * v0.9.3: копия с таймаутом COPY_TIMEOUT_MS. Если openInputStream
     * висит дольше — null (вызывающий идёт на fallback по URI),
     * а не вечная тишина. Зависший поток копии — daemon: cancel(true)
     * его прерывает, но блокирующий I/O провайдера может не ответить
     * на interrupt — тогда поток дописывает temp-файл в фоне, который
     * никто не использует (stale-cleanup сервиса его удалит).
     */
    private fun copySharedFileWithTimeout(uri: android.net.Uri, timeoutMs: Long = COPY_TIMEOUT_MS): String? {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r).apply { isDaemon = true; name = "aircast-copy" }
        }
        val future = executor.submit<String?> { copySharedFile(uri) }
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.add("file", "копия: таймаут ${timeoutMs / 1000} с — fallback на URI для сервиса")
            future.cancel(true)
            null
        } catch (e: Exception) {
            Log.add("file", "копия: ожидание error: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * v0.9.3: доставка пути копии в уже работающий сервис
     * (FILE_DELIVER, обычный startService — сервис foreground).
     * Если синхронный старт не удался — последняя попытка через
     * startForegroundService со старым интентом 0.9.2. Всё в
     * try/catch с логом: тишина недопустима.
     */
    private fun deliverFilePath(path: String, name: String?, isAudio: Boolean, waitStarted: Boolean) {
        if (waitStarted) {
            try {
                startService(CastService.fileDeliverIntent(this, path, name, isAudio))
                Log.add("share", "путь копии доставлен (FILE_DELIVER): $path")
                return
            } catch (e: Exception) {
                Log.add("share", "FILE_DELIVER error: ${e.javaClass.simpleName}: ${e.message} — fallback на startForegroundService")
            }
        }
        try {
            startForegroundService(CastService.filePathCastIntent(this, path, name, isAudio))
            Log.add("share", "путь копии доставлен (startForegroundService, fallback)")
        } catch (e: Exception) {
            Log.add("share", "доставка пути error: ${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(this, "Не удалось запустить VLC Cast", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * v0.9.3: fallback на URI-путь (копия не удалась или таймаут):
     * та же схема — сначала в работающий сервис, иначе
     * startForegroundService. Ошибка открытия файла в сервисе —
     * с «Поделиться логом» (onFileError в CastService).
     */
    private fun deliverFileUri(uri: android.net.Uri, name: String?, isAudio: Boolean, waitStarted: Boolean) {
        if (waitStarted) {
            try {
                startService(CastService.fileCastIntent(this, uri, name, isAudio))
                Log.add("share", "URI доставлен в работающий сервис (fallback копии)")
                return
            } catch (e: Exception) {
                Log.add("share", "доставка URI error: ${e.javaClass.simpleName}: ${e.message} — fallback на startForegroundService")
            }
        }
        try {
            startForegroundService(CastService.fileCastIntent(this, uri, name, isAudio))
            Log.add("share", "URI доставлен (startForegroundService, fallback)")
        } catch (e: Exception) {
            Log.add("share", "доставка URI error: ${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(this, "Не удалось запустить VLC Cast", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * v0.9.2: копия расшаренного файла в cacheDir/aircast-temp —
     * пока grant на URI жив (в activity). Возвращает абсолютный
     * путь к копии или null (openInputStream вернул null / ошибка).
     * Логируется размер копии и класс исключения. Оригинал не трогается;
     * удаление копии — в движке после каста + stale-cleanup сервиса.
     */
    private fun copySharedFile(uri: android.net.Uri): String? {
        return try {
            val dir = File(cacheDir, TempFiles.TEMP_DIR_NAME).apply { mkdirs() }
            val tmp = File(dir, TempFiles.tempName())
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                Log.add("file", "копия в activity: openInputStream вернул null: $uri")
                return null
            }
            Log.add("file", "скопирован в activity: ${tmp.name}, $bytes Б → ${tmp.absolutePath}")
            tmp.absolutePath
        } catch (e: Exception) {
            Log.add("file", "копия в activity error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
