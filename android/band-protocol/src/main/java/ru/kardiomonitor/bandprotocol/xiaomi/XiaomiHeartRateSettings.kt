package ru.kardiomonitor.bandprotocol.xiaomi

/** Прочитанные настройки: null означает отсутствие поля, intervalMinutes=0 — Smart, не Off.
 * Список поддержанных интервалов неизвестен; запись настроек намеренно отсутствует.
 */
data class XiaomiHeartRateSettings(val disabled: Boolean?, val intervalMinutes: Int?)
