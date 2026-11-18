package ru.kardiomonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.kardiomonitor.features.monitoring.liveheartrate.AndroidLiveHeartRateRequestNotification
import ru.kardiomonitor.features.monitoring.liveheartrate.RequestedLiveHeartRateWorker

/** Сначала устойчиво записывает остановку пациентом, затем отменяет foreground Worker. */
class LiveHeartRateRequestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidLiveHeartRateRequestNotification.ACTION_STOP) return
        val requestId = intent.getStringExtra(AndroidLiveHeartRateRequestNotification.EXTRA_REQUEST_ID) ?: return
        val pending = goAsync()
        val synchronizer = (context.applicationContext as KardioApplication)
            .container.liveHeartRateRequestSynchronizer
        synchronizer.stopByPatient(requestId) {
            RequestedLiveHeartRateWorker.cancel(context, requestId)
            pending.finish()
        }
    }
}
