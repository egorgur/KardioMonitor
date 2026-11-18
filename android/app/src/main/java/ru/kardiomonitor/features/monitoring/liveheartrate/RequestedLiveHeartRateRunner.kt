package ru.kardiomonitor.features.monitoring.liveheartrate

import android.util.Log
import kotlinx.coroutines.CancellationException
import ru.kardiomonitor.features.band.api.BandAssociation
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateRequest
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateRequestRepository
import java.io.IOException
import java.time.Clock

/** Проверяет системные предпосылки и выполняет сохранённый запрос ровно оставшееся время. */
class RequestedLiveHeartRateRunner(
    private val requests: LiveHeartRateRequestRepository,
    private val measurement: LiveHeartRateMeasurement,
    private val synchronizer: LiveHeartRateRequestSynchronizer,
    private val bands: BandRepository,
    private val association: BandAssociation,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun request(requestId: String): LiveHeartRateRequest? =
        requests.request.value?.takeIf { it.id == requestId }

    fun missingPrerequisite(request: LiveHeartRateRequest, canNotify: Boolean): LiveHeartRateRequest.FailureReason? {
        if (!canNotify) return LiveHeartRateRequest.FailureReason.NotificationPermissionDenied
        val band = bands.band.value ?: return LiveHeartRateRequest.FailureReason.BandUnavailable
        if (!band.credentials.protocol.canMonitorActivity) return LiveHeartRateRequest.FailureReason.BandUnavailable
        if (!association.isAssociated(band.credentials.address)) {
            return LiveHeartRateRequest.FailureReason.CompanionAssociationMissing
        }
        if (!clock.instant().isBefore(request.expiresAt) && request.startedAt == null) {
            return LiveHeartRateRequest.FailureReason.Expired
        }
        return null
    }

    suspend fun fail(requestId: String, reason: LiveHeartRateRequest.FailureReason) {
        synchronizer.report(requestId, LiveHeartRateRequest.Report(LiveHeartRateRequest.Status.Failed, reason))
    }

    suspend fun run(requestId: String) {
        synchronizer.report(requestId, LiveHeartRateRequest.Report(LiveHeartRateRequest.Status.Running))
        val request = request(requestId) ?: return
        val remaining = request.remainingSeconds(clock.instant())
        try {
            if (remaining > 0) measurement.runRequested(remaining)
            synchronizer.report(requestId, LiveHeartRateRequest.Report(LiveHeartRateRequest.Status.Completed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "измерение по запросу клиники не подключилось к браслету", e)
            fail(requestId, LiveHeartRateRequest.FailureReason.BandUnavailable)
        } catch (e: Exception) {
            Log.e(TAG, "измерение по запросу клиники завершилось с ошибкой", e)
            fail(requestId, LiveHeartRateRequest.FailureReason.MeasurementFailed)
        }
    }

    private companion object {
        const val TAG = "KardioBLE"
    }
}
