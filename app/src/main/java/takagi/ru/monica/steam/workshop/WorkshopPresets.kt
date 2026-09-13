package takagi.ru.monica.steam.workshop

import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

internal data class WorkshopPreset(
    val id: String,
    val name: String,
    val share: WorkshopShare,
    val updatedAt: Long
)

internal enum class WorkshopPresetProblem { NAME, DUPLICATE_NAME, LIMIT, STORAGE, MISSING }
internal class WorkshopPresetException(val problem: WorkshopPresetProblem) : Exception(problem.name)

internal data class WorkshopPresetLibrary(
    val items: List<WorkshopPreset> = emptyList(),
    val loading: Boolean = false,
    val problem: WorkshopPresetProblem? = null
)

internal data class WorkshopPresetDraft(
    val key: String = UUID.randomUUID().toString(),
    val replacingId: String? = null,
    val name: String = "",
    val share: WorkshopShare? = null,
    val fromSubscriptions: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val count: Int = 0,
    val problem: WorkshopPresetProblem? = null,
    val shareProblem: WorkshopShareProblem? = null,
    val failure: WorkshopFailure? = null
)

/** A frozen plan: a later edit/deletion of a local preset cannot change an accepted task. */
@Serializable
internal data class WorkshopPresetSwitch(
    val name: String,
    val code: String,
    val previousIds: List<String>
) {
    val share: WorkshopShare by lazy { WorkshopShareCode.decode(code) }
    val targetIds: Set<String> by lazy { share.itemIds.toSet() }
    val addIds: List<String> by lazy {
        val previous = previousIds.toHashSet()
        share.itemIds.filterNot { it in previous }
    }
    val removeIds: List<String> by lazy { previousIds.filterNot { it in targetIds } }
    // Check every target first, including shared items, then remove only the approved old items.
    val operationIds: List<String> by lazy { share.itemIds + removeIds }
    val retained: Int get() = share.itemIds.size - addIds.size

    fun validate(appId: Int) {
        require(name.isNotBlank() && name.length <= MAX_PRESET_NAME)
        require(share.appId == appId)
        require(previousIds.size <= WorkshopShareCode.MAX_ITEMS && previousIds.distinct().size == previousIds.size)
        require(previousIds.all { WorkshopShareCode.validId(it) && it.toULong().toString() == it })
    }
}

internal data class WorkshopPresetChange(val id: String, val title: String, val subscribe: Boolean)
internal data class WorkshopPresetPreview(
    val preset: WorkshopPreset,
    val loading: Boolean = true,
    val collected: Int = 0,
    val plan: WorkshopPresetSwitch? = null,
    val changes: List<WorkshopPresetChange> = emptyList(),
    val unavailableIds: List<String> = emptyList(),
    val failure: WorkshopFailure? = null,
    val problem: WorkshopShareProblem? = null
) {
    val canApply: Boolean get() = !loading && plan != null && unavailableIds.isEmpty() && failure == null && problem == null
}

internal const val MAX_PRESET_NAME = 60
internal const val MAX_PRESETS_PER_GAME = 100

internal data class WorkshopSubscriptionSnapshot(val ids: List<String>, val items: Map<String, WorkshopItem>)

/** Never derive a replacement/removal set from filtered, truncated or shifting subscription pages. */
internal suspend fun readAllWorkshopSubscriptions(
    appId: Int,
    read: suspend (Int) -> WorkshopBatch,
    progress: suspend (Int) -> Unit = {}
): WorkshopSubscriptionSnapshot {
    val ids = linkedSetOf<String>()
    val items = linkedMapOf<String, WorkshopItem>()
    var expectedTotal: Int? = null
    var page = 1
    while (true) {
        val batch = read(page)
        if (batch.page != page || batch.total < 0 || batch.subscriptionIds.any { !WorkshopShareCode.validId(it) } ||
            batch.items.any { it.appId != appId || it.id !in batch.subscriptionIds }) {
            throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
        }
        if (batch.total > WorkshopShareCode.MAX_ITEMS ||
            page > WorkshopShareCode.MAX_ITEMS / SteamWorkshopProtocol.SUBSCRIPTION_PAGE_SIZE + 1) {
            throw WorkshopShareCodeException(WorkshopShareProblem.TOO_LARGE)
        }
        val incoming = batch.subscriptionIds.map { it.toULong().toString() }
        if ((expectedTotal != null && expectedTotal != batch.total) || incoming.distinct().size != incoming.size ||
            incoming.any { it in ids } || (batch.hasMore && incoming.isEmpty())) {
            throw WorkshopException(WorkshopFailure.SUBSCRIPTIONS_CHANGED)
        }
        expectedTotal = batch.total
        ids += incoming
        if (ids.size > WorkshopShareCode.MAX_ITEMS) throw WorkshopShareCodeException(WorkshopShareProblem.TOO_LARGE)
        batch.items.forEach { items[it.id] = it }
        progress(ids.size)
        if (!batch.hasMore) {
            if (ids.size != expectedTotal) throw WorkshopException(WorkshopFailure.SUBSCRIPTIONS_CHANGED)
            return WorkshopSubscriptionSnapshot(ids.toList(), items)
        }
        if (ids.size >= batch.total) throw WorkshopException(WorkshopFailure.SUBSCRIPTIONS_CHANGED)
        page++
        delay(350)
    }
}
