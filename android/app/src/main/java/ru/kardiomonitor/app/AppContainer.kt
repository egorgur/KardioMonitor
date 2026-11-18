package ru.kardiomonitor.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.kardiomonitor.features.alerts.alertsync.AndroidPatientAlertNotifier
import ru.kardiomonitor.features.alerts.alertsync.PatientAlertSynchronizer
import ru.kardiomonitor.features.alerts.data.SharedPreferencesPatientAlertRepository
import ru.kardiomonitor.features.alerts.domain.PatientAlertRepository
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.api.BandPairing
import ru.kardiomonitor.features.band.api.BandAssociation
import ru.kardiomonitor.features.band.connection.SingleBandConnection
import ru.kardiomonitor.features.band.data.SharedPreferencesBandRepository
import ru.kardiomonitor.features.band.data.SqliteBatteryLog
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.band.pairing.StoredBandPairing
import ru.kardiomonitor.features.band.pairing.CompanionBandAssociation
import ru.kardiomonitor.features.band.statusrefresh.BandStatusRefresh
import ru.kardiomonitor.features.clinic.data.HttpClinicServer
import ru.kardiomonitor.features.clinic.data.InMemoryClinicSyncEvents
import ru.kardiomonitor.features.clinic.data.SharedPreferencesServerRegistrationRepository
import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.ClinicEnrollment
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import ru.kardiomonitor.features.clinic.enrollment.StoredClinicEnrollment
import ru.kardiomonitor.features.clinic.liveheartrateshare.LiveHeartRateSharing
import ru.kardiomonitor.features.clinic.minuteupload.MinuteUploader
import ru.kardiomonitor.features.monitoring.data.InMemoryLiveHeartRateFeed
import ru.kardiomonitor.features.monitoring.data.InMemoryHistorySyncEvents
import ru.kardiomonitor.features.monitoring.data.SharedPreferencesLiveHeartRateRequestRepository
import ru.kardiomonitor.features.monitoring.data.SharedPreferencesSyncSettings
import ru.kardiomonitor.features.monitoring.data.SqliteActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateFeed
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateRequestRepository
import ru.kardiomonitor.features.monitoring.domain.SyncSettings
import ru.kardiomonitor.features.monitoring.api.ActivityHistoryRefresh
import ru.kardiomonitor.features.monitoring.historysync.BackgroundActivityHistoryRefresh
import ru.kardiomonitor.features.monitoring.liveheartrate.AndroidLiveHeartRateRequestNotification
import ru.kardiomonitor.features.monitoring.liveheartrate.LiveHeartRateMeasurement
import ru.kardiomonitor.features.monitoring.liveheartrate.LiveHeartRateRequestSynchronizer
import ru.kardiomonitor.features.monitoring.liveheartrate.RequestedLiveHeartRateRunner
import ru.kardiomonitor.features.monitoring.liveheartrate.RequestedLiveHeartRateWorker
import ru.kardiomonitor.features.rehab.data.SharedPreferencesRehabPlanRepository
import ru.kardiomonitor.features.rehab.domain.RehabPlanRepository
import ru.kardiomonitor.features.rehab.plansync.RehabPlanSynchronizer
import ru.kardiomonitor.features.reminders.api.ReminderActions
import ru.kardiomonitor.features.reminders.data.SharedPreferencesReminderRepository
import ru.kardiomonitor.features.reminders.domain.ReminderRepository
import ru.kardiomonitor.features.reminders.remindercheck.AndroidReminderNotification
import ru.kardiomonitor.features.reminders.remindercheck.ReminderActionsStore
import ru.kardiomonitor.features.reminders.remindercheck.ReminderCheck
import ru.kardiomonitor.platform.db.KardioDatabase
import ru.kardiomonitor.platform.http.JsonHttpClient
import ru.kardiomonitor.platform.huami.HuamiBand
import ru.kardiomonitor.platform.band.ProtocolBandRouter
import ru.kardiomonitor.platform.xiaomi.XiaomiBand
import ru.kardiomonitor.platform.keystore.AndroidKeystoreSecretBox

/**
 * Композиционный корень (ADR-0004): создаёт инфраструктуру и реализации портов один раз на
 * процесс и передаёт их через конструкторы в секции слайсов и Worker. Глобальных синглтонов
 * кроме него нет.
 */
class AppContainer(context: Context) {
    /** Scope приложения: живёт, пока жив процесс; сбой одной задачи не отменяет остальные. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = KardioDatabase(context)
    private val secretBox = AndroidKeystoreSecretBox("kardiomonitor.patient.secrets.v1")

    val bandRepository: BandRepository = SharedPreferencesBandRepository(context, secretBox)
    /** Один маршрутизируемый владелец обоих портов; без fallback BLE/SPP (ADR-0014). */
    private val bandDriver = ProtocolBandRouter(bandRepository, HuamiBand(context, scope), XiaomiBand(context))
    val bandConnection: BandConnection =
        SingleBandConnection(bandDriver, bandRepository, SqliteBatteryLog(database), scope)
    val bandPairing: BandPairing = StoredBandPairing(bandRepository)
    val companionBandAssociation = CompanionBandAssociation(context)
    val bandAssociation: BandAssociation = companionBandAssociation

    val activitySource: ActivitySource = bandDriver
    val activityHistory: ActivityHistory = SqliteActivityHistory(database)
    val syncSettings: SyncSettings = SharedPreferencesSyncSettings(context)
    private val backgroundBandAccess = BackgroundBandAccess(context, bandRepository, bandAssociation, syncSettings)
    val bandStatusRefresh = BandStatusRefresh(bandConnection, bandRepository, backgroundBandAccess::isAllowed)
    val liveHeartRateFeed: LiveHeartRateFeed = InMemoryLiveHeartRateFeed()
    val historySyncEvents = InMemoryHistorySyncEvents()
    val liveHeartRateMeasurement = LiveHeartRateMeasurement(
        bandConnection,
        activitySource,
        liveHeartRateFeed,
        scope,
    )
    val activityHistoryRefresh: ActivityHistoryRefresh = BackgroundActivityHistoryRefresh(
        bandConnection,
        bandPairing,
        activitySource,
        activityHistory,
        syncSettings,
        historySyncEvents,
    )

    /** Обмен с клиникой: сервер (для демо — через ngrok) и регистрация телефона на нём. */
    val clinicServer: ClinicServer = HttpClinicServer(JsonHttpClient())
    private val clinicSyncEvents = InMemoryClinicSyncEvents()
    val serverRegistrations: ServerRegistrationRepository =
        SharedPreferencesServerRegistrationRepository(context, secretBox)
    val clinicEnrollment: ClinicEnrollment = StoredClinicEnrollment(clinicServer, serverRegistrations)
    val rehabPlans: RehabPlanRepository = SharedPreferencesRehabPlanRepository(context)
    val rehabPlanSynchronizer =
        RehabPlanSynchronizer(clinicServer, serverRegistrations, rehabPlans, activityHistory, scope)
    val patientAlerts: PatientAlertRepository = SharedPreferencesPatientAlertRepository(context)
    val patientAlertSynchronizer = PatientAlertSynchronizer(
        clinicServer,
        serverRegistrations,
        patientAlerts,
        AndroidPatientAlertNotifier(context),
        clinicSyncEvents,
        scope,
    )
    val minuteUploader = MinuteUploader(clinicServer, serverRegistrations, activityHistory, scope, clinicSyncEvents)
    val liveHeartRateSharing = LiveHeartRateSharing(clinicServer, serverRegistrations, liveHeartRateFeed, scope)
    val liveHeartRateRequests: LiveHeartRateRequestRepository =
        SharedPreferencesLiveHeartRateRequestRepository(context)
    val liveHeartRateRequestNotification = AndroidLiveHeartRateRequestNotification(context)
    val liveHeartRateRequestSynchronizer = LiveHeartRateRequestSynchronizer(
        clinicServer,
        serverRegistrations,
        liveHeartRateRequests,
        historySyncEvents,
        scope,
        schedule = { RequestedLiveHeartRateWorker.schedule(context, it) },
    )
    val requestedLiveHeartRateRunner = RequestedLiveHeartRateRunner(
        liveHeartRateRequests,
        liveHeartRateMeasurement,
        liveHeartRateRequestSynchronizer,
        bandRepository,
        bandAssociation,
    )

    val reminders: ReminderRepository = SharedPreferencesReminderRepository(context)
    val reminderCheck = ReminderCheck(
        activityHistoryRefresh,
        activityHistory,
        rehabPlans,
        reminders,
        AndroidReminderNotification(context),
        isMonitoringAvailable = { activitySource.monitoringUnavailableReason == null },
    )
    val reminderActions: ReminderActions = ReminderActionsStore(reminders)

    val backgroundSyncSchedule = BackgroundSyncSchedule(context, bandRepository, syncSettings, scope)
    val reminderSchedule = ReminderSchedule(context, reminders, scope)
}
