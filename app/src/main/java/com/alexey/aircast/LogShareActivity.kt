package com.alexey.aircast

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * v0.9.0 — «Поделиться логом»: прозрачная activity (NoDisplay) из
 * действия в уведомлении «Видео не найдено». Открывает системный
 * share sheet с файлом aircast.log (через LogFileProvider) и сразу
 * закрывается. Без UI, без запуска MainActivity.
 *
 * Лог — в filesDir/aircast.log (см. Log.init в CastService/MainActivity).
 * Если файла нет — просто ничего не шарим (тихо закрываемся).
 */
class LogShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Log.init(filesDir)
            val file = Log.logFile()
            if (file != null && file.exists() && file.length() > 0) {
                val uri: Uri = LogFileProvider.uriForFile(this, file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "VLC Cast — лог")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Поделиться логом VLC Cast"))
                Log.add("app", "лог поделён (${file.length()} Б)")
            } else {
                Log.add("app", "поделиться логом: файл пуст или отсутствует")
            }
        } catch (e: Exception) {
            Log.add("app", "поделиться логом error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            finish()
        }
    }
}
