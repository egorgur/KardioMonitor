package ru.kardiomonitor.features.band.api

import ru.kardiomonitor.features.band.domain.ConnectionProblem
import java.io.IOException

/** Сеанс связи не открылся: браслет недоступен по причине [problem]; [message] — текст для пользователя. */
class SessionUnavailableException(val problem: ConnectionProblem, message: String) : IOException(message)
