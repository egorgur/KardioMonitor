package ru.kardiomonitor.features.band.domain

/** Почему сеанса связи с браслетом нет или он работает не полностью. */
sealed interface ConnectionProblem {
    data object NotPaired : ConnectionProblem
    data object NoBluetooth : ConnectionProblem
    data object BluetoothOff : ConnectionProblem
    data object NoPermission : ConnectionProblem

    /** Браслет не найден поиском: далеко или соединение держит другое приложение (Mi Fitness). */
    data object NotFound : ConnectionProblem

    /** Браслет отклонил авторизацию: неверный ключ или неподдерживаемый протокол. */
    data class AuthRejected(val reason: String) : ConnectionProblem

    /** Сбой связи во время подключения. */
    data class LinkFailed(val reason: String) : ConnectionProblem

    /** Установленное соединение оборвалось. */
    data object Lost : ConnectionProblem

    /** Соединение есть, но настройку браслета записать не удалось. */
    data class SettingsNotApplied(val reason: String) : ConnectionProblem
}
