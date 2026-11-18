package ru.kardiomonitor.features.monitoring.liveheartrate

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo

/** Постоянное видимое уведомление клинического измерения с действием остановки. */
class AndroidLiveHeartRateRequestNotification(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Пульс по запросу клиники", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun canShow(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    fun foregroundInfo(requestId: String, durationMinutes: Int): ForegroundInfo {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Клиника запросила измерение пульса")
            .setContentText("Непрерывное измерение до $durationMinutes мин")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Остановить", stopAction(requestId)).build())
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun stopAction(requestId: String): PendingIntent {
        val intent = Intent(ACTION_STOP)
            .setClassName(context.packageName, RECEIVER)
            .putExtra(EXTRA_REQUEST_ID, requestId)
        return PendingIntent.getBroadcast(
            context,
            requestId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_STOP = "ru.kardiomonitor.action.STOP_REQUESTED_LIVE_HEART_RATE"
        const val EXTRA_REQUEST_ID = "request_id"
        const val RECEIVER = "ru.kardiomonitor.app.LiveHeartRateRequestActionReceiver"
        private const val CHANNEL_ID = "clinic_live_heart_rate"
        private const val NOTIFICATION_ID = 30_001
    }
}
