package ru.kardiomonitor.features.band.domain

/** Несекретный идентификатор привязки для отложенной работы; ключ в WorkManager не передаётся. */
val Band.backgroundWorkIdentity: String
    get() = "${credentials.protocol.wireName}:${credentials.address.value}:${pairedAt?.toEpochMilli() ?: "legacy"}"
