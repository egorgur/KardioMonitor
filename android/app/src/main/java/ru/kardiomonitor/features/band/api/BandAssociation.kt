package ru.kardiomonitor.features.band.api

import ru.kardiomonitor.features.band.domain.BandAddress

/** Системное разрешение Companion Device Manager на фоновую работу с браслетом. */
fun interface BandAssociation {
    fun isAssociated(address: BandAddress): Boolean
}
