package ru.kardiomonitor.features.clinic.minuteupload

import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.MinuteReport
import ru.kardiomonitor.features.clinic.domain.MinuteReport.ReportedKind
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivityKind
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute

/**
 * Отправка новых минут врачу: сервер говорит, какую минуту принял последней, телефон
 * отправляет следующие пакетами по суткам. Повтор безопасен — сервер принимает идемпотентно.
 * Сбои связи — `java.io.IOException`: следующая попытка продолжит с того же места.
 */
internal class UploadNewMinutes(
    private val server: ClinicServer,
    private val history: ActivityHistory,
) {
    /** Сколько минут отправлено. */
    suspend operator fun invoke(registration: ServerRegistration): Int {
        var after = server.lastUploadedMinute(registration)
        var sent = 0
        while (true) {
            val minutes = history.minutesAfter(after, BATCH_MINUTES)
            if (minutes.isEmpty()) return sent
            server.uploadMinutes(registration, minutes.map(::reportOf))
            sent += minutes.size
            after = minutes.last().start
        }
    }

    companion object {
        /** Сутки — пакет заметно меньше лимита сервера (7 суток) и быстро уходит по мобильной сети. */
        const val BATCH_MINUTES = 24 * 60
    }
}

/** Перевод на язык сервера: код браслета уходит только для нерасшифрованных типов. */
internal fun reportOf(minute: ActivityMinute): MinuteReport {
    val (kind, code) = when (val activity = minute.kind) {
        ActivityKind.NotWorn -> ReportedKind.NotWorn to null
        ActivityKind.Charging -> ReportedKind.Charging to null
        ActivityKind.Sleep -> ReportedKind.Sleep to null
        is ActivityKind.Other -> ReportedKind.Other to activity.code
    }
    return MinuteReport(
        start = minute.start,
        steps = minute.steps.value,
        heartRateBpm = minute.heartRate?.bpm,
        kind = kind,
        kindCode = code,
        intensity = minute.intensity.value,
    )
}
