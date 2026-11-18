package ru.kardiomonitor.features.alerts.alertsync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import ru.kardiomonitor.features.alerts.api.PatientAlertText
import ru.kardiomonitor.features.alerts.domain.PatientAlert
import ru.kardiomonitor.features.alerts.domain.PatientAlertNotifier

/** Системные уведомления канала «Оповещения клиники». */
class AndroidPatientAlertNotifier(private val context: Context) : PatientAlertNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Оповещения клиники", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Оповещения, полученные от клиники при синхронизации"
            },
        )
    }

    override fun show(alert: PatientAlert): Boolean {
        if (!canNotify()) return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(PatientAlertText.title(alert))
            .setContentText(PatientAlertText.message(alert))
            .setStyle(Notification.BigTextStyle().bigText(PatientAlertText.message(alert)))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        manager.notify(alert.id.hashCode(), notification)
        return true
    }

    private fun canNotify(): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted && manager.areNotificationsEnabled()
    }

    private companion object {
        const val CHANNEL_ID = "clinic_alerts"
    }
}
