package com.alexey.aircast

/**
 * v0.9.3 — гейт самообновления внутри приложения: разрешение на
 * установку из неизвестных источников.
 *
 * Чистая логика без Android (JVM-тесты): на входе — результат
 * packageManager.canRequestPackageInstalls() и флаг «уже отправляли
 * пользователя в настройки в этом эпизоде» (prefs
 * update_install_settings_shown).
 *
 * PROCEED — разрешение есть, можно качать APK.
 * OPEN_SETTINGS — разрешения нет, и в настройки ещё не отправляли:
 * один раз открыть Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
 * (package URI) с коротким объяснением.
 * DENIED — разрешения нет и в настройки уже отправляли: не гонять
 * пользователя по кругу — честный текст, пусть разрешит сам и
 * нажмёт «Скачать и установить» ещё раз. Флаг сбрасывается, когда
 * разрешение появляется (следующий эпизод — снова один раз).
 */
object UpdateGate {
    enum class Decision { PROCEED, OPEN_SETTINGS, DENIED }

    fun decide(canRequest: Boolean, settingsShown: Boolean): Decision = when {
        canRequest -> Decision.PROCEED
        !settingsShown -> Decision.OPEN_SETTINGS
        else -> Decision.DENIED
    }
}
