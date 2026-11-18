package com.alexey.aircast

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * VLC Cast v0.7.0 — прозрачный приёмник шаринга.
 *
 * Chrome → Поделиться → VLC Cast попадает СЮДА, а не в MainActivity.
 * Тема Theme.NoDisplay: у activity нет UI, она не открывается на
 * экране — пользователь остаётся в Chrome. Задача activity — ТОЛЬКО
 * вытащить URL/URI из intent, стартовать фоновый CastService и сразу
 * finish(). Весь каст (загрузка страницы, детект видео, запуск VLC)
 * происходит в сервисе в фоне; на экране — только уведомление.
 *
 * Если в тексте нет ссылки — тост и тихий выход (приложение всё равно
 * не открывается).
 *
 * v0.9.3.2 (КРАШ 0.9.3.1 — IllegalStateException: did not call
 * finish() prior to onResume() completing): Theme.NoDisplay ОБЯЗАНА
 * вызвать finish() в onCreate, до конца onResume. Редизайн 0.9.3
 * это нарушал — onCreate стартовал сервис (FILE_WAIT) и возвращался
 * без finish(), а копия файла шла в фоновом потоке activity с
 * finish() в runOnUiThread после копии. Теперь activity — только
 * форвардер: синхронный старт CastService (FILE_COPY) + НЕМЕДЛЕННЫЙ
 * finish() в том же onCreate. Никаких потоков и отложенных finish()
 * в activity. Копию файла в cacheDir/aircast-temp делает САМ СЕРВИС
 * (см. FileShareRoute и CastService.copySharedFileAsync).
 *
 * История (для контекста, не трогать без живого репорта):
 * v0.9.1 — content:// URI уходил в сервис напрямую (grant с
 * интентом), но openFileDescriptor в сервисе возвращал null.
 * v0.9.2 — activity сама копировала файл, пока grant жив, и отдавала
 * сервису путь; старт сервиса — только после копии из runOnUiThread,
 * что молча умирало (старт из фона, Android 12+). v0.9.3 —
 * синхронный старт сервиса до копии + доставка пути вторым интентом,
 * но с отложенным finish() — краш NoDisplay-activity.
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
            Toast.makeText(this, "Копирую файл…", Toast.LENGTH_SHORT).show()
            try {
                startForegroundService(CastService.fileCopyIntent(this, streamUri, name, isAudio))
                Log.add("share", "сервис стартовал синхронно (FILE_COPY)")
            } catch (e: Exception) {
                Log.add("share", "FILE_COPY старт error: ${e.javaClass.simpleName}: ${e.message}")
                Toast.makeText(this, "Не удалось запустить VLC Cast", Toast.LENGTH_SHORT).show()
            }
            // v0.9.3.2: finish() — СИНХРОННО в onCreate, до return.
            // NoDisplay-activity без этого роняется системой в onResume
            // (краш 0.9.3.1). Никаких потоков до finish() здесь нет.
            finish()
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
}
