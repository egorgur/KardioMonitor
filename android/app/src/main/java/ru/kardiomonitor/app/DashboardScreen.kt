package ru.kardiomonitor.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.kardiomonitor.features.band.connection.ConnectionSection
import ru.kardiomonitor.features.band.measurementinterval.MeasurementIntervalSection
import ru.kardiomonitor.features.band.pairing.ChangeBandButton
import ru.kardiomonitor.features.clinic.minuteupload.MinuteUploadSection
import ru.kardiomonitor.features.clinic.registration.ServerRegistrationSection
import ru.kardiomonitor.features.monitoring.dailysummary.DailySummarySection
import ru.kardiomonitor.features.monitoring.historysync.HistorySyncSection
import ru.kardiomonitor.features.monitoring.liveheartrate.LiveHeartRateSection
import ru.kardiomonitor.features.monitoring.stepstoday.StepsTodaySection

/** Главный экран — раскладка секций слайсов. Сценарии экран не знает: каждый — в своей секции. */
@Composable
fun DashboardScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val connection = container.bandConnection
    val source = container.activitySource
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionSection(connection, container.bandRepository) { StepsTodaySection(connection, source) }
        HistorySyncSection(connection, source, container.activityHistory, container.syncSettings)
        LiveHeartRateSection(connection, source, container.liveHeartRateFeed)
        MeasurementIntervalSection(container.bandRepository, connection)
        DailySummarySection(container.activityHistory)
        ServerRegistrationSection(container.serverRegistrations, container.clinicServer)
        MinuteUploadSection(container.minuteUploader)
        ChangeBandButton(container.bandRepository, connection)
    }
}
