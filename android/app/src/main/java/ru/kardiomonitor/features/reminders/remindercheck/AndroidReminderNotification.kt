package ru.kardiomonitor.features.reminders.remindercheck

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import ru.kardiomonitor.features.reminders.api.ReminderActionContract
import ru.kardiomonitor.features.reminders.domain.ReminderDecision
import ru.kardiomonitor.features.reminders.domain.ReminderKind

/** Системные уведомления: отдельный канал каждого вида, отложить и выключить на сегодня. */
class AndroidReminderNotification(private val context: Context) : ReminderNotification {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        channels().forEach(manager::createNotificationChannel)
    }

    override fun show(decision: ReminderDecision): Boolean {
        if (!canNotify() || !channelEnabled(decision.kind)) return false
        val notification = Notification.Builder(context, channelId(decision.kind))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title(decision.kind))
            .setContentText(message(decision))
            .setAutoCancel(true)
            .addAction(notificationAction("Отложить на 30 мин", ReminderActionContract.SNOOZE, decision.kind))
            .addAction(notificationAction("Не напоминать сегодня", ReminderActionContract.MUTE_TODAY, decision.kind))
            .build()
        manager.notify(decision.kind.ordinal + NOTIFICATION_BASE, notification)
        return true
    }

    private fun action(action: String, kind: ReminderKind): PendingIntent {
        val intent = Intent(action)
            .setClassName(context.packageName, ReminderActionContract.RECEIVER)
            .putExtra(ReminderActionContract.KIND, kind.name)
        return PendingIntent.getBroadcast(
            context,
            kind.ordinal * 10 + action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notificationAction(title: String, actionName: String, kind: ReminderKind): Notification.Action =
        Notification.Action.Builder(null, title, action(actionName, kind)).build()

    private fun channels(): List<NotificationChannel> = ReminderKind.entries.map { kind ->
        NotificationChannel(channelId(kind), title(kind), NotificationManager.IMPORTANCE_DEFAULT)
    }

    private fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled()

    private fun channelEnabled(kind: ReminderKind): Boolean =
        manager.getNotificationChannel(channelId(kind))?.importance != NotificationManager.IMPORTANCE_NONE

    private fun title(kind: ReminderKind): String = when (kind) {
        ReminderKind.Move -> "Пора пройтись"
        ReminderKind.DailyTarget -> "Норма дня"
        ReminderKind.WearBand -> "Наденьте браслет"
    }

    private fun message(decision: ReminderDecision): String = when (val details = decision.details) {
        is ReminderDecision.Details.Move -> "За последний интервал ${details.steps} из ${details.thresholdSteps} шагов"
        is ReminderDecision.Details.DailyTarget ->
            "Осталось ${details.remainingTrainingMinutes} мин и ${details.remainingSteps} шагов"
        is ReminderDecision.Details.WearBand -> "Браслет снят не меньше ${details.notWornMinutes} минут"
    }

    private fun channelId(kind: ReminderKind): String = "patient_reminder_${kind.name.lowercase()}"

    private companion object {
        const val NOTIFICATION_BASE = 20_000
    }
}
