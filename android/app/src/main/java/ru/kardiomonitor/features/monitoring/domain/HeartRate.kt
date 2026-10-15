package ru.kardiomonitor.features.monitoring.domain

/**
 * Пульс, уд/мин. Допустимо 11–249: значение вне диапазона — не замер, а отметка браслета
 * «замера нет» (см. [fromMeasurement]).
 */
@JvmInline
value class HeartRate(val bpm: Int) {
    init {
        require(bpm in VALID_BPM) { "пульс вне диапазона $VALID_BPM: $bpm" }
    }

    companion object {
        private val VALID_BPM = 11..249

        /** Значение от браслета: 0 — замера не было, 255 — замер не удался; оба дают null. */
        fun fromMeasurement(bpm: Int): HeartRate? = if (bpm in VALID_BPM) HeartRate(bpm) else null
    }
}
