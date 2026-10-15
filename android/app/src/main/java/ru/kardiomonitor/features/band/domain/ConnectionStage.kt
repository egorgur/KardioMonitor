package ru.kardiomonitor.features.band.domain

/** Стадия установки сеанса связи с браслетом. */
enum class ConnectionStage {
    Disconnected,
    Searching,
    Connecting,
    Authenticating,
    Connected,
}
