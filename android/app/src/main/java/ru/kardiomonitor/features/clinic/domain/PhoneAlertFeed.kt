package ru.kardiomonitor.features.clinic.domain

/** Страница новых или изменённых оповещений после курсора телефона. */
data class PhoneAlertFeed(
    val alerts: List<PhoneAlertReport>,
    val cursor: Long,
    val hasMore: Boolean,
) {
    init {
        require(cursor >= 0) { "курсор оповещений не может быть отрицательным" }
    }
}
