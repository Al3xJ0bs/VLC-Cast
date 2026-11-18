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
 * Новый порядок:
 * 1. FILE_WAIT — сервис стартует СИНХРОННО в onCreate activity,
 *    ДО копии; сразу показывает «Копирую файл…» и ждёт путь.
 * 2. FILE_DELIVER — activity отдаёт путь к копии уже работающему
 *    (foreground) сервису вторым интентом; ограничение на старт
 *    из фона не действует.
 * 3. Старые пути сохранены: ACTION_CAST + EXTRA_FILE_PATH (0.9.2)
 *    и ACTION_CAST + data URI (0.9.1, fallback при ошибке копии).
 */
object FileShareRoute {
    const val ACTION_FILE_WAIT = "com.alexey.aircast.action.FILE_WAIT"
    const val ACTION_FILE_DELIVER = "com.alexey.aircast.action.FILE_DELIVER"

    enum class Route { WAIT, DELIVER_PATH, FILE_URI, URL, NONE }

    fun resolve(action: String?, filePath: String?, dataUri: String?, url: String?): Route {
        if (action == ACTION_FILE_WAIT) return Route.WAIT
        if (action == ACTION_FILE_DELIVER && !filePath.isNullOrBlank()) return Route.DELIVER_PATH
        if (!filePath.isNullOrBlank()) return Route.DELIVER_PATH
        if (!dataUri.isNullOrBlank()) return Route.FILE_URI
        if (!url.isNullOrBlank()) return Route.URL
        return Route.NONE
    }
}
