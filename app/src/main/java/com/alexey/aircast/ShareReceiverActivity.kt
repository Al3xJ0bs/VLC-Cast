package com.alexey.aircast

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * VLC Cast v0.7.0 — прозрачный приёмник шаринга.
 *
 * Chrome → Поделиться → VLC Cast попадает СЮДА, а не в MainActivity.
 * Задача activity — вытащить URL/URI из intent, стартовать фоновый
 * CastService и сразу finish(). Весь каст (загрузка страницы, детект
 * видео, запуск VLC) происходит в сервисе в фоне; на экране — только
 * уведомление.
 *
 * Если в тексте нет ссылки — тост и тихий выход (приложение всё равно
 * не открывается).
 *
 * v0.9.3.3 (Telegram: «Не удалось открыть файл», лог 07:51:32):
 * копия файла — В САМОЙ ACTIVITY, а не в сервисе. Сервисная копия
 * 0.9.3.2 падала с FileNotFoundException ENOENT (провайдер отвечает,
 * имя файла из query читается, а openInputStream в сервисе — ENOENT):
 * grant на чтение до сервиса не доходит. Поэтому ветка файла —
 * ПОЛУПРОЗРАЧНАЯ activity с прогрессом («Копирую файл…»), которая
 * живёт до конца копии: grant пришёл в этот контекст, и только
 * здесь openInputStream гарантированно видит файл. По завершении —
 * старт сервиса с готовым путём + finish(). При ошибке копии —
 * fallback на сервис (FILE_COPY: сервисная копия + URI-путь движка,
 * там честная ошибка с классом исключения и «Поделиться логом»).
 *
 * Тема — Theme.Translucent.NoTitleBar, НЕ NoDisplay: NoDisplay-activity
 * обязана finish() в onCreate (краш 0.9.3.1), а здесь activity
 * обязана ЖИТЬ до конца копии. Для ссылок поведение то же, что
 * раньше: старт сервиса + finish() сразу, окна не видно (контента
 * нет, фон прозрачный). configChanges — чтобы поворот не пересоздал
 * activity посреди копии.
 *
 * История (для контекста, не трогать без живого репорта):
 * v0.9.1 — content:// URI уходил в сервис напрямую (grant с
 * интентом), но openFileDescriptor в сервисе возвращал null.
 * v0.9.2 — activity сама копировала файл, пока grant жив, и отдавала
 * сервису путь; старт сервиса — только после копии из runOnUiThread,
 * что молча умирало (старт из фона, Android 12+). v0.9.3 —
 * синхронный старт сервиса до копии + доставка пути вторым интентом,
 * но с отложенным finish() — краш NoDisplay-activity. v0.9.3.2 —
 * копия в сервисе, activity только форвардит URI и finish() сразу:
 * краш ушёл, но копия в сервисе падает с ENOENT.
 */
class ShareReceiverActivity : Activity() {

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
            // v0.9.3.3: диагностика grant'а — в лог: scheme/authority,
            // getType и какой именно open упадёт и с каким классом.
            val mime = try {
                contentResolver.getType(streamUri)
            } catch (e: Exception) {
                "getType error: ${e.javaClass.simpleName}"
            }
            Log.add("share", "файл: scheme=${streamUri.scheme} authority=${streamUri.authority} intentType=$mimeType getType=$mime")
            // Полупрозрачный экран с прогрессом — activity живёт до
            // конца копии (см. шапку класса).
            setContentView(copyProgressView())
            Thread {
                val path = copySharedFileWithTimeout(streamUri)
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        Log.add("share", "activity уничтожена до конца копии — путь не доставляю")
                        return@runOnUiThread
                    }
                    if (path != null) {
                        try {
                            startForegroundService(CastService.filePathCastIntent(this, path, name, isAudio))
                            Log.add("share", "сервис стартовал с готовым путём: $path")
                        } catch (e: Exception) {
                            Log.add("share", "старт сервиса с путём error: ${e.javaClass.simpleName}: ${e.message}")
                            Toast.makeText(this, "Не удалось запустить VLC Cast", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Копия в activity не удалась — fallback на
                        // сервис (его копия + URI-путь движка с честной
                        // ошибкой и «Поделиться логом»).
                        try {
                            startForegroundService(CastService.fileCopyIntent(this, streamUri, name, isAudio))
                            Log.add("share", "копия в activity не удалась — fallback на сервис (FILE_COPY)")
                        } catch (e: Exception) {
                            Log.add("share", "FILE_COPY старт error: ${e.javaClass.simpleName}: ${e.message}")
                            Toast.makeText(this, "Не удалось запустить VLC Cast", Toast.LENGTH_SHORT).show()
                        }
                    }
                    finish()
                }
            }.apply { this.isDaemon = true; this.name = "aircast-activity-copy" }.start()
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

    /** Полупрозрачный оверлей с прогрессом для ветки файла. */
    private fun copyProgressView(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        box.addView(ProgressBar(this).apply { isIndeterminate = true })
        box.addView(TextView(this).apply {
            text = "Копирую файл…"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })
        root.addView(box, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return root
    }

    /**
     * Копия расшаренного файла в cacheDir/aircast-temp — пока grant
     * на URI жив (activity, которая его получила). Таймаут 60 с:
     * если openInputStream висит дольше — null (вызывающий идёт на
     * fallback в сервис), а не вечная тишина. Зависший поток копии —
     * daemon; блокирующий I/O провайдера может не ответить на
     * interrupt — тогда поток дописывает temp-файл в фоне, который
     * никто не использует (stale-cleanup сервиса его удалит).
     */
    private fun copySharedFileWithTimeout(uri: android.net.Uri, timeoutMs: Long = 60_000L): String? {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r).apply { this.isDaemon = true; this.name = "aircast-activity-copy-worker" }
        }
        val future = executor.submit<String?> { copySharedFile(uri) }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.add("file", "копия в activity: таймаут ${timeoutMs / 1000} с — fallback на сервис")
            future.cancel(true)
            null
        } catch (e: Exception) {
            Log.add("file", "копия в activity: ожидание error: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            executor.shutdownNow()
        }
    }

    /** Копия в cacheDir/aircast-temp; null — open вернул null/ошибка. */
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
}
