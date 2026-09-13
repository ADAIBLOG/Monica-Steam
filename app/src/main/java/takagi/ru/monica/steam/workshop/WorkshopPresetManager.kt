package takagi.ru.monica.steam.workshop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class WorkshopPresetUiState(
    val visible: Boolean = false,
    val library: WorkshopPresetLibrary = WorkshopPresetLibrary(),
    val draft: WorkshopPresetDraft? = null,
    val preview: WorkshopPresetPreview? = null
) {
    val readingSubscriptions: Boolean get() = draft?.loading == true || preview != null
}

/** Local preset editing and cancellable previews. Subscription writes belong to the bulk controller. */
internal class WorkshopPresetManager(
    private val appId: Int,
    private val scope: CoroutineScope,
    private val repository: WorkshopPresetRepository,
    private val processing: CoroutineDispatcher,
    private val read: suspend (Int) -> WorkshopBatch,
    private val inspect: suspend (List<String>) -> List<WorkshopItem>,
    private val canReadSubscriptions: () -> Boolean,
    private val onSaved: () -> Unit
) {
    private val mutableState = MutableStateFlow(WorkshopPresetUiState())
    val state = mutableState.asStateFlow()
    private var libraryJob: Job? = null
    private var draftJob: Job? = null
    private var previewJob: Job? = null
    private var previewGeneration = 0L

    fun show() {
        mutableState.update { it.copy(visible = true) }
        reload()
    }

    fun hide() { mutableState.update { it.copy(visible = false) } }

    fun reload() {
        libraryJob?.cancel()
        mutableState.update { it.copy(library = it.library.copy(loading = true, problem = null)) }
        libraryJob = scope.launch {
            try {
                val items = repository.load(appId)
                mutableState.update { it.copy(library = WorkshopPresetLibrary(items)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(library = it.library.copy(loading = false, problem = WorkshopPresetProblem.STORAGE)) }
            }
        }
    }

    fun fromSubscriptions(replacing: WorkshopPreset? = null) {
        if (!canReadSubscriptions() || state.value.draft?.saving == true || replacing?.share?.appId?.let { it != appId } == true) return
        val draft = WorkshopPresetDraft(replacingId = replacing?.id, name = replacing?.name.orEmpty(),
            fromSubscriptions = true, loading = true)
        draftJob?.cancel()
        mutableState.update { it.copy(draft = draft) }
        loadSubscriptions(draft)
    }

    fun retryDraft() {
        val draft = state.value.draft?.takeIf { it.fromSubscriptions && !it.saving } ?: return
        if (!canReadSubscriptions()) return
        draftJob?.cancel()
        updateDraft(draft.key) { it.copy(loading = true, failure = null, shareProblem = null, count = 0) }
        loadSubscriptions(draft)
    }

    private fun loadSubscriptions(draft: WorkshopPresetDraft) {
        draftJob = scope.launch {
            try {
                val snapshot = readAllWorkshopSubscriptions(appId, read) { count -> updateDraft(draft.key) { it.copy(count = count) } }
                val share = WorkshopShare(appId, snapshot.ids)
                withContext(processing) { WorkshopShareCode.encode(share) }
                updateDraft(draft.key) { it.copy(share = share, loading = false, count = share.itemIds.size) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: WorkshopShareCodeException) {
                updateDraft(draft.key) { it.copy(loading = false, shareProblem = error.problem) }
            } catch (error: Exception) {
                updateDraft(draft.key) { it.copy(loading = false, failure = error.workshopFailure()) }
            }
        }
    }

    fun fromShare(share: WorkshopShare) {
        if (share.appId != appId || state.value.draft?.saving == true) return
        draftJob?.cancel()
        val draft = WorkshopPresetDraft(loading = true, count = share.itemIds.size)
        mutableState.update { it.copy(draft = draft) }
        draftJob = scope.launch {
            try {
                val normalized = withContext(processing) { WorkshopShareCode.decode(WorkshopShareCode.encode(share)) }
                updateDraft(draft.key) { it.copy(loading = false, share = normalized, count = normalized.itemIds.size) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: WorkshopShareCodeException) {
                updateDraft(draft.key) { it.copy(loading = false, shareProblem = error.problem) }
            }
        }
    }

    fun rename(preset: WorkshopPreset) {
        if (preset.share.appId != appId || state.value.draft?.saving == true) return
        draftJob?.cancel()
        mutableState.update { it.copy(draft = WorkshopPresetDraft(replacingId = preset.id,
            name = preset.name, share = preset.share, count = preset.share.itemIds.size)) }
    }

    fun name(value: String) {
        val draft = state.value.draft?.takeIf { !it.saving } ?: return
        updateDraft(draft.key) { it.copy(name = value.take(MAX_PRESET_NAME), problem = null) }
    }

    fun save() {
        val draft = state.value.draft?.takeIf { !it.loading && !it.saving && it.share != null } ?: return
        updateDraft(draft.key) { it.copy(saving = true, problem = null) }
        draftJob = scope.launch {
            try {
                val items = repository.save(appId, draft.name, requireNotNull(draft.share), draft.replacingId)
                libraryJob?.cancel()
                mutableState.update { it.copy(visible = true, library = WorkshopPresetLibrary(items), draft = null) }
                onSaved()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val problem = (error as? WorkshopPresetException)?.problem ?: WorkshopPresetProblem.STORAGE
                updateDraft(draft.key) { it.copy(saving = false, problem = problem) }
            }
        }
    }

    fun dismissDraft() {
        if (state.value.draft?.saving == true) return
        draftJob?.cancel()
        mutableState.update { it.copy(draft = null) }
    }

    fun delete(preset: WorkshopPreset) {
        if (preset.share.appId != appId || state.value.library.loading) return
        libraryJob?.cancel()
        mutableState.update { it.copy(library = it.library.copy(loading = true, problem = null)) }
        libraryJob = scope.launch {
            try {
                val items = repository.delete(appId, preset.id)
                mutableState.update { it.copy(library = WorkshopPresetLibrary(items)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(library = it.library.copy(loading = false, problem = WorkshopPresetProblem.STORAGE)) }
            }
        }
    }

    fun preview(preset: WorkshopPreset) {
        if (preset.share.appId != appId || !canReadSubscriptions()) return
        previewJob?.cancel()
        val version = ++previewGeneration
        mutableState.update { it.copy(preview = WorkshopPresetPreview(preset)) }
        previewJob = scope.launch {
            fun update(change: (WorkshopPresetPreview) -> WorkshopPresetPreview) {
                if (version == previewGeneration) mutableState.update { current -> current.copy(preview = current.preview?.let(change)) }
            }
            try {
                val snapshot = readAllWorkshopSubscriptions(appId, read) { count -> update { it.copy(collected = count) } }
                val plan = withContext(processing) {
                    WorkshopPresetSwitch(preset.name, WorkshopShareCode.encode(preset.share), snapshot.ids).also { it.validate(appId) }
                }
                val currentIds = snapshot.ids.toHashSet()
                val titles = snapshot.items.mapValues { it.value.title }.toMutableMap()
                val unavailable = mutableListOf<String>()
                for (chunk in plan.share.itemIds.chunked(20)) {
                    val inspected = inspect(chunk)
                    if (version != previewGeneration) return@launch
                    if (inspected.any { it.appId != appId || it.id !in chunk } || inspected.map { it.id }.distinct().size != inspected.size) {
                        throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
                    }
                    val items = inspected.associateBy { it.id }
                    for (id in chunk) {
                        val item = items[id]
                        if (item != null) {
                            titles[id] = item.title
                            if (item.subscribed == null) throw WorkshopException(WorkshopFailure.UNCONFIRMED)
                            if (item.subscribed != (id in currentIds)) throw WorkshopException(WorkshopFailure.SUBSCRIPTIONS_CHANGED)
                        }
                        if (id !in currentIds && item?.canSubscribeHere != true) unavailable += id
                    }
                    if (chunk.last() != plan.share.itemIds.last()) delay(350)
                }
                val changes = plan.addIds.map { WorkshopPresetChange(it, titles[it] ?: "#$it", true) } +
                    plan.removeIds.map { WorkshopPresetChange(it, titles[it] ?: "#$it", false) }
                update { it.copy(loading = false, plan = plan, changes = changes, unavailableIds = unavailable) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: WorkshopShareCodeException) { update { it.copy(loading = false, problem = error.problem) } }
            catch (error: Exception) { update { it.copy(loading = false, failure = error.workshopFailure()) } }
        }
    }

    fun dismissPreview() {
        previewJob?.cancel()
        previewGeneration++
        mutableState.update { it.copy(preview = null) }
    }

    fun stopReading() {
        dismissPreview()
        if (state.value.draft?.loading == true) dismissDraft()
    }

    private fun updateDraft(key: String, change: (WorkshopPresetDraft) -> WorkshopPresetDraft) {
        mutableState.update { current -> current.copy(draft = current.draft?.let { if (it.key == key) change(it) else it }) }
    }
}
