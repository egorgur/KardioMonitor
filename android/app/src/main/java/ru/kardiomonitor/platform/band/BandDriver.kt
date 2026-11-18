package ru.kardiomonitor.platform.band

import ru.kardiomonitor.features.band.domain.BandLink
import ru.kardiomonitor.features.monitoring.domain.ActivitySource

/** Оба порта обслуживаются одним владельцем физического соединения. */
interface BandDriver : BandLink, ActivitySource
