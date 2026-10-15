package ru.kardiomonitor.features.clinic.domain

import kotlinx.coroutines.flow.StateFlow

/** Регистрация телефона на сервере. null — не зарегистрирован, данные никуда не отправляются. */
interface ServerRegistrationRepository {
    val registration: StateFlow<ServerRegistration?>

    fun save(registration: ServerRegistration)

    fun forget()
}
