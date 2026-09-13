package takagi.ru.monica.steam.workshop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import takagi.ru.monica.steam.data.SteamAccount
import takagi.ru.monica.steam.network.SteamApiException
import takagi.ru.monica.steam.session.domain.SteamAccountSessionResolver

/** The page submits commands and observes progress; it never owns the running job. */
internal interface WorkshopBulkController {
    val state: StateFlow<WorkshopBulkState?>
    fun start(ids: List<String>, subscribe: Boolean, dependencies: Boolean)
    fun startPreset(preset: WorkshopPresetSwitch)
    fun retry() {
        val current = state.value?.takeIf { it.canRetry } ?: return
        current.preset?.let(::startPreset) ?: start(current.retryIds, current.subscribe, current.dependencies)
    }
    fun stop()
    fun dismissFinished()
    fun updateAccount(account: SteamAccount) = Unit
}

/** Shared by Android's persistent worker and the deterministic host/test controller. */
internal class WorkshopBulkRunner(
    private val appId: Int,
    private var account: SteamAccount?,
    private val gateway: SteamWorkshopGateway,
    private val resolver: SteamAccountSessionResolver? = null
) {
    suspend fun run(
        initial: WorkshopBulkState,
        stopRequested: () -> Boolean,
        publish: suspend (WorkshopBulkState) -> Unit
    ): WorkshopBulkState {
        var state = initial.copy(queued = false, currentTitle = "", failure = null, presetVerified = false)
        val preset = state.preset
        var snapshot: WorkshopSubscriptionSnapshot? = null
        var verified = false
        fun shouldStop() = state.stopRequested || stopRequested()
        suspend fun emit(next: WorkshopBulkState) {
            state = next.copy(stopRequested = next.stopRequested || stopRequested())
            publish(state)
        }
        emit(state)
        suspend fun subscriptions(): WorkshopSubscriptionSnapshot {
            fun checkStop() { if (shouldStop()) throw WorkshopQueueStopped() }
            checkStop()
            return readAllWorkshopSubscriptions(appId, read = { page ->
                checkStop()
                authenticated { gateway.browse(it, appId, WorkshopQuery(subscribedOnly = true, sort = WorkshopSort.UPDATED), page) }
            }, progress = { checkStop() }).also { current ->
                // Delayed tasks must never remove newly discovered items that were not in the preview.
                val allowed = requireNotNull(preset).previousIds.toSet() + preset.targetIds
                if (current.ids.any { it !in allowed }) throw WorkshopException(WorkshopFailure.SUBSCRIPTIONS_CHANGED)
            }
        }
        try {
            if (preset != null && !shouldStop()) {
                preset.validate(appId)
                require(initial.ids == preset.operationIds && !initial.dependencies)
                emit(state.copy(phase = WorkshopBulkPhase.CHECKING))
                snapshot = subscriptions()
            }
            val processed = initial.results.mapTo(hashSetOf()) { it.id }
            val groups = if (preset == null) listOf(initial.ids) else listOf(preset.share.itemIds, preset.removeIds)
            operations@ for ((groupIndex, group) in groups.withIndex()) {
                if (shouldStop()) break
                if (preset != null && groupIndex == 1 && group.any { it !in processed }) {
                    val failedTarget = state.results.firstOrNull { it.id in preset.targetIds &&
                        it.outcome !in listOf(WorkshopBulkOutcome.UPDATED, WorkshopBulkOutcome.UNCHANGED) }
                    if (failedTarget != null) throw WorkshopException(failedTarget.failure ?: WorkshopFailure.UNAVAILABLE)
                    emit(state.copy(phase = WorkshopBulkPhase.CHECKING, currentTitle = ""))
                    snapshot = subscriptions()
                    if (!snapshot.ids.toHashSet().containsAll(preset.targetIds)) throw WorkshopException(WorkshopFailure.SUBSCRIPTIONS_CHANGED)
                }
                val remaining = group.filterNot { it in processed }
                for (chunk in remaining.chunked(20)) {
                    if (shouldStop()) break@operations
                    // After interruption even the in-flight item is re-read before any write.
                    val inspected = authenticated { gateway.inspectItems(it, appId, chunk) }
                    if (inspected.any { it.appId != appId || it.id !in chunk } ||
                        inspected.map { it.id }.distinct().size != inspected.size) {
                        throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
                    }
                    val items = inspected.associateBy { it.id }
                    for (id in chunk) {
                        if (shouldStop()) break@operations
                        val subscribe = state.subscribes(id)
                        // A complete authenticated subscription snapshot also identifies deleted/private
                        // old items. They can be retained or removed, but never newly subscribed blindly.
                        val item = items[id] ?: snapshot?.let { current ->
                            if (!subscribe || id in current.ids) WorkshopItem(id, appId,
                                current.items[id]?.title ?: id, subscribed = id in current.ids) else null
                        }
                        val title = (item?.title ?: id).take(256)
                        emit(state.copy(currentTitle = title, phase = WorkshopBulkPhase.ITEMS))
                        val result = try {
                            when {
                                item == null -> WorkshopBulkResult(id, title, WorkshopBulkOutcome.UNAVAILABLE)
                                item.subscribed == subscribe -> WorkshopBulkResult(id, title, WorkshopBulkOutcome.UNCHANGED)
                                subscribe && !item.canSubscribeHere -> WorkshopBulkResult(id, title, WorkshopBulkOutcome.UNAVAILABLE)
                                item.subscribed == null -> throw WorkshopException(WorkshopFailure.UNCONFIRMED)
                                else -> {
                                    authenticated { gateway.setSubscription(it, item, subscribe, state.dependencies && subscribe) }
                                    WorkshopBulkResult(id, title, WorkshopBulkOutcome.UPDATED)
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            WorkshopBulkResult(id, title, WorkshopBulkOutcome.FAILED, error.workshopFailure())
                        }
                        emit(state.copy(results = state.results + result))
                        if ((preset != null && result.outcome in listOf(WorkshopBulkOutcome.FAILED, WorkshopBulkOutcome.UNAVAILABLE)) ||
                            result.failure in listOf(WorkshopFailure.LOGIN, WorkshopFailure.RATE_LIMIT,
                                WorkshopFailure.NETWORK, WorkshopFailure.UNCONFIRMED)) {
                            emit(state.copy(failure = result.failure ?: WorkshopFailure.UNAVAILABLE))
                            break@operations
                        }
                        if (state.remaining > 0) delay(350)
                    }
                }
            }
            if (preset != null && state.remaining == 0 && state.failed == 0 && state.failure == null && !shouldStop()) {
                emit(state.copy(phase = WorkshopBulkPhase.VERIFYING, currentTitle = ""))
                val finalIds = subscriptions().ids.toSet()
                if (finalIds != preset.targetIds) throw WorkshopException(WorkshopFailure.SUBSCRIPTIONS_CHANGED)
                verified = true
            }
        } catch (_: WorkshopQueueStopped) {
            // An explicit stop during a read has no write in flight and can finish immediately.
        } catch (cancelled: CancellationException) {
            // A Worker stop is resumable. Its owner checkpoints progress, without declaring completion.
            throw cancelled
        } catch (error: Exception) {
            emit(state.copy(failure = error.workshopFailure()))
        }
        emit(state.copy(running = false, queued = false, currentTitle = "", presetVerified = verified))
        return state
    }

    private class WorkshopQueueStopped : Exception()

    private suspend fun <T> authenticated(block: suspend (SteamAccount) -> T): T {
        val original = account ?: throw WorkshopException(WorkshopFailure.LOGIN)
        suspend fun resolve(current: SteamAccount, force: Boolean): SteamAccount {
            val resolved = resolver?.resolve(current, force) ?: current
            if (resolved.id != original.id || resolved.steamId != original.steamId) {
                throw WorkshopException(WorkshopFailure.LOGIN)
            }
            account = resolved
            return resolved
        }
        val resolved = resolve(original, false)
        return try { block(resolved) } catch (error: SteamApiException) {
            if (error.workshopFailure() != WorkshopFailure.LOGIN || resolver == null) throw error
            block(resolve(resolved, true))
        }
    }
}

/** For JVM callers without Android WorkManager. Production injects SteamWorkshopBulkTasks. */
internal class InMemoryWorkshopBulkController(
    private val appId: Int,
    private var account: SteamAccount?,
    private val gateway: SteamWorkshopGateway,
    private val resolver: SteamAccountSessionResolver? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : WorkshopBulkController {
    private val mutableState = MutableStateFlow<WorkshopBulkState?>(null)
    override val state = mutableState.asStateFlow()

    override fun start(ids: List<String>, subscribe: Boolean, dependencies: Boolean) {
        launch(WorkshopBulkState(ids.distinct(), subscribe, dependencies && subscribe))
    }

    override fun startPreset(preset: WorkshopPresetSwitch) {
        preset.validate(appId)
        launch(WorkshopBulkState(preset.operationIds, true, false, preset = preset))
    }

    private fun launch(request: WorkshopBulkState) {
        if (state.value?.running == true) return
        mutableState.value = request
        scope.launch {
            WorkshopBulkRunner(appId, account, gateway, resolver).run(request,
                stopRequested = { state.value?.stopRequested == true },
                publish = { next -> mutableState.value = next.copy(stopRequested = next.stopRequested || state.value?.stopRequested == true) })
        }
    }

    override fun stop() { mutableState.value = state.value?.copy(stopRequested = true) }
    override fun dismissFinished() { if (state.value?.running != true) mutableState.value = null }
    override fun updateAccount(account: SteamAccount) {
        if (this.account?.id == account.id && this.account?.steamId == account.steamId) this.account = account
    }
}
