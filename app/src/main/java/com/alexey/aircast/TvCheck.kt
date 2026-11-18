package com.alexey.aircast

import java.net.InetSocketAddress
import java.net.Socket

/**
 * v0.9.1 — проверка ТВ заранее: перед 30-секундным таймаутом
 * детекта быстро проверить, доступен ли Apple TV (TCP на
 * порт Companion 7000). Если недоступен — честное уведомление
 * сразу, а не молчаливый таймаут.
 *
 * Pure Kotlin, no Android imports: тестируется на JVM
 * (локальный ServerSocket).
 */
object TvCheck {
    /** TCP-подключение к Apple TV (Companion, порт 7000). */
    fun probe(host: String, port: Int = 7000, timeoutMs: Int = 2000): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
