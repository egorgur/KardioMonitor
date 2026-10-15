package ru.kardiomonitor.features.monitoring.domain

/** Число шагов — неотрицательное. */
@JvmInline
value class StepCount(val value: Int) {
    init {
        require(value >= 0) { "число шагов не может быть отрицательным: $value" }
    }

    operator fun plus(other: StepCount): StepCount = StepCount(value + other.value)

    companion object {
        val ZERO: StepCount = StepCount(0)
    }
}
