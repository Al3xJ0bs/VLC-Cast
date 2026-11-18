package com.alexey.aircast

/**
 * v0.9.3 (хотфикс 0.9.2 — тишина при шаринге файла из Telegram):
 * маршрутизация интентов файлового шаринга — чистая логика без
 * Android, покрыта JVM-тестами.
 *
 * Проблема 0.9.2: ShareReceiverActivity копировала файл в фоновом
 * потоке и стартовала сервис ТОЛЬКО после копии (runOnUiThread).
 * NoDisplay-activity без окна система может прибить раньше —
 * startForegroundService из фона падает (Android 12+,
 * ForegroundServiceStartNotAllowedException), и всё умирает молча.
 *
 * v0.9.3 (попытка починить): сервис стартовал синхронно в onCreate
 * activity (FILE_WAIT), копия — в фоновом потоке activity, путь —
 * вторым интентом (FILE_DELIVER). Это сломало саму activity:
 * Theme.NoDisplay ОБЯЗАНА вызвать finish() в onCreate, до конца
 * onResume, — а onCreate возвращался без finish() (finish был в
 * runOnUiThread после копии). Итог 0.9.3.1: краш при шаринге из
 * Telegram — IllegalStateException: did not call finish() prior
 * to onResume() completing.
 *
 * v0.9.3.2 (порядок теперь): activity — ТОЛЬКО форвардер: в onCreate
 * извлекает URI, синхронно стартует сервис (FILE_COPY) и НЕМЕДЛЕННО
 * вызывает finish() в том же onCreate. Никаких потоков и отложенных
 * finish() в activity. Копию файла в cacheDir/aircast-temp делает
 * САМ СЕРВИС в своём фоновом потоке (foreground, уведомление
 * «Копирую файл…»), по завершении — startFileShare по пути копии,
 * при ошибке копии — fallback на URI-путь. FILE_WAIT/FILE_DELIVER
 * удалены вместе со всей двухшаговой схемой.
 *
 * Старые пути сохранены: ACTION_CAST + EXTRA_FILE_PATH (0.9.2)
 * и ACTION_CAST + data URI (0.9.1, fallback при ошибке копии).
 */
object FileShareRoute {
    /** v0.9.3.2: activity передаёт URI сервису, копия — в сервисе. */
    const val ACTION_FILE_COPY = "com.alexey.aircast.action.FILE_COPY"

    enum class Route { COPY_FILE, DELIVER_PATH, FILE_URI, URL, NONE }

    fun resolve(action: String?, filePath: String?, dataUri: String?, url: String?): Route {
        if (action == ACTION_FILE_COPY && !dataUri.isNullOrBlank()) return Route.COPY_FILE
        if (!filePath.isNullOrBlank()) return Route.DELIVER_PATH
        if (!dataUri.isNullOrBlank()) return Route.FILE_URI
        if (!url.isNullOrBlank()) return Route.URL
        return Route.NONE
    }
}
