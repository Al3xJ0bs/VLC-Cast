package com.alexey.aircast

/**
 * v0.9.1 — master-first для Twitch/Kick (и любых HLS):
 * не запускать первый пришедший вариант, а подождать
 * мастер-плейлист и отдать во VLC его — VLC сам берёт
 * максимум (см. RESEARCH: Twitch/Kick брали низкий вариант,
 * потому что debounce на 2 с срабатывал раньше, чем приходил
 * мастер, а isMasterPlaylist по URL не видел Twitch-мастер
 * без маркера "master" в пути).
 *
 * Ждём только пока: мастера нет, есть хотя бы один HLS-вариант
 * и прошло меньше 8 с с первого кандидата. После 8 с —
 * запускаем лучшее из того, что есть (как раньше), а не висим.
 * Если мастер пришёл — запуск сразу, без дополнительного
 * ожидания. content-based проверка (StreamProbe.isHlsMaster)
 * помечает варианты как мастера даже без маркера в URL.
 *
 * Pure Kotlin, no Android imports: тестируется на JVM.
 */
object MasterWait {
    /** Максимальное время ожидания мастера с первого кандидата. */
    const val MAX_WAIT_MS = 8_000L

    /** Задержка debounce, пока ждём мастер (vs обычная 2 с). */
    const val WAIT_DELAY_MS = 4_000L

    /** Обычная задержка debounce, когда мастера ждать не нужно. */
    const val NORMAL_DELAY_MS = 2_000L

    /** Максимум переносов debounce (было 3 — поднято, чтобы дождаться мастера). */
    const val MAX_RESCHEDULES = 6

    /**
     * Ждать ли дальше мастера: мастера нет, есть HLS-вариант
     * и не вышло время.
     */
    fun shouldKeepWaiting(hasMaster: Boolean, hasHlsVariant: Boolean, elapsedMs: Long): Boolean {
        return !hasMaster && hasHlsVariant && elapsedMs < MAX_WAIT_MS
    }

    /** Задержка debounce в зависимости от того, ждём ли мастера. */
    fun waitDelayMs(waitingForMaster: Boolean): Long =
        if (waitingForMaster) WAIT_DELAY_MS else NORMAL_DELAY_MS
}
