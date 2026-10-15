package ru.kardiomonitor.features.clinic.domain

import java.time.Instant

/** Ответ сервера на пакет минут: сколько принято, сколько новых, последняя принятая минута. */
data class UploadReceipt(val received: Int, val new: Int, val lastMinute: Instant)
