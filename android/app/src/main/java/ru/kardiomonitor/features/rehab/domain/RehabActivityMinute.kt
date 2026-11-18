package ru.kardiomonitor.features.rehab.domain

/** Минимальный снимок минуты, необходимый домену реабилитации для локального расчёта. */
data class RehabActivityMinute(
    val steps: Int,
    val heartRateBpm: Int?,
    val isWorn: Boolean,
) {
    init {
        require(steps >= 0) { "число шагов не может быть отрицательным" }
    }
}
