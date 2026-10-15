package ru.kardiomonitor.features.band.domain

import kotlinx.coroutines.flow.StateFlow

/** Хранилище привязанного браслета. Один браслет на приложение. */
interface BandRepository {
    /** Привязанный браслет; null — браслет не привязан. */
    val band: StateFlow<Band?>

    fun save(band: Band)

    /** Отвязать браслет: учётные данные и настройки удаляются. */
    fun forget()
}
