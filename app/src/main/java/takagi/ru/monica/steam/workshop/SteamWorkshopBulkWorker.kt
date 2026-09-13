package takagi.ru.monica.steam.workshop

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import takagi.ru.monica.steam.diagnostics.SteamDiagLogger

class SteamWorkshopBulkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val tasks = SteamWorkshopBulkTasks.get(applicationContext)
    private val notifications = SteamWorkshopBulkNotifications(applicationContext)

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.foreground(
        inputData.getString(SteamWorkshopBulkTasks.KEY_TASK)?.let { tasks.task(it) }
    )

    override suspend fun doWork(): Result {
        val key = inputData.getString(SteamWorkshopBulkTasks.KEY_TASK) ?: return Result.failure()
        val requestId = id.toString()
        try {
            val task = tasks.task(key)?.takeIf { it.state.requestId == requestId } ?: return Result.success()
            if (!task.state.running) return Result.success()
            // The notification-backed worker owns network execution even after the Activity is gone.
            setForeground(notifications.foreground(task))
            var lastNotification = 0L
            var lastCompleted = -1
            val result = tasks.run(key, requestId) { snapshot ->
                if (snapshot.state.results.size != lastCompleted || !snapshot.state.running) {
                    lastCompleted = snapshot.state.results.size
                    setProgress(Data.Builder().putInt("completed", lastCompleted).putInt("total", snapshot.state.ids.size).build())
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotification >= 1000 || !snapshot.state.running || snapshot.state.stopRequested) {
                    setForeground(notifications.foreground(snapshot))
                    lastNotification = now
                }
            } ?: return Result.success()
            if (tasks.task(key)?.state?.requestId == requestId) notifications.completed(result)
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SteamDiagLogger.append("workshop_background_worker failed type=${error::class.java.simpleName}")
            tasks.fail(key, requestId, WorkshopFailure.BACKGROUND)
            tasks.task(key)?.takeIf { it.state.requestId == requestId }?.let(notifications::completed)
            return Result.failure()
        }
    }
}
