package ru.kardiomonitor.features.band.domain

/** Прочитанные поля не подменяются protobuf-defaults; interval=0 у Xiaomi означает Smart. */
data class ReportedHeartRateSettings(val disabled: Boolean?, val intervalMinutes: Int?) {
    init {
        require(intervalMinutes == null || intervalMinutes >= 0)
    }
}
