package ru.kardiomonitor.platform.huami

import java.io.IOException

/** Браслет отклонил авторизацию: неверный ключ или неподдерживаемый вариант протокола. */
class AuthException(message: String) : IOException(message)
