package ru.kardiomonitor.features.band.connection

import ru.kardiomonitor.features.band.domain.ConnectionProblem

/** Текст причины для пользователя. */
internal fun ConnectionProblem.text(): String = when (this) {
    ConnectionProblem.NotPaired -> "Браслет не привязан"
    ConnectionProblem.NoBluetooth -> "На телефоне нет Bluetooth"
    ConnectionProblem.BluetoothOff -> "Bluetooth выключен"
    ConnectionProblem.NoPermission -> "Нет разрешения на Bluetooth"
    ConnectionProblem.NotFound -> "Браслет не найден. Он рядом? Mi Fitness не держит соединение?"
    is ConnectionProblem.AuthRejected -> "Ошибка авторизации: $reason. Проверьте ключ."
    is ConnectionProblem.LinkFailed -> reason
    ConnectionProblem.Lost -> "Соединение с браслетом потеряно"
    is ConnectionProblem.SettingsNotApplied -> "Не удалось задать частоту пульса: $reason"
}
