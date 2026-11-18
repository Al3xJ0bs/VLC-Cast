package com.alexey.aircast

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * v0.9.0 — минимальный ContentProvider для шаринга aircast.log.
 * В проекте нет AndroidX и нет Gradle (сборка aapt2 + kotlinc),
 * поэтому androidx FileProvider недоступен — свой провайдер на
 * голом ContentProvider. Отдаёт ТОЛЬКО два своих файла, read-only:
 * filesDir/aircast.log и APK обновления (external files/Download/
 * VLC-Cast-update.apk, v0.9.3 — самообновление внутри приложения).
 * v0.9.3.2: файл бэкапа спаривания убран вместе с PairingBackup.
 * Любой другой путь — FileNotFoundException.
 * exported=false, grantUriPermissions=true (доступ — только через
 * URI из intent).
 *
 * Authority: com.alexey.aircast.logfile (см. AndroidManifest.xml).
 */
class LogFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.alexey.aircast.logfile"
        const val LOG_NAME = "aircast.log"
        /** v0.9.3: имя APK обновления (AppUpdater.UPDATE_APK_NAME). */
        const val UPDATE_NAME = "VLC-Cast-update.apk"

        fun uriForFile(ctx: Context, file: File): Uri =
            Uri.parse("content://$AUTHORITY/$LOG_NAME")

        fun logFile(ctx: Context): File = File(ctx.filesDir, LOG_NAME)

        /** v0.9.3: URI для промпта установки скачанного APK. */
        fun uriForUpdateApk(ctx: Context): Uri =
            Uri.parse("content://$AUTHORITY/$UPDATE_NAME")

        /** v0.9.3: файл APK обновления (external files/Download). */
        fun updateFile(ctx: Context): File =
            File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), UPDATE_NAME)
    }

    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): File {
        if (uri.authority != AUTHORITY) throw FileNotFoundException("unknown authority: ${uri.authority}")
        val file = when (uri.lastPathSegment) {
            LOG_NAME -> logFile(context!!)
            UPDATE_NAME -> updateFile(context!!)
            else -> throw FileNotFoundException("unknown file: ${uri.lastPathSegment}")
        }
        if (!file.exists()) throw FileNotFoundException("file not found: ${uri.lastPathSegment}")
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = resolve(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = resolve(uri)
        val name = uri.lastPathSegment ?: LOG_NAME
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = Array<Any?>(cols.size) { null }
        for (i in cols.indices) {
            row[i] = when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment) {
        UPDATE_NAME -> "application/vnd.android.package-archive"
        else -> "text/plain"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")
}
