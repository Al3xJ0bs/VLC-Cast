package com.alexey.aircast

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v0.9.3.5 — manifest-receiver завершения загрузки обновления.
 * Живёт независимо от MainActivity: срабатывает, даже если
 * приложение убито mid-download. exported=true + intent-filter
 * ACTION_DOWNLOAD_COMPLETE: это системный broadcast от
 * DownloadManager (другой UID) — NOT_EXPORTED его не получит
 * (тот же урок, что и у activity-receiver в 0.9.3.1→0.9.3.5).
 * Безопасность: id сверяется с сохранённым id загрузки, а статус —
 * через запрос к DownloadManager; чужой broadcast ничего не сделает.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        try {
            Log.init(context.filesDir)
        } catch (ignored: Exception) {
        }
        Log.add("update", "manifest-receiver: DOWNLOAD_COMPLETE id=$id")
        try {
            UpdateInstall.handleDownloadFinished(context.applicationContext, id, null)
        } catch (e: Exception) {
            Log.add("update", "manifest-receiver error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
