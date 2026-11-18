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
        // RECEIVER_NOT_EXPORTED. Трёхаргументный overload есть с API 26
        // (= minSdk), а константа инлайнится при компиляции — ветвление
        // по SDK_INT не нужно (тот же приём, что в androidx
        // ContextCompat.registerReceiver).
        // v0.9.3.5 (БАГ самообновления: загрузка шла, а установка не
        // начиналась): флаг здесь обязан быть RECEIVER_EXPORTED.
        // В 0.9.3.1 поставили NOT_EXPORTED — краш ушёл, но receiver
        // молча перестал получать системный ACTION_DOWNLOAD_COMPLETE
        // (DownloadManager — другой UID; по документации Android
        // системный broadcast требует EXPORTED). Чужой broadcast
        // безвреден: id сверяется с сохранённым, статус — через
        // запрос к DownloadManager (см. UpdateInstall).
        activity.registerReceiver(r, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        // Зависшая загрузка с прошлого запуска: DownloadManager живёт
        // сам, проверяем её статус сразу. Скачанный APK, ждущий
        // разрешения на установку, — тоже (файл уже на месте, его
        // нельзя чистить как stale).
        val storedId = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (storedId != -1L) handleDownloadFinished(storedId)
        else if (prefs.getBoolean(UpdateInstall.PREF_PENDING_INSTALL, false)) handlePendingInstall()
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
        prefs.edit().remove(UpdateInstall.PREF_PENDING_INSTALL).apply()
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

    /** Итог загрузки по id — делегирование в UpdateInstall (та же
     * логика у manifest-receiver UpdateDownloadReceiver). */
    private fun handleDownloadFinished(id: Long) {
        UpdateInstall.handleDownloadFinished(activity, id, statusCallback)
    }

    /**
     * v0.9.3.5: скачанный APK ждёт разрешения на установку (файл уже
     * на месте). Разрешение есть — промпт сразу; нет — один раз
     * в настройки (после возврата установка начнётся без повторного
     * скачивания, см. onActivityResult).
     */
    fun handlePendingInstall() {
        val file = UpdateInstall.updateFile(activity)
        if (!file.exists() || file.length() == 0L) {
            prefs.edit().remove(UpdateInstall.PREF_PENDING_INSTALL).apply()
            Log.add("update", "pending-install: файл пропал — флаг снят")
            cleanupStaleApk(activity)
            return
        }
        if (canRequestInstalls()) {
            prefs.edit().remove(UpdateInstall.PREF_PENDING_INSTALL).apply()
            resetSettingsShown()
            Log.add("update", "pending-install: разрешение есть — промпт установки")
            statusCallback?.invoke("Готово — открывается установка…")
            if (!UpdateInstall.promptInstall(activity, file)) {
                UpdateInstall.postInstallReadyNotification(activity, file)
            }
        } else if (gateDecision() == UpdateGate.Decision.OPEN_SETTINGS) {
            Log.add("update", "pending-install: разрешения нет — один раз в настройки")
            statusCallback?.invoke("Нужен доступ к установке из неизвестных источников — открываю настройки…")
            openInstallSettings()
        } else {
            statusCallback?.invoke("Обновление скачано. Разреши установку из неизвестных источников — установка начнётся сама.")
        }
    }

    /**
     * Промпт установки скачанного APK (делегирование в UpdateInstall:
     * URI — через LogFileProvider, read-only, только этот файл).
     * Возвращает false, если промпт показать не удалось.
     */
    fun promptInstall(file: File): Boolean {
        val ok = UpdateInstall.promptInstall(activity, file)
        if (!ok) statusCallback?.invoke("Не удалось открыть установку. APK — в папке приложения, попробуй ещё раз.")
        return ok
    }
}
