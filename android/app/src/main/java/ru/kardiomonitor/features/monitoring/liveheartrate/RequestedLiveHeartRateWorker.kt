package ru.kardiomonitor.features.monitoring.liveheartrate

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateRequest

/** Long-running foreground Worker непрерывного измерения по одному запросу клиники. */
class RequestedLiveHeartRateWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: RequestedLiveHeartRateRunner,
    private val notification: AndroidLiveHeartRateRequestNotification,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(REQUEST_ID) ?: return Result.failure()
        val request = runner.request(requestId) ?: return Result.success()
        val missing = runner.missingPrerequisite(request, notification.canShow())
        if (missing != null) {
            runner.fail(requestId, missing)
            return Result.success()
        }
        if (!promoteToForeground(request)) return Result.success()
        runner.run(requestId)
        return Result.success()
    }

    private suspend fun promoteToForeground(request: LiveHeartRateRequest): Boolean = try {
        setForeground(notification.foregroundInfo(request.id, request.durationMinutes))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "не удалось показать foreground-уведомление измерения", e)
        runner.fail(request.id, LiveHeartRateRequest.FailureReason.MeasurementFailed)
        false
    }

    companion object {
        private const val REQUEST_ID = "request_id"
        private const val WORK_PREFIX = "requested-live-heart-rate-"
        private const val TAG = "KardioBLE"

        fun schedule(context: Context, requestId: String) {
            val input = Data.Builder().putString(REQUEST_ID, requestId).build()
            val request = OneTimeWorkRequestBuilder<RequestedLiveHeartRateWorker>()
                .setInputData(input)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_PREFIX + requestId,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, requestId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + requestId)
        }
    }
}
