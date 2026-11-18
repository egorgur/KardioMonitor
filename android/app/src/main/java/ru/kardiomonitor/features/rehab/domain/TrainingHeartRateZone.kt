package ru.kardiomonitor.features.rehab.domain

/** Индивидуальная зона тренировочного пульса; обе границы включены. */
data class TrainingHeartRateZone(val lowerBpm: Int, val upperBpm: Int) {
    init {
        require(lowerBpm in MIN_BPM until upperBpm) { "нижняя граница зоны должна быть меньше верхней" }
        require(upperBpm <= MAX_BPM) { "зона пульса должна быть в пределах $MIN_BPM–$MAX_BPM" }
    }

    fun contains(heartRateBpm: Int): Boolean = heartRateBpm in lowerBpm..upperBpm

    private companion object {
        const val MIN_BPM = 40
        const val MAX_BPM = 220
    }
}
