package ru.kardiomonitor.features.monitoring.domain

/**
 * Тип активности за минуту. Коды браслета — деталь протокола: в эти варианты их переводит
 * ACL `platform.huami`, а обратно в коды — только хранилище истории (коды лежат в базе с прототипа).
 */
sealed interface ActivityKind {
    /** Надет ли браслет: снятый и заряжающийся браслет пульс и шаги не измеряет. */
    val isWorn: Boolean get() = this != NotWorn && this != Charging

    /** Браслет снят. */
    data object NotWorn : ActivityKind

    /** Браслет заряжается. */
    data object Charging : ActivityKind

    /** Сон. Данные о сне пока не расшифрованы — не использовать для выводов. */
    data object Sleep : ActivityKind

    /** Код браслета, смысл которого не расшифрован. */
    data class Other(val code: Int) : ActivityKind
}
