package com.alexey.aircast

/**
 * v0.9.3 (#27): бэкап спаривания — экспорт Companion-учёток (HAP +
 * companion) в файл и импорт обратно (восстановление после
 * переустановки без повторного PIN).
 *
 * Формат файла (текст, v1):
 *   # VLC Cast pairing backup v1
 *   <identity>\t<serialize()>
 *   ...
 * identity — "companion:<host>" (Companion) или "<host>" (HAP,
 * старые версии); serialize() — PLAINTEXT HapCredentials
 * (clientId:clientSecret:serverId:serverPublic в hex).
 * KeyStore-шифр из CredentialStore для бэкапа не годится: ключ
 * KeyStore умирает вместе с переустановкой, и шифр после неё —
 * мусор. Поэтому экспортируется PLAINTEXT serialize().
 *
 * Файл содержит секреты спаривания — UI и README говорят хранить
 * его в надёжном месте и удалить после восстановления.
 * Чистая логика (export/parse) — JVM-тесты; запись/чтение файла,
 * выбор identity из prefs — в MainActivity.
 */
object PairingBackup {
    const val HEADER = "# VLC Cast pairing backup v1"
    const val BACKUP_NAME = "aircast-pairing-backup.txt"

    fun exportEntry(identity: String, serialized: String): String = "$identity\t$serialized"

    fun exportAll(entries: List<Pair<String, String>>): String {
        val sb = StringBuilder(HEADER).append('\n')
        for ((identity, serialized) in entries) sb.append(exportEntry(identity, serialized)).append('\n')
        return sb.toString()
    }

    data class ParsedEntry(val identity: String, val creds: HapCredentials)
    data class ParseResult(val entries: List<ParsedEntry>, val skipped: Int)

    /**
     * Разобрать файл бэкапа. Валидна запись: непустой identity +
     * HapCredentials.parse не null. Мусор (шапка, пустые строки,
     * битые строки) пропускается и считается в skipped.
     * Никогда не бросает.
     */
    fun parse(text: String?): ParseResult {
        val entries = ArrayList<ParsedEntry>()
        var skipped = 0
        if (text.isNullOrBlank()) return ParseResult(entries, 0)
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) {
                skipped++
                continue
            }
            val identity = line.substring(0, tab).trim()
            val serialized = line.substring(tab + 1).trim()
            val creds = HapCredentials.parse(serialized)
            if (identity.isEmpty() || creds == null) {
                skipped++
                continue
            }
            entries.add(ParsedEntry(identity, creds))
        }
        return ParseResult(entries, skipped)
    }
}
