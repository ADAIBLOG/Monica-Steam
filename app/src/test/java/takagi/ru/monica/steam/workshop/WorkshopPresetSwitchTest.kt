package takagi.ru.monica.steam.workshop

import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import takagi.ru.monica.steam.data.SteamAccount
import takagi.ru.monica.steam.session.domain.SteamAccountSessionResolver

@OptIn(ExperimentalCoroutinesApi::class)
class WorkshopPresetSwitchTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = SteamWorkshopViewModelTest.account()
    private fun plan(ids: List<String> = listOf("2", "3"), previous: List<String> = listOf("1", "2")) =
        WorkshopPresetSwitch("Realistic MODs", WorkshopShareCode.encode(WorkshopShare(570, ids)), previous)
    private fun request(preset: WorkshopPresetSwitch = plan()) = WorkshopBulkState(preset.operationIds, true, false, preset = preset)
    private fun task(state: WorkshopBulkState) = WorkshopBulkTask(
        WorkshopBulkTarget("room|${account.id}|${account.steamId}", account.id, account.steamId, 570), "Dota 2", "Test account", state)

    @Test fun switchingPreservesCommonItemsAndAddsBeforeRemoving() = runTest {
        val gateway = WorkshopPresetTestGateway()
        val result = WorkshopBulkRunner(570, account, gateway).run(request(), { false }) { }
        assertEquals(listOf("3" to true, "1" to false), gateway.writes)
        assertEquals(setOf("2", "3"), gateway.subscribed)
        assertEquals(WorkshopBulkOutcome.UNCHANGED, result.results.single { it.id == "2" }.outcome)
        assertFalse(result.subscribes("1"))
        assertTrue(result.subscribes("3"))
        assertTrue(result.presetVerified)
        assertFalse(result.canRetry)
        assertTrue(gateway.dependencies.none { it })
    }

    @Test fun unavailableTargetStopsWithoutRemovingAnyOldSubscriptions() = runTest {
        val gateway = WorkshopPresetTestGateway().apply { unavailable += "4" }
        val result = WorkshopBulkRunner(570, account, gateway).run(request(plan(listOf("2", "3", "4"))), { false }) { }
        assertEquals(listOf("3" to true), gateway.writes)
        assertTrue(gateway.subscribed.containsAll(listOf("1", "2")))
        assertEquals(WorkshopFailure.UNAVAILABLE, result.failure)
        assertFalse(result.presetVerified)
        assertTrue(result.canRetry)
    }

    @Test fun retryReconcilesAnAmbiguousAdditionAndRetainsTheRemovalDirection() = runTest {
        val gateway = WorkshopPresetTestGateway().apply { afterWrite = { id, _ -> if (id == "3") throw IOException() } }
        val controller = InMemoryWorkshopBulkController(570, account, gateway, scope = this)
        controller.startPreset(plan()); advanceUntilIdle()
        assertEquals(WorkshopFailure.NETWORK, controller.state.value?.failure)
        assertEquals(listOf("3" to true), gateway.writes)
        assertTrue("Old mod must be kept while addition is unconfirmed", "1" in gateway.subscribed)
        gateway.afterWrite = { _, _ -> }
        controller.retry(); advanceUntilIdle()
        assertEquals(listOf("3" to true, "1" to false), gateway.writes)
        assertTrue(controller.state.value?.presetVerified == true)
    }

    @Test fun acceptedRemovalWithAnAmbiguousReplyIsNotSentTwiceOnRetry() = runTest {
        val gateway = WorkshopPresetTestGateway().apply { afterWrite = { _, subscribe -> if (!subscribe) throw IOException() } }
        val controller = InMemoryWorkshopBulkController(570, account, gateway, scope = this)
        controller.startPreset(plan()); advanceUntilIdle()
        assertFalse(controller.state.value?.presetVerified == true)
        assertEquals(setOf("2", "3"), gateway.subscribed)
        gateway.afterWrite = { _, _ -> }
        controller.retry(); advanceUntilIdle()
        assertEquals(listOf("3" to true, "1" to false), gateway.writes)
        assertTrue(controller.state.value?.presetVerified == true)
    }

    @Test fun processRestartDuringRemovalRestoresTheOriginalMixedTask() = runTest {
        val root = temporary.newFolder()
        val store = WorkshopBulkTaskStore(root)
        val gate = CompletableDeferred<Unit>()
        val gateway = WorkshopPresetTestGateway().apply { afterWrite = { _, subscribe -> if (!subscribe) gate.await() } }
        var checkpoint = task(request())
        val job = launch {
            WorkshopBulkRunner(570, account, gateway).run(checkpoint.state, { false }) {
                checkpoint = checkpoint.copy(state = it)
                store.write(checkpoint)
            }
        }
        advanceUntilIdle()
        assertEquals(listOf("3" to true, "1" to false), gateway.writes)
        job.cancelAndJoin()
        val restored = requireNotNull(WorkshopBulkTaskStore(root).read(checkpoint.target.key))
        assertEquals(2, restored.schema)
        assertEquals(plan(), restored.state.preset)
        assertFalse(restored.state.results.any { it.id == "1" })
        val result = WorkshopBulkRunner(570, account, gateway).run(restored.state, { false }) { }
        assertTrue(result.presetVerified)
        assertEquals(listOf("3" to true, "1" to false), gateway.writes)
        assertEquals(WorkshopBulkOutcome.UNCHANGED, result.results.single { it.id == "1" }.outcome)
    }

    @Test fun oldSchemaStillLoadsAndMixedTasksCannotBeMisreadAsOldSubscribeOnlyTasks() = runTest {
        val root = temporary.newFolder()
        val store = WorkshopBulkTaskStore(root)
        val legacy = task(WorkshopBulkState(listOf("1"), false, false))
        store.write(legacy)
        val file = File(root, "${legacy.target.key}.json")
        val json = Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap()
        json["state"] = JsonObject(json.getValue("state").jsonObject.filterKeys { it !in setOf("preset", "phase", "presetVerified") })
        file.writeText(JsonObject(json).toString())
        assertEquals(legacy, store.read(legacy.target.key))
        assertThrows(IllegalArgumentException::class.java) { store.write(task(request()).copy(schema = 1)) }
        assertThrows(IllegalArgumentException::class.java) { store.write(task(request().copy(ids = listOf("1", "2", "3")))) }
    }

    @Test fun maximumSizeReplacementCanCheckpointBothSetsAndUnicodeTitles() {
        val preset = plan((5001..10000).map(Int::toString), (1..5000).map(Int::toString))
        val original = task(request(preset).copy(running = false, presetVerified = true,
            results = preset.operationIds.map { WorkshopBulkResult(it, "模".repeat(256), WorkshopBulkOutcome.UPDATED) }))
        val root = temporary.newFolder()
        WorkshopBulkTaskStore(root).write(original)
        val restored = requireNotNull(WorkshopBulkTaskStore(root).read(original.target.key))
        assertEquals(10_000, restored.state.results.size)
        assertTrue(restored.state.presetVerified)
        assertEquals(preset, restored.state.preset)
    }

    @Test fun externallyAddedItemsAfterPreviewStopTheTaskBeforeAnyWrites() = runTest {
        val gateway = WorkshopPresetTestGateway().apply { subscribed += "9" }
        val result = WorkshopBulkRunner(570, account, gateway).run(request(), { false }) { }
        assertEquals(WorkshopFailure.SUBSCRIPTIONS_CHANGED, result.failure)
        assertTrue(gateway.writes.isEmpty())
        assertTrue("9" in gateway.subscribed)
    }

    @Test fun changedSubscriptionsBetweenPhasesCannotWidenApprovedRemovals() = runTest {
        val gateway = WorkshopPresetTestGateway().apply { afterWrite = { id, _ -> if (id == "3") subscribed += "9" } }
        val result = WorkshopBulkRunner(570, account, gateway).run(request(), { false }) { }
        assertEquals(WorkshopFailure.SUBSCRIPTIONS_CHANGED, result.failure)
        assertEquals(listOf("3" to true), gateway.writes)
        assertTrue(gateway.subscribed.containsAll(listOf("1", "2", "3", "9")))
    }

    @Test fun aFailedFinalReadRemainsRetryableEvenAfterAllOperationsFinished() = runTest {
        val gateway = WorkshopPresetTestGateway().apply { beforeBrowse = { _, count -> if (count == 3) throw IOException() } }
        val controller = InMemoryWorkshopBulkController(570, account, gateway, scope = this)
        controller.startPreset(plan()); advanceUntilIdle()
        val result = requireNotNull(controller.state.value)
        assertEquals(0, result.remaining)
        assertTrue(result.retryIds.isEmpty())
        assertEquals(WorkshopFailure.NETWORK, result.failure)
        assertFalse(result.presetVerified)
        assertTrue(result.canRetry)
        controller.retry(); advanceUntilIdle()
        assertTrue(controller.state.value?.presetVerified == true)
        assertEquals(listOf("3" to true, "1" to false), gateway.writes)
    }

    @Test fun failedOrIncompleteSubscriptionPagesCannotStartAWritingPhase() = runTest {
        for (truncate in listOf(false, true)) {
            val gateway = WorkshopPresetTestGateway().apply {
                subscribed.clear(); subscribed += (1..31).map(Int::toString)
                if (truncate) overrideBrowse = { page -> WorkshopBatch(listOf(item("1")), page, false, 31) }
                else beforeBrowse = { page, _ -> if (page == 2) throw IOException() }
            }
            val result = WorkshopBulkRunner(570, account, gateway).run(request(plan(listOf("32"), gateway.subscribed.toList())), { false }) { }
            assertFalse(result.presetVerified)
            assertNotNull(result.failure)
            assertTrue(gateway.writes.isEmpty())
        }
    }

    @Test fun stoppingDuringAnAdditionKeepsOldItemsAndCanResumeLater() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = WorkshopPresetTestGateway().apply { afterWrite = { id, _ -> if (id == "3") gate.await() } }
        val controller = InMemoryWorkshopBulkController(570, account, gateway, scope = this)
        controller.startPreset(plan()); advanceUntilIdle()
        controller.stop(); gate.complete(Unit); advanceUntilIdle()
        assertFalse(controller.state.value?.running == true)
        assertFalse(controller.state.value?.presetVerified == true)
        assertEquals(listOf("3" to true), gateway.writes)
        controller.retry(); advanceUntilIdle()
        assertTrue(controller.state.value?.presetVerified == true)
    }

    @Test fun disappearedTargetsInARestoredCheckpointPreventRemovingOldItems() = runTest {
        val gateway = WorkshopPresetTestGateway()
        val initial = request().copy(results = listOf(
            WorkshopBulkResult("2", "Common", WorkshopBulkOutcome.UNCHANGED),
            WorkshopBulkResult("3", "New mod", WorkshopBulkOutcome.UPDATED)))
        val result = WorkshopBulkRunner(570, account, gateway).run(initial, { false }) { }
        assertEquals(WorkshopFailure.SUBSCRIPTIONS_CHANGED, result.failure)
        assertTrue(gateway.writes.isEmpty())
    }

    @Test fun deletedOldItemsCanBeRemovedUsingTheFullAuthenticatedSubscriptionSnapshot() = runTest {
        val gateway = WorkshopPresetTestGateway().apply { unavailable += "1" }
        val result = WorkshopBulkRunner(570, account, gateway).run(request(), { false }) { }
        assertTrue(result.presetVerified)
        assertEquals(listOf("3" to true, "1" to false), gateway.writes)
    }

    @Test fun presetReadsAndWritesStayOnTheOriginalAccount() = runTest {
        val gateway = WorkshopPresetTestGateway()
        val result = WorkshopBulkRunner(570, account, gateway,
            SteamAccountSessionResolver { current, _ -> current.copy(id = 999) }).run(request(), { false }) { }
        assertEquals(WorkshopFailure.LOGIN, result.failure)
        assertEquals(0, gateway.browseCount)
        assertTrue(gateway.writes.isEmpty())
    }

    @Test fun foreignGameMetadataAndStoppedRequestsSendNoWrites() = runTest {
        val gateway = WorkshopPresetTestGateway().apply { inspectAppId = 550 }
        val result = WorkshopBulkRunner(570, account, gateway).run(request(), { false }) { }
        assertEquals(WorkshopFailure.INVALID_RESPONSE, result.failure)
        assertTrue(gateway.writes.isEmpty())
        val untouched = WorkshopPresetTestGateway()
        WorkshopBulkRunner(570, account, untouched).run(request().copy(stopRequested = true), { false }) { }
        assertEquals(0, untouched.browseCount)
        assertTrue(untouched.writes.isEmpty())
    }
}

internal class WorkshopPresetTestGateway : SteamWorkshopGateway {
    val subscribed = linkedSetOf("1", "2")
    val unavailable = hashSetOf<String>()
    val writes = mutableListOf<Pair<String, Boolean>>()
    val dependencies = mutableListOf<Boolean>()
    val accounts = mutableListOf<Long>()
    val queries = mutableListOf<WorkshopQuery>()
    var browseCount = 0
    var inspectAppId = 570
    var beforeBrowse: suspend (Int, Int) -> Unit = { _, _ -> }
    var overrideBrowse: (suspend (Int) -> WorkshopBatch)? = null
    var afterWrite: suspend (String, Boolean) -> Unit = { _, _ -> }
    fun item(id: String, appId: Int = 570) = WorkshopItem(id, appId, "Mod $id", canSubscribe = true, subscribed = id in subscribed)
    override suspend fun supportsWorkshop(appId: Int) = true
    override suspend fun browse(account: SteamAccount?, appId: Int, query: WorkshopQuery, page: Int): WorkshopBatch {
        account?.let { accounts += it.id }
        queries += query
        browseCount++
        beforeBrowse(page, browseCount)
        overrideBrowse?.let { return it(page) }
        val all = if (query.subscribedOnly) subscribed.toList() else (1..4).map(Int::toString)
        val ids = all.drop((page - 1) * 30).take(30)
        return WorkshopBatch(ids.filterNot { it in unavailable }.map { item(it, appId) }, page, page * 30 < all.size,
            all.size, subscriptionIds = ids)
    }
    override suspend fun details(account: SteamAccount, appId: Int, id: String) = item(id, appId)
    override suspend fun inspectItems(account: SteamAccount, appId: Int, ids: List<String>): List<WorkshopItem> {
        accounts += account.id
        return ids.filterNot { it in unavailable }.map { item(it, inspectAppId) }
    }
    override suspend fun setSubscription(account: SteamAccount, item: WorkshopItem, subscribe: Boolean, dependencies: Boolean) {
        require(item.appId == 570)
        accounts += account.id
        writes += item.id to subscribe
        this.dependencies += dependencies
        if (subscribe) subscribed += item.id else subscribed -= item.id
        afterWrite(item.id, subscribe)
    }
}
