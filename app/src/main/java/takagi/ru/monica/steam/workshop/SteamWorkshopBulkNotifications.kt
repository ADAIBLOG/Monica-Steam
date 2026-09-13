package takagi.ru.monica.steam.workshop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import java.util.UUID
import takagi.ru.monica.MonicaSteamActivity
import takagi.ru.monica.R

internal class SteamWorkshopBulkNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun foreground(task: WorkshopBulkTask?): ForegroundInfo {
        val id = task?.state?.requestId?.let(::notificationId) ?: FALLBACK_NOTIFICATION_ID
        val notification = notification(task, ongoing = true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(id, notification)
    }

    fun completed(task: WorkshopBulkTask) {
        if (!manager.areNotificationsEnabled()) return
        try { manager.notify(task.state.requestId, COMPLETED_NOTIFICATION_ID, notification(task, ongoing = false)) }
        catch (_: SecurityException) { /* Notification denial never changes a subscription result. */ }
    }

    fun cancel(requestId: String) {
        manager.cancel(notificationId(requestId))
        manager.cancel(requestId, COMPLETED_NOTIFICATION_ID)
    }

    private fun notification(task: WorkshopBulkTask?, ongoing: Boolean): Notification {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.workshop_background_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val state = task?.state
        val title = if (ongoing) context.getString(when {
            state?.preset != null -> R.string.workshop_preset_switch
            state?.subscribe != false -> R.string.workshop_bulk_subscribe
            else -> R.string.workshop_bulk_unsubscribe
        })
        else context.getString(if (state != null && (state.failure != null || state.remaining != 0 || state.failed > 0 ||
                (state.preset != null && !state.presetVerified)))
            R.string.workshop_background_attention else R.string.workshop_background_complete)
        val progress = when {
            state == null || state.queued -> context.getString(R.string.workshop_background_queued)
            state.presetVerified -> context.getString(R.string.workshop_preset_applied, requireNotNull(state.preset).name)
            ongoing && state.phase == WorkshopBulkPhase.CHECKING -> context.getString(R.string.workshop_preset_checking)
            ongoing && state.phase == WorkshopBulkPhase.VERIFYING -> context.getString(R.string.workshop_preset_verifying)
            else -> context.getString(R.string.workshop_bulk_progress, state.results.size, state.ids.size)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_steam_chat_notification)
            .setContentTitle(task?.gameName?.takeIf { it.isNotBlank() }?.let { "$title · $it" } ?: title)
            .setContentText(progress)
            .setSubText(task?.accountName)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(if (ongoing) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
        if (task != null) {
            val open = Intent(context, MonicaSteamActivity::class.java).apply {
                action = ACTION_OPEN
                data = Uri.parse("monica-internal://workshop/${task.state.requestId}")
                putExtra(SteamWorkshopBulkTasks.KEY_TASK, task.target.key)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            builder.setContentIntent(PendingIntent.getActivity(context, 0, open, PENDING_FLAGS))
            if (ongoing) {
                builder.setProgress(task.state.ids.size, task.state.results.size, task.state.queued || task.state.phase != WorkshopBulkPhase.ITEMS)
                if (!task.state.stopRequested) {
                    val stop = Intent(context, SteamWorkshopBulkActionReceiver::class.java).apply {
                        action = ACTION_STOP
                        data = Uri.parse("monica-internal://workshop/${task.state.requestId}")
                        putExtra(SteamWorkshopBulkTasks.KEY_TASK, task.target.key)
                        putExtra(EXTRA_REQUEST, task.state.requestId)
                    }
                    builder.addAction(0, context.getString(R.string.workshop_stop_queue),
                        PendingIntent.getBroadcast(context, 0, stop, PENDING_FLAGS))
                }
                if (task.state.stopRequested) builder.setContentText(context.getString(R.string.workshop_stopping))
            } else builder.setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(
                R.string.workshop_bulk_summary, task.state.updated, task.state.unchanged, task.state.failed, task.state.remaining)))
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "steam_workshop_tasks"
        private const val COMPLETED_NOTIFICATION_ID = 7312
        private const val FALLBACK_NOTIFICATION_ID = 7313
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private const val ACTION_OPEN = "takagi.ru.monica.steam.workshop.OPEN_TASK"
        internal const val ACTION_STOP = "takagi.ru.monica.steam.workshop.STOP_TASK"
        internal const val EXTRA_REQUEST = "workshop_request_id"
        private fun notificationId(requestId: String): Int = 0x5a000000 or (requestId.hashCode() and 0x00ffffff)

        fun consumeOpenIntent(intent: Intent?): String? {
            if (intent?.action != ACTION_OPEN) return null
            val key = intent.getStringExtra(SteamWorkshopBulkTasks.KEY_TASK)
                ?.takeIf(WorkshopBulkTaskStore::validKey)
            intent.removeExtra(SteamWorkshopBulkTasks.KEY_TASK)
            return key
        }
    }
}

class SteamWorkshopBulkActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != SteamWorkshopBulkNotifications.ACTION_STOP) return
        val key = intent.getStringExtra(SteamWorkshopBulkTasks.KEY_TASK)?.takeIf(WorkshopBulkTaskStore::validKey) ?: return
        val requestId = intent.getStringExtra(SteamWorkshopBulkNotifications.EXTRA_REQUEST) ?: return
        if (runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false).not()) return
        val pending = goAsync()
        SteamWorkshopBulkTasks.get(context).stop(key, requestId) { pending.finish() }
    }
}
