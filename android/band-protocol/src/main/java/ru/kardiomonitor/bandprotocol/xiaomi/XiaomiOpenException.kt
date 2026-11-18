package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException

/** Только фиксированный этап; исходное исключение, адрес и протокольные данные не сохраняются. */
class XiaomiOpenException internal constructor(val phase: XiaomiOpenPhase) : IOException("Не удалось открыть сеанс Xiaomi")
