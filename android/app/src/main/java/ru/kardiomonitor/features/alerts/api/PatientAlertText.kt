package ru.kardiomonitor.features.alerts.api

import ru.kardiomonitor.features.alerts.domain.PatientAlert
import java.time.format.DateTimeFormatter

/** Черновые нейтральные тексты пациенту; врач должен утвердить их до выпуска (В-19). */
object PatientAlertText {
    fun title(alert: PatientAlert): String = when (alert.kind) {
        PatientAlert.Kind.HighHeartRate -> "Пульс выше заданного порога"
        PatientAlert.Kind.DailyTargetMissed -> "Дневная норма пока не выполнена"
        PatientAlert.Kind.DailyTargetMet -> "Дневная норма выполнена"
        PatientAlert.Kind.LowActivity -> "За день мало шагов"
        PatientAlert.Kind.TrainingMinutesExceeded -> "Превышен порог тренировочных минут"
    }

    fun message(alert: PatientAlert): String = when (val details = alert.details) {
        is PatientAlert.Details.HighHeartRate ->
            "До ${details.maxHeartRate} уд/мин при пороге ${details.thresholdBpm}, ${details.durationMinutes} мин"
        is PatientAlert.Details.DailyTarget ->
            "${details.trainingMinutes} из ${details.targetTrainingMinutes} мин, " +
                "${details.steps} из ${details.targetSteps} шагов"
        is PatientAlert.Details.LowActivity ->
            "${details.steps} шагов при заданном пороге ${details.thresholdSteps}"
        is PatientAlert.Details.TrainingMinutesExceeded ->
            "За ${requireNotNull(alert.day).format(ALERT_DAY)}: ${details.trainingMinutes} мин тренировки " +
                "при заданном пороге ${details.thresholdMinutes} мин"
    }

    private val ALERT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
}
