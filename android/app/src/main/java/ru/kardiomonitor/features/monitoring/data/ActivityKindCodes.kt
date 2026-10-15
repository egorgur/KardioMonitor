package ru.kardiomonitor.features.monitoring.data

import ru.kardiomonitor.features.monitoring.domain.ActivityKind

/**
 * Формат хранения типа активности в базе: код браслета, как его записывал прототип
 * (docs/ble-protocol.md, «История активности»). Это формат базы, а не протокол: менять
 * его нельзя, пока на телефоне пациента лежит история прототипа.
 */
internal object ActivityKindCodes {
    private const val NOT_WORN = 115
    private const val CHARGING = 118
    private const val SLEEP = 120

    fun code(kind: ActivityKind): Int = when (kind) {
        ActivityKind.NotWorn -> NOT_WORN
        ActivityKind.Charging -> CHARGING
        ActivityKind.Sleep -> SLEEP
        is ActivityKind.Other -> kind.code
    }

    fun kind(code: Int): ActivityKind = when (code) {
        NOT_WORN -> ActivityKind.NotWorn
        CHARGING -> ActivityKind.Charging
        SLEEP -> ActivityKind.Sleep
        else -> ActivityKind.Other(code)
    }
}
