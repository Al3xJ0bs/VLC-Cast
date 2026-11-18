package com.alexey.aircast

import android.app.ActivityManager
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Environment
import java.io.File

/**
 * v0.9.3.5 — общая логика завершения загрузки обновления, независимая
 * от Activity: её вызывают и receiver в MainActivity, и
 * manifest-receiver UpdateDownloadReceiver (он живёт, даже когда
 * приложение убито mid-download).
 *
 * Почему вообще понадобилось: в 0.9.3.1 краш при запуске чинили
 * флагом RECEIVER_NOT_EXPORTED — и он же молча отрезал доставку
 * системного ACTION_DOWNLOAD_COMPLETE (DownloadManager — другой UID;
 * по документации Android системный broadcast требует
 * RECEIVER_EXPORTED). Загрузка шла, а приложение о её завершении не
 * узнавало — install-промпт не появлялся. Плюс два зазора: receiver
 * жил на Activity (ушёл из приложения mid-download — receiver умер
 * вместе с ней), а прямой startActivity из фона режется ограничением
 * Android 10+ на запуск activity из фона (молча, без исключения).
 * Поэтому три слоя: activity-receiver (EXPORTED) + manifest-receiver
 * + re-check при запуске, а из фона — уведомление с PendingIntent
 * (оно от ограничения на запуск из фона освобождено).
 */
object UpdateInstall {
    /** Скачанный APK ждёт разрешения на установку (файл уже на месте). */
    const val PREF_PENDING_INSTALL = "update_pending_install"
    const val UPDATE_NOTIF_ID = 9101

    fun updateFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), AppUpdater.UPDATE_APK_NAME)

    fun canRequestInstalls(ctx: Context): Boolean =
        ctx.packageManager.canRequestPackageInstalls()

    /** Приложение на переднем плане? (прямой промпт разрешён только тогда). */
    fun isAppForeground(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pid = android.os.Process.myPid()
            am.runningAppProcesses?.any {
                it.pid == pid && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } == true
        } catch (e: Exception) {
            Log.add("update", "isAppForeground error: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Промпт установки скачанного APK. false — показать не удалось. */
    fun promptInstall(ctx: Context, file: File): Boolean {
        return try {
            val uri = LogFileProvider.uriForUpdateApk(ctx)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
            Log.add("update", "промпт установки показан: ${file.name}")
            true
        } catch (e: Exception) {
            Log.add("update", "промпт установки error: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun smallIcon(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("ic_launcher", "mipmap", ctx.packageName)
        return if (id != 0) id else android.R.drawable.ic_media_play
    }

    private fun ensureChannel(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(AutoCast.NOTIF_CHANNEL_ID, "VLC Cast", NotificationManager.IMPORTANCE_DEFAULT)
            )
        } catch (e: Exception) {
            Log.add("update", "ensureChannel error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Уведомление «обновление скачано — нажми, чтобы установить» (тап — BAL-exempt). */
    fun postInstallReadyNotification(ctx: Context, file: File) {
        try {
            ensureChannel(ctx)
            val uri = LogFileProvider.uriForUpdateApk(ctx)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(
                ctx, UPDATE_NOTIF_ID, install,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(ctx, AutoCast.NOTIF_CHANNEL_ID)
                .setSmallIcon(smallIcon(ctx))
                .setContentTitle("VLC Cast — обновление скачано")
                .setContentText("Нажми, чтобы установить новую версию.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(UPDATE_NOTIF_ID, n)
            Log.add("update", "уведомление «обновление готово» показано")
        } catch (e: Exception) {
            Log.add("update", "уведомление об обновлении error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Уведомление «разреши установку» — тап открывает приложение (attach сам откроет настройки). */
    fun postNeedsPermissionNotification(ctx: Context) {
        try {
            ensureChannel(ctx)
            val open = Intent().setClassName("com.alexey.aircast", "com.alexey.aircast.MainActivity").apply {
                putExtra(AutoCast.EXTRA_MODE, AutoCast.MODE_INSTRUCTION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pi = PendingIntent.getActivity(
                ctx, UPDATE_NOTIF_ID + 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(ctx, AutoCast.NOTIF_CHANNEL_ID)
                .setSmallIcon(smallIcon(ctx))
                .setContentTitle("VLC Cast — обновление скачано")
                .setContentText("Разреши установку из неизвестных источников — установка начнётся сама.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(UPDATE_NOTIF_ID + 2, n)
            Log.add("update", "уведомление «нужно разрешение на установку» показано")
        } catch (e: Exception) {
            Log.add("update", "уведомление о разрешении error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Честное уведомление о провале загрузки (только из фона; на экране — статус). */
    fun postUpdateFailedNotification(ctx: Context, reason: String) {
        try {
            ensureChannel(ctx)
            val open = Intent().setClassName("com.alexey.aircast", "com.alexey.aircast.MainActivity").apply {
                putExtra(AutoCast.EXTRA_MODE, AutoCast.MODE_INSTRUCTION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pi = PendingIntent.getActivity(
                ctx, UPDATE_NOTIF_ID + 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(ctx, AutoCast.NOTIF_CHANNEL_ID)
                .setSmallIcon(smallIcon(ctx))
                .setContentTitle("VLC Cast — не удалось скачать обновление")
                .setContentText(reason)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(UPDATE_NOTIF_ID + 1, n)
            Log.add("update", "уведомление о провале загрузки показано: $reason")
        } catch (e: Exception) {
            Log.add("update", "уведомление о провале error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Общий обработчик завершения загрузки по id. Возвращает true,
     * если id был наш и загрузка обработана (успех/провал/ещё идёт).
     * statusSink — текст статуса на экран (только из Activity).
     */
    fun handleDownloadFinished(ctx: Context, id: Long, statusSink: ((String) -> Unit)?): Boolean {
        val prefs = ctx.getSharedPreferences("aircast", Context.MODE_PRIVATE)
        val storedId = prefs.getLong(AppUpdater.PREF_DOWNLOAD_ID, -1L)
        if (id != storedId) {
            Log.add("update", "завершение загрузки id=$id — не наше (stored=$storedId), игнор")
            return false
        }
        val dm = try {
            ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        } catch (e: Exception) {
            Log.add("update", "DownloadManager недоступен: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        val status: Int
        try {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) {
                    prefs.edit().remove(AppUpdater.PREF_DOWNLOAD_ID).apply()
                    AppUpdater.cleanupStaleApk(ctx)
                    Log.add("update", "загрузки id=$id больше нет — забываем")
                    return false
                }
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        } catch (e: Exception) {
            Log.add("update", "query загрузки error: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        val fg = isAppForeground(ctx)
        Log.add("update", "загрузка id=$id: статус DownloadManager=$status, foreground=$fg")
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                prefs.edit().remove(AppUpdater.PREF_DOWNLOAD_ID).apply()
                val file = updateFile(ctx)
                if (!file.exists() || file.length() == 0L) {
                    Log.add("update", "загрузка успешна, но файл пуст/отсутствует: ${file.absolutePath}")
                    statusSink?.invoke("Скачивание завершено, но файл повреждён. Попробуй ещё раз.")
                    if (!fg) postUpdateFailedNotification(ctx, "Скачанный файл повреждён. Открой VLC Cast и попробуй ещё раз.")
                    AppUpdater.cleanupStaleApk(ctx)
                    return true
                }
                Log.add("update", "скачано: ${file.name}, ${file.length()} Б; canRequestInstalls=${canRequestInstalls(ctx)}")
                if (!canRequestInstalls(ctx)) {
                    // Разрешения на установку нет: файл уже скачан —
                    // запоминаем «ждёт установки», настройки откроются
                    // при следующем запуске (attach), а после возврата
                    // установка начнётся без повторного скачивания.
                    prefs.edit().putBoolean(PREF_PENDING_INSTALL, true).apply()
                    statusSink?.invoke("Обновление скачано. Разреши установку из неизвестных источников — установка начнётся сама.")
                    if (!fg) postNeedsPermissionNotification(ctx)
                    return true
                }
                statusSink?.invoke("Готово — открывается установка…")
                deliverInstall(ctx, file)
                return true
            }
            DownloadManager.STATUS_FAILED -> {
                prefs.edit().remove(AppUpdater.PREF_DOWNLOAD_ID).apply()
                Log.add("update", "скачивание провалено (DownloadManager STATUS_FAILED)")
                statusSink?.invoke("Не удалось скачать обновление. Проверь сеть и попробуй ещё раз.")
                if (!fg) postUpdateFailedNotification(ctx, "Не удалось скачать обновление. Проверь сеть и попробуй ещё раз.")
                AppUpdater.cleanupStaleApk(ctx)
                return true
            }
            else -> {
                Log.add("update", "загрузка ещё идёт (статус $status)")
                return true
            }
        }
    }

    /**
     * Доставка установки: на переднем плане — прямой промпт;
     * в фоне — уведомление (прямой startActivity режется BAL).
     * Промпт не удался и на переднем плане — тоже уведомление.
     */
    fun deliverInstall(ctx: Context, file: File) {
        if (isAppForeground(ctx)) {
            if (!promptInstall(ctx, file)) {
                Log.add("update", "прямой промпт не удался — показываю уведомление")
                postInstallReadyNotification(ctx, file)
            }
        } else {
            Log.add("update", "приложение в фоне — установка через уведомление (BAL)")
            postInstallReadyNotification(ctx, file)
        }
    }
}
