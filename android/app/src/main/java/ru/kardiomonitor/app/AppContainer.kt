package ru.kardiomonitor.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.connection.SingleBandConnection
import ru.kardiomonitor.features.band.data.SharedPreferencesBandRepository
import ru.kardiomonitor.features.band.data.SqliteBatteryLog
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.clinic.data.HttpClinicServer
import ru.kardiomonitor.features.clinic.data.SharedPreferencesServerRegistrationRepository
import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import ru.kardiomonitor.features.clinic.liveheartrateshare.LiveHeartRateSharing
import ru.kardiomonitor.features.clinic.minuteupload.MinuteUploader
import ru.kardiomonitor.features.monitoring.data.InMemoryLiveHeartRateFeed
import ru.kardiomonitor.features.monitoring.data.SharedPreferencesSyncSettings
import ru.kardiomonitor.features.monitoring.data.SqliteActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateFeed
import ru.kardiomonitor.features.monitoring.domain.SyncSettings
import ru.kardiomonitor.platform.db.KardioDatabase
import ru.kardiomonitor.platform.http.JsonHttpClient
import ru.kardiomonitor.platform.huami.HuamiBand

/**
 * Композиционный корень (ADR-0004): создаёт инфраструктуру и реализации портов один раз на
 * процесс и передаёт их через конструкторы в секции слайсов и Worker. Глобальных синглтонов
 * кроме него нет.
 */
class AppContainer(context: Context) {
    /** Scope приложения: живёт, пока жив процесс; сбой одной задачи не отменяет остальные. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = KardioDatabase(context)

    /** Единственный владелец GATT-соединения (ADR-0003). */
    private val huamiBand = HuamiBand(context, scope)

    val bandRepository: BandRepository = SharedPreferencesBandRepository(context)
    val bandConnection: BandConnection =
        SingleBandConnection(huamiBand, bandRepository, SqliteBatteryLog(database), scope)

    val activitySource: ActivitySource = huamiBand
    val activityHistory: ActivityHistory = SqliteActivityHistory(database)
    val syncSettings: SyncSettings = SharedPreferencesSyncSettings(context)
    val liveHeartRateFeed: LiveHeartRateFeed = InMemoryLiveHeartRateFeed()

    /** Обмен с клиникой: сервер (для демо — через ngrok) и регистрация телефона на нём. */
    val clinicServer: ClinicServer = HttpClinicServer(JsonHttpClient())
    val serverRegistrations: ServerRegistrationRepository = SharedPreferencesServerRegistrationRepository(context)
    val minuteUploader = MinuteUploader(clinicServer, serverRegistrations, activityHistory, scope)
    val liveHeartRateSharing = LiveHeartRateSharing(clinicServer, serverRegistrations, liveHeartRateFeed, scope)

    val backgroundSyncSchedule = BackgroundSyncSchedule(context, bandRepository, syncSettings, scope)
}
