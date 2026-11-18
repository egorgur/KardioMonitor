package ru.kardiomonitor.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import ru.kardiomonitor.platform.ui.SectionCard
import ru.kardiomonitor.features.alerts.list.PatientAlertSection
import ru.kardiomonitor.features.band.connection.ConnectionSection
import ru.kardiomonitor.features.band.measurementinterval.MeasurementIntervalSection
import ru.kardiomonitor.features.band.pairing.ChangeBandButton
import ru.kardiomonitor.features.band.pairing.CompanionAssociationSection
import ru.kardiomonitor.features.band.statusrefresh.BandStatusSection
import ru.kardiomonitor.features.clinic.minuteupload.MinuteUploadSection
import ru.kardiomonitor.features.clinic.registration.ServerRegistrationSection
import ru.kardiomonitor.features.monitoring.dailysummary.DailySummarySection
import ru.kardiomonitor.features.monitoring.historysync.HistorySyncSection
import ru.kardiomonitor.features.monitoring.liveheartrate.LiveHeartRateSection
import ru.kardiomonitor.features.monitoring.stepstoday.StepsTodaySection
import ru.kardiomonitor.features.rehab.progress.RehabDayProgressSection
import ru.kardiomonitor.features.rehab.progress.RehabProgressSection
import ru.kardiomonitor.features.reminders.settings.ReminderSettingsSection
import java.time.ZoneId

/** Главный экран — раскладка секций слайсов. Сценарии экран не знает: каждый — в своей секции. */
@Composable
fun DashboardScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val connection = container.bandConnection
    val source = container.activitySource
    val band by container.bandRepository.band.collectAsStateWithLifecycle()
    val canMonitor = band?.credentials?.protocol?.canMonitorActivity != false
    val rehabPlan by container.rehabPlans.plan.collectAsStateWithLifecycle()
    val patientTimeZone = rehabPlan?.timeZone ?: ZoneId.systemDefault()
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionSection(connection, container.bandRepository) { StepsTodaySection(connection, source) }
        CompanionAssociationSection(container.companionBandAssociation, container.bandRepository)
        if (!canMonitor) {
            SectionCard("Мониторинг Band 9 пока недоступен") {
                Text("История пульса и шагов, суточные шаги, безопасная остановка live HR и фоновый мониторинг ещё не подтверждены.")
                Text("Неполные данные не отправляются в клинику. Сохранённые данные не удалены.")
            }
        }
        if (!canMonitor) BandStatusSection(container.syncSettings)
        if (canMonitor) HistorySyncSection(
            connection,
            container.bandPairing,
            source,
            container.activityHistory,
            container.syncSettings,
            container.historySyncEvents,
        )
        if (canMonitor) LiveHeartRateSection(connection, container.liveHeartRateMeasurement)
        MeasurementIntervalSection(container.bandRepository, connection)
        if (canMonitor) RehabProgressSection(
            container.serverRegistrations,
            container.rehabPlans,
            container.activityHistory,
            container.rehabPlanSynchronizer::refreshNow,
        )
        PatientAlertSection(container.patientAlerts, container.patientAlertSynchronizer)
        if (canMonitor) ReminderSettingsSection(container.reminders)
        if (canMonitor) DailySummarySection(
            history = container.activityHistory,
            timeZone = patientTimeZone,
            dayContent = { day ->
                RehabDayProgressSection(
                    day = day,
                    plans = container.rehabPlans,
                    history = container.activityHistory,
                )
            },
        )
        ServerRegistrationSection(container.serverRegistrations, container.clinicServer)
        MinuteUploadSection(container.minuteUploader)
        ChangeBandButton(container.bandRepository, connection)
    }
}
