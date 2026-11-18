package ru.kardiomonitor.bandprotocol.xiaomi

/** Фиксированные безопасные этапы открытия; не содержат данных устройства или пакетов. */
enum class XiaomiOpenPhase {
    SOCKET_CONNECT, VERSION, SESSION, AUTH_CHALLENGE, WATCH_PROOF, AUTH_CONFIRM, DEVICE_INFO, IDENTITY_GUARD,
}
