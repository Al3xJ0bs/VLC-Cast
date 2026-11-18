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
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(filesDir)
        // v0.9.1: видеофайл (Telegram/галерея) — content:// URI.
        val streamUri = if (intent?.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        } else null
        val mimeType = intent?.type ?: ""
        if (streamUri != null && mimeType.startsWith("video/")) {
            val name = queryDisplayName(streamUri)
            Log.add("share", "получен видеофайл: ${name ?: streamUri}")
            startForegroundService(CastService.fileCastIntent(this, streamUri, name))
            finish()
            return
        }
        val url = when (intent?.action) {
            Intent.ACTION_SEND -> AutoCast.extractFirstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            Intent.ACTION_VIEW -> intent.data?.toString()?.takeIf { it.isNotBlank() }
            else -> null
        }
        if (url == null) {
            Toast.makeText(this, "Это не ссылка — поделись ссылкой на страницу с видео", Toast.LENGTH_SHORT).show()
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
