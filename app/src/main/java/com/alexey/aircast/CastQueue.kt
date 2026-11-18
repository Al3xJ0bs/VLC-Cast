package com.alexey.aircast

/**
 * v0.9.1 — очередь шарингов: несколько ссылок подряд → играют
 * последовательно (а не заменяют друг друга, как раньше).
 *
 * Элемент очереди — ссылка на страницу/видео или видеофайл
 * (content:// URI из Telegram/галереи). Порядок — FIFO, как
 * поделился. Очередь живёт только пока жив сервис (в памяти);
 * после перезапуска приложения она пуста — честно, без
 * обещания персистентности.
 *
 * Pure Kotlin, no Android imports: тестируется на JVM.
 */
sealed class QueueItem {
    /** Ссылка на страницу с видео или прямой видеопоток. */
    data class Url(val url: String) : QueueItem()

    /** Видеофайл из Telegram/галереи (content:// URI + имя для показа). */
    data class File(val uri: String, val displayName: String?, val isAudio: Boolean = false) : QueueItem()
}

class CastQueue {
    private val items = ArrayList<QueueItem>()

    /** Добавить в конец очереди. Возвращает размер после добавления. */
    fun add(item: QueueItem): Int {
        items.add(item)
        return items.size
    }

    fun addAll(newItems: List<QueueItem>): Int {
        items.addAll(newItems)
        return items.size
    }

    /** Взять следующий элемент (или null — очередь пуста). */
    fun poll(): QueueItem? {
        if (items.isEmpty()) return null
        return items.removeAt(0)
    }

    fun peek(): QueueItem? = items.firstOrNull()

    fun clear() = items.clear()

    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    fun snapshot(): List<QueueItem> = items.toList()
}
