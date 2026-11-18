package ru.kardiomonitor.features.band.statusrefresh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo

/** Отдельное уведомление: проверка связи не называется мониторингом пациента. */
internal class BandStatusNotification(private val context: Context) {
    fun foregroundInfo(): ForegroundInfo {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Проверка связи с браслетом", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Проверка связи с Band 9")
            .setContentText("Только заряд и настройки. Мониторинг пока недоступен")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        return ForegroundInfo(30_002, notification, type)
    }

    private companion object {
        const val CHANNEL = "band_read_only_status"
    }
}
