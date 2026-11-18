package com.alexey.aircast

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * v0.9.3 — самообновление ВНУТРИ приложения, без браузера.
 *
 * Раньше кнопка «Скачать» открывала latest.url в браузере
 * (ACTION_VIEW) — пользователь сам качал APK и сам его искал.
 * Теперь: DownloadManager качает APK из latest.url (прогресс —
 * в системном уведомлении, без лишних разрешений: файл — в
 * getExternalFilesDir), по завершении — автоматический промпт
 * установки через FileProvider (LogFileProvider отдаёт APK
 * read-only, application/vnd.android.package-archive,
 * FLAG_GRANT_READ_URI_PERMISSION).
 *
 * Перед скачиванием — гейт UpdateGate (чистая логика, JVM-тесты):
 * packageManager.canRequestPackageInstalls(); если нет — один раз
 * в Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES (package URI),
 * после возврата скачивание продолжается само (pending URL в prefs).
 *
 * Старый APK чистится: перед новой загрузкой и при запуске, если
 * загрузка провалилась/зависла. Честно: точного колбэка «установка
 * завершена» у пути через ACTION_VIEW нет — поэтому файл после
 * успешного промпта установки НЕ удаляется сразу, а убирается при
 * следующем запуске приложения (или перед следующей загрузкой).
 *
 * Подпись проверяет сам Android при установке поверх (тот же
 * keystore, что у текущей версии): чужой APK с другой подписью
 * просто не встанет — система покажет ошибку установки.
 */
class AppUpdater(private val activity: Activity) {
    companion object {
        /** Имя файла обновления (фиксированное — старые не копятся). */
        const val UPDATE_APK_NAME = "VLC-Cast-update.apk"
        /** onActivityResult: возврат из настроек установки. */
        const val REQUEST_INSTALL_SETTINGS = 4101
        const val PREF_PENDING_URL = "update_pending_url"
        const val PREF_PENDING_NAME = "update_pending_name"
        const val PREF_SETTINGS_SHOWN = "update_install_settings_shown"
        const val PREF_DOWNLOAD_ID = "update_download_id"

        /** Файл APK обновления (external files, DownloadManager). */
        fun updateFile(ctx: Context): File =
            File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), UPDATE_APK_NAME)

        /** Старый APK — удалить (перед загрузкой / при запуске). */
        fun cleanupStaleApk(ctx: Context) {
            val f = updateFile(ctx)
            if (f.exists() && f.delete()) Log.add("update", "старый APK обновления удалён: ${f.name}")
        }
    }

    private val prefs = activity.getSharedPreferences("aircast", Context.MODE_PRIVATE)
    private var receiver: BroadcastReceiver? = null
    private var statusCallback: ((String) -> Unit)? = null

    /** Разрешение на установку из неизвестных источников есть? */
    fun canRequestInstalls(): Boolean =
        activity.packageManager.canRequestPackageInstalls()

    /** Решение гейта (UpdateGate — чистая логика). */
    fun gateDecision(): UpdateGate.Decision =
        UpdateGate.decide(canRequestInstalls(), prefs.getBoolean(PREF_SETTINGS_SHOWN, false))

    /** Один раз — в системные настройки установки из неизвестных источников. */
    fun openInstallSettings() {
        prefs.edit().putBoolean(PREF_SETTINGS_SHOWN, true).apply()
        try {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivityForResult(i, REQUEST_INSTALL_SETTINGS)
            Log.add("update", "открыты настройки установки из неизвестных источников")
        } catch (e: Exception) {
            Log.add("update", "открытие настроек установки error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Запомнить pending-обновление (продолжить после возврата из настроек). */
    fun rememberPending(url: String, versionName: String) {
        prefs.edit().putString(PREF_PENDING_URL, url).putString(PREF_PENDING_NAME, versionName).apply()
    }

    fun pendingUrl(): String? = prefs.getString(PREF_PENDING_URL, null)
    fun pendingName(): String? = prefs.getString(PREF_PENDING_NAME, null)
    fun clearPending() {
        prefs.edit().remove(PREF_PENDING_URL).remove(PREF_PENDING_NAME).apply()
    }

    /** Разрешение появилось (после возврата из настроек) — сбросить флаг эпизода. */
    fun resetSettingsShown() {
        prefs.edit().putBoolean(PREF_SETTINGS_SHOWN, false).apply()
    }

    /**
     * Прикрепить receiver завершения загрузки + проверить зависшую
     * загрузку с прошлого запуска (приложение могли убить mid-download).
     * onStatus — текст статуса на экране (вызывается на main-потоке).
     */
    fun attach(onStatus: (String) -> Unit) {
        statusCallback = onStatus
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                handleDownloadFinished(id)
            }
        }
        receiver = r
        // v0.9.3.1 (ХОТФИКС краша при запуске на Android 13+, API 33+):
        // двухаргументный registerReceiver(receiver, filter) с targetSdk 33+
        // бросает SecurityException — обязателен флаг RECEIVER_EXPORTED или
        // RECEIVER_NOT_EXPORTED. Здесь NOT_EXPORTED: приёмник внутренний,
        // ловит только системный ACTION_DOWNLOAD_COMPLETE от DownloadManager,
        // чужие broadcast'ы ему не нужны. Трёхаргументный overload есть с
        // API 26 (= minSdk), а константа инлайнится при компиляции —
        // ветвление по SDK_INT не нужно (тот же приём, что в androidx
        // ContextCompat.registerReceiver).
        activity.registerReceiver(r, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        // Зависшая загрузка с прошлого запуска: DownloadManager живёт
        // сам, проверяем её статус сразу.
        val storedId = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (storedId != -1L) handleDownloadFinished(storedId)
        else cleanupStaleApk(activity)
    }

    fun detach() {
        receiver?.let { runCatching { activity.unregisterReceiver(it) } }
        receiver = null
        statusCallback = null
    }

    /**
     * Скачать APK через DownloadManager. Прогресс — в системном
     * уведомлении DownloadManager. Возвращает false, если запрос
     * не принят (невалидный URL и т.п.).
     */
    fun startDownload(url: String, versionName: String, onStatus: (String) -> Unit): Boolean {
        cleanupStaleApk(activity)
        return try {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("VLC Cast $versionName")
                setDescription("Скачивание обновления…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, UPDATE_APK_NAME)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)
            prefs.edit().putLong(PREF_DOWNLOAD_ID, id).apply()
            statusCallback = onStatus
            Log.add("update", "скачивание начато: $versionName (id=$id)")
            onStatus("Скачиваю $versionName… Прогресс — в уведомлении.")
            true
        } catch (e: Exception) {
            Log.add("update", "enqueue скачивания error: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Итог загрузки по id: успех — промпт установки; провал — статус + чистка. */
    private fun handleDownloadFinished(id: Long) {
        val storedId = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (id != storedId) return
        val dm = try {
            activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        } catch (e: Exception) {
            return
        }
        val status: Int
        try {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) {
                    // Загрузки с таким id больше нет (система её убрала) —
                    // файл тоже не ждём, чистим и забываем.
                    prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
                    cleanupStaleApk(activity)
                    return
                }
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        } catch (e: Exception) {
            Log.add("update", "query загрузки error: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
                val file = updateFile(activity)
                if (!file.exists() || file.length() == 0L) {
                    Log.add("update", "загрузка успешна, но файл пуст/отсутствует: ${file.absolutePath}")
                    statusCallback?.invoke("Скачивание завершено, но файл повреждён. Попробуй ещё раз.")
                    cleanupStaleApk(activity)
                    return
                }
                Log.add("update", "скачано: ${file.name}, ${file.length()} Б — промпт установки")
                statusCallback?.invoke("Готово — открывается установка…")
                promptInstall(file)
            }
            DownloadManager.STATUS_FAILED -> {
                prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
                Log.add("update", "скачивание провалено (DownloadManager STATUS_FAILED)")
                statusCallback?.invoke("Не удалось скачать обновление. Проверь сеть и попробуй ещё раз.")
                cleanupStaleApk(activity)
            }
            else -> {
                // RUNNING / PENDING / PAUSED — ничего не делаем, ждём receiver.
                Log.add("update", "загрузка ещё идёт (статус $status)")
            }
        }
    }

    /**
     * Промпт установки скачанного APK. URI — через LogFileProvider
     * (read-only, только этот файл). Возвращает false, если промпт
     * показать не удалось (нет установщика и т.п.).
     */
    fun promptInstall(file: File): Boolean {
        return try {
            val uri = LogFileProvider.uriForUpdateApk(activity)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(i)
            Log.add("update", "промпт установки показан: ${file.name}")
            true
        } catch (e: Exception) {
            Log.add("update", "промпт установки error: ${e.javaClass.simpleName}: ${e.message}")
            statusCallback?.invoke("Не удалось открыть установку. APK — в папке приложения, попробуй ещё раз.")
            false
        }
    }
}
