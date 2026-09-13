package takagi.ru.monica.steam.workshop

import java.util.UUID
import kotlinx.serialization.Serializable

internal val WorkshopItem.canSubscribeHere: Boolean
    get() = canSubscribe && fileType == 0 && !banned

@Serializable
internal enum class WorkshopBulkOutcome { UPDATED, UNCHANGED, UNAVAILABLE, FAILED }
@Serializable
internal data class WorkshopBulkResult(
    val id: String, val title: String, val outcome: WorkshopBulkOutcome,
    val failure: WorkshopFailure? = null
)
@Serializable
internal enum class WorkshopBulkPhase { CHECKING, ITEMS, VERIFYING }
@Serializable
internal data class WorkshopBulkState(
    val ids: List<String>, val subscribe: Boolean, val dependencies: Boolean,
    val running: Boolean = true, val stopRequested: Boolean = false,
    val currentTitle: String = "", val results: List<WorkshopBulkResult> = emptyList(),
    val failure: WorkshopFailure? = null,
    val requestId: String = UUID.randomUUID().toString(),
    val queued: Boolean = false,
    val preset: WorkshopPresetSwitch? = null,
    val phase: WorkshopBulkPhase = WorkshopBulkPhase.ITEMS,
    val presetVerified: Boolean = false
) {
    val remaining: Int get() = ids.size - results.size
    val updated: Int get() = results.count { it.outcome == WorkshopBulkOutcome.UPDATED }
    val unchanged: Int get() = results.count { it.outcome == WorkshopBulkOutcome.UNCHANGED }
    val failed: Int get() = results.count { it.outcome == WorkshopBulkOutcome.FAILED || it.outcome == WorkshopBulkOutcome.UNAVAILABLE }
    fun subscribes(id: String): Boolean = preset?.let { id in it.targetIds } ?: subscribe
    val canRetry: Boolean get() = !running && (retryIds.isNotEmpty() || (preset != null && !presetVerified))
    val retryIds: List<String> get() {
        val done = results.filter { it.outcome in listOf(WorkshopBulkOutcome.UPDATED, WorkshopBulkOutcome.UNCHANGED) }.map { it.id }.toSet()
        return ids.filterNot { it in done }
    }
}
internal data class WorkshopImportEntry(val id: String, val item: WorkshopItem? = null, val loaded: Boolean = false) {
    val selectable: Boolean get() = item?.let { it.canSubscribeHere && it.subscribed == false } == true
}
internal data class WorkshopImportState(
    val share: WorkshopShare,
    val entries: List<WorkshopImportEntry> = share.itemIds.map { WorkshopImportEntry(it) },
    val selectedIds: Set<String> = share.itemIds.toSet(),
    val loading: Boolean = true, val failure: WorkshopFailure? = null
) {
    val selectableIds: Set<String> get() = entries.filter { it.selectable }.mapTo(linkedSetOf()) { it.id }
}
internal data class WorkshopExportState(
    val loading: Boolean = true, val count: Int = 0, val code: String? = null,
    val problem: WorkshopShareProblem? = null, val failure: WorkshopFailure? = null,
    val share: WorkshopShare? = null
)
