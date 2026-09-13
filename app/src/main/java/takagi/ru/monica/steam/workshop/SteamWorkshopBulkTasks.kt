package takagi.ru.monica.steam.workshop

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import takagi.ru.monica.steam.data.SteamAccountSourceRepository
import takagi.ru.monica.steam.diagnostics.SteamDiagLogger
import takagi.ru.monica.steam.session.domain.SteamAccountSessionHandle
import takagi.ru.monica.steam.session.domain.SteamAccountSessionResolver

/** Application-owned, account/game-isolated queues backed by WorkManager and durable checkpoints. */
internal class SteamWorkshopBulkTasks private constructor(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        SteamDiagLogger.append("workshop_background failed type=${error::class.java.simpleName}")
    })
    private val workManager = WorkManager.getInstance(this.context)
    private val store = WorkshopBulkTaskStore(File(this.context.noBackupFilesDir, "workshop-tasks"))
    private val sessions = ConcurrentHashMap<String, Session>()

    fun controller(handle: SteamAccountSessionHandle, appId: Int, gameName: String): WorkshopBulkController =
        session(WorkshopBulkTarget(handle.stableKey, handle.account.id, handle.account.steamId, appId),
            gameName, handle.account.displayName.ifBlank { handle.account.accountName })

    suspend fun task(key: String): WorkshopBulkTask? = withContext(Dispatchers.IO) {
        if (!WorkshopBulkTaskStore.validKey(key)) return@withContext null
        val session = sessionForKey(key) ?: return@withContext null
        session.ready.await()
        session.task
    }

    suspend fun controller(key: String): WorkshopBulkController? = withContext(Dispatchers.IO) {
        sessionForKey(key)?.also { it.ready.await() }
    }

    fun stop(key: String, requestId: String, finished: () -> Unit) {
        scope.launch {
            try {
                sessionForKey(key)?.let { session ->
                    session.ready.await()
                    session.requestStop(requestId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
            finally { finished() }
        }
    }

    suspend fun fail(key: String, requestId: String, reason: WorkshopFailure) {
        withContext(Dispatchers.IO) {
            val session = sessionForKey(key) ?: return@withContext
            session.ready.await()
            session.lock.withLock {
                session.task?.takeIf { it.state.requestId == requestId && it.state.running }?.let {
                    session.publish(it.copy(state = it.state.copy(running = false, queued = false,
                        currentTitle = "", failure = reason)), force = true)
                }
            }
        }
    }

    suspend fun run(key: String, requestId: String, progress: suspend (WorkshopBulkTask) -> Unit): WorkshopBulkTask? =
        withContext(Dispatchers.IO) {
            val session = sessionForKey(key) ?: return@withContext null
            session.ready.await()
            val initial = session.lock.withLock {
                session.task?.takeIf { it.state.requestId == requestId && it.state.running }
            } ?: return@withContext null
            try {
                val repository = SteamAccountSourceRepository.get(context)
                val handle = repository.loadAllSessionHandles().firstOrNull {
                    it.stableKey == initial.target.handleKey && it.account.id == initial.target.accountId &&
                        it.account.steamId == initial.target.steamId
                } ?: throw WorkshopException(WorkshopFailure.LOGIN)
                val resolver = SteamAccountSessionResolver { account, force ->
                    repository.sessionManager.resolve(handle.copy(account = account), force).account
                }
                WorkshopBulkRunner(initial.target.appId, handle.account, SteamWorkshopService(), resolver).run(
                    initial.state,
                    stopRequested = { session.state.value?.stopRequested == true },
                    publish = { next ->
                        val snapshot = session.lock.withLock {
                            val current = session.task?.takeIf { it.state.requestId == requestId }
                                ?: throw CancellationException("Workshop task superseded")
                            current.copy(state = next.copy(stopRequested = next.stopRequested || current.state.stopRequested))
                                .also { session.publish(it) }
                        }
                        progress(snapshot)
                    }
                )
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    session.lock.withLock {
                        session.task?.takeIf { it.state.requestId == requestId && it.state.running }?.let {
                            session.publish(it.copy(state = it.state.copy(queued = true, currentTitle = "")), force = true)
                        }
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                fail(key, requestId, error.workshopFailure())
            }
            session.task?.takeIf { it.state.requestId == requestId }
        }

    private fun session(target: WorkshopBulkTarget, gameName: String, accountName: String): Session =
        sessions.computeIfAbsent(target.key) { Session(target, gameName.take(256), accountName.take(256)) }

    private fun sessionForKey(key: String): Session? {
        if (!WorkshopBulkTaskStore.validKey(key)) return null
        return sessions[key] ?: store.read(key)?.let { session(it.target, it.gameName, it.accountName) }
    }

    private inner class Session(
        val target: WorkshopBulkTarget, val gameName: String, val accountName: String
    ) : WorkshopBulkController {
        val lock = Mutex()
        @Volatile var task: WorkshopBulkTask? = null
            private set
        private var persisted: WorkshopBulkTask? = null
        private var monitor: Job? = null
        private val mutableState = MutableStateFlow<WorkshopBulkState?>(null)
        override val state = mutableState.asStateFlow()
        val ready = scope.async {
            lock.withLock {
                try {
                    val saved = store.read(target.key) ?: return@withLock
                    persisted = saved
                    publish(saved, force = false)
                    if (saved.state.running) {
                        val info = workManager.getWorkInfoById(UUID.fromString(saved.state.requestId)).get()
                        when {
                            info == null -> schedule(saved)
                            info.state.isFinished -> publish(saved.copy(state = saved.state.copy(
                                running = false, queued = false, currentTitle = "",
                                failure = if (saved.state.stopRequested) null else WorkshopFailure.BACKGROUND)), force = true)
                            info.state != WorkInfo.State.RUNNING -> publish(saved.copy(state = saved.state.copy(
                                queued = true, currentTitle = "")), force = true)
                        }
                        if (task?.state?.running == true) watch(saved.state.requestId)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    report(error)
                    task?.let { mutableState.value = it.state.copy(running = false, queued = false, failure = WorkshopFailure.BACKGROUND) }
                    task = task?.copy(state = requireNotNull(mutableState.value))
                }
            }
        }

        override fun start(ids: List<String>, subscribe: Boolean, dependencies: Boolean) {
            val captured = ids.distinct()
            if (captured.size !in 1..WorkshopShareCode.MAX_ITEMS || captured.any { !WorkshopShareCode.validId(it) }) return
            enqueue(WorkshopBulkState(captured, subscribe, dependencies && subscribe, queued = true))
        }

        override fun startPreset(preset: WorkshopPresetSwitch) {
            preset.validate(target.appId)
            enqueue(WorkshopBulkState(preset.operationIds, true, false, queued = true, preset = preset))
        }

        private fun enqueue(state: WorkshopBulkState) {
            scope.launch {
                ready.await()
                lock.withLock {
                    if (task?.state?.running == true) return@withLock
                    val request = WorkshopBulkTask(target, gameName, accountName, state)
                    try {
                        task?.let { SteamWorkshopBulkNotifications(context).cancel(it.state.requestId) }
                        publish(request, force = true)
                        schedule(request)
                        watch(request.state.requestId)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        report(error)
                        val failed = request.copy(state = request.state.copy(running = false, queued = false, failure = WorkshopFailure.BACKGROUND))
                        runCatching { publish(failed, force = true) }.onFailure {
                            task = failed
                            mutableState.value = failed.state
                        }
                    }
                }
            }
        }

        override fun stop() {
            scope.launch {
                ready.await()
                task?.state?.requestId?.let { requestStop(it) }
            }
        }

        suspend fun requestStop(requestId: String) {
            lock.withLock {
                val current = task?.takeIf { it.state.requestId == requestId && it.state.running } ?: return@withLock
                val queued = current.state.queued
                publish(current.copy(state = current.state.copy(stopRequested = true,
                    running = !queued, queued = false)), force = true)
                if (queued) workManager.cancelWorkById(UUID.fromString(requestId)).result.get()
            }
        }

        override fun dismissFinished() {
            scope.launch {
                ready.await()
                lock.withLock {
                    if (task?.state?.running == true) return@withLock
                    task?.let { SteamWorkshopBulkNotifications(context).cancel(it.state.requestId) }
                    monitor?.cancel()
                    store.delete(target.key)
                    task = null
                    persisted = null
                    mutableState.value = null
                }
            }
        }

        fun publish(next: WorkshopBulkTask, force: Boolean = false) {
            val saved = persisted?.state
            task = next
            mutableState.value = next.state
            // Checkpoint every metadata chunk and on every lifecycle/stop/failure boundary. Replayed
            // items are inspected first, so a process death cannot duplicate a confirmed write.
            if (force || saved == null || next.state.requestId != saved.requestId || !next.state.running ||
                next.state.queued != saved.queued || next.state.stopRequested != saved.stopRequested ||
                next.state.failure != saved.failure || next.state.results.size - saved.results.size >= 20) {
                store.write(next)
                persisted = next
            }
        }

        private fun watch(requestId: String) {
            monitor?.cancel()
            monitor = scope.launch {
                // Includes failures before doWork (for example foreground startup rejection), so
                // the UI cannot remain on a running queue after WorkManager has already stopped it.
                workManager.getWorkInfoByIdLiveData(UUID.fromString(requestId)).asFlow()
                    .filterNotNull().first { it.state.isFinished }
                lock.withLock {
                    task?.takeIf { it.state.requestId == requestId && it.state.running }?.let {
                        val ended = it.copy(state = it.state.copy(running = false, queued = false, currentTitle = "",
                            failure = if (it.state.stopRequested) null else WorkshopFailure.BACKGROUND))
                        publish(ended, force = true)
                        SteamWorkshopBulkNotifications(context).completed(ended)
                    }
                }
            }
        }
    }

    private fun schedule(task: WorkshopBulkTask) {
        val request = OneTimeWorkRequestBuilder<SteamWorkshopBulkWorker>()
            .setId(UUID.fromString(task.state.requestId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString(KEY_TASK, task.target.key).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // A just-completed worker may still be publishing its notification. Append instead of
        // dropping a user's retry while WorkManager is committing that worker's terminal state.
        workManager.enqueueUniqueWork("workshop_bulk_${task.target.key}", ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get()
    }

    private fun report(error: Exception) {
        SteamDiagLogger.append("workshop_background failed type=${error::class.java.simpleName}")
    }

    companion object {
        const val KEY_TASK = "workshop_task_key"
        @Volatile private var instance: SteamWorkshopBulkTasks? = null
        fun get(context: Context): SteamWorkshopBulkTasks = instance ?: synchronized(this) {
            instance ?: SteamWorkshopBulkTasks(context).also { instance = it }
        }
    }
}
