package takagi.ru.monica.steam.workshop

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import takagi.ru.monica.steam.data.SteamAccount

@OptIn(ExperimentalCoroutinesApi::class)
class SteamWorkshopManagementTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun model(gateway: Fake = Fake(), account: SteamAccount? = SteamWorkshopViewModelTest.account()) =
        SteamWorkshopViewModel(570, account, gateway, processingDispatcher = dispatcher)

    @Test fun importReadsEveryChunkButWritesOnlyExplicitlySelectedItems() = runTest(scheduler) {
        val gateway = Fake()
        val vm = model(gateway)
        vm.openImport(WorkshopShare(570, (1..21).map { it.toString() }))
        advanceUntilIdle()
        assertEquals(listOf(20, 1), gateway.reads.map { it.size })
        assertTrue(gateway.writes.isEmpty())
        vm.selectImported(false)
        vm.toggleImported("21")
        vm.subscribeImported(false, true)
        advanceUntilIdle()
        assertEquals(listOf("21"), gateway.writes.map { it.first })
        assertTrue(gateway.writes.single().third)
        assertEquals(1, vm.state.value.bulk?.updated)
    }

    @Test fun bulkSkipsExistingSubscriptionsAndUnsupportedItems() = runTest(scheduler) {
        val gateway = Fake().apply { inspect = { ids -> ids.map { id -> item(id).copy(
            subscribed = id == "1", fileType = if (id == "2") 2 else 0) } } }
        val vm = model(gateway)
        vm.start(); advanceUntilIdle(); vm.selectLoaded()
        vm.manageSelected(true); vm.manageSelected(true)
        advanceUntilIdle()
        assertEquals(listOf("3"), gateway.writes.map { it.first })
        assertEquals(1, vm.state.value.bulk?.unchanged)
        assertEquals(1, vm.state.value.bulk?.failed)
        assertEquals(1, vm.state.value.bulk?.updated)
        assertEquals(setOf("2"), vm.state.value.selectedIds)
    }

    @Test fun mySubscriptionsCanBeSharedAndRemovedButCannotStartAnotherSubscriptionQueue() = runTest(scheduler) {
        val gateway = Fake().apply {
            browse = { _, page -> WorkshopBatch((1..3).map { item(it.toString()).copy(subscribed = true) }, page, false, 3) }
            inspect = { ids -> ids.map { item(it).copy(subscribed = true) } }
        }
        val vm = model(gateway)
        vm.query(WorkshopQuery(subscribedOnly = true, sort = WorkshopSort.UPDATED))
        advanceUntilIdle(); vm.selectLoaded()
        vm.manageSelected(true); advanceUntilIdle()
        assertNull(vm.state.value.bulk)
        assertTrue(gateway.reads.isEmpty())
        assertTrue(gateway.writes.isEmpty())

        vm.exportSelected(); advanceUntilIdle()
        assertEquals(WorkshopShare(570, listOf("1", "2", "3")),
            WorkshopShareCode.decode(requireNotNull(vm.state.value.export?.code)))
        vm.manageSelected(false); advanceUntilIdle()
        assertEquals(listOf("1", "2", "3"), gateway.writes.map { it.first })
        assertTrue(gateway.writes.all { !it.second && !it.third })
    }

    @Test fun browseSubscribeQueuesOnlyEligibleSelectionsAndRechecksUnknownStates() = runTest(scheduler) {
        val rows = listOf(item("1").copy(subscribed = true), item("2"),
            item("3").copy(fileType = 2), item("4").copy(subscribed = null))
        val gateway = Fake().apply {
            browse = { _, page -> WorkshopBatch(rows, page, false, rows.size) }
            // Item 4 was already subscribed outside the app. The worker still reads it first.
            inspect = { ids -> ids.map { item(it).copy(subscribed = it == "4") } }
        }
        val vm = model(gateway)
        vm.start(); advanceUntilIdle(); vm.selectLoaded()
        vm.manageSelected(true); advanceUntilIdle()
        assertEquals(listOf("2", "4"), vm.state.value.bulk?.ids)
        assertEquals(listOf(listOf("2", "4")), gateway.reads)
        assertEquals(listOf(Triple("2", true, false)), gateway.writes)
        assertEquals(1, vm.state.value.bulk?.unchanged)
        assertEquals(setOf("1", "3"), vm.state.value.selectedIds)
    }

    @Test fun browseUnsubscribeSkipsKnownUnsubscribedSelectionsAndChecksUnknownStates() = runTest(scheduler) {
        val rows = listOf(item("1").copy(subscribed = true), item("2"), item("3").copy(subscribed = null))
        val gateway = Fake().apply {
            browse = { _, page -> WorkshopBatch(rows, page, false, rows.size) }
            inspect = { ids -> ids.map { item(it).copy(subscribed = it == "1") } }
        }
        val vm = model(gateway)
        vm.start(); advanceUntilIdle(); vm.selectLoaded()
        vm.manageSelected(false); advanceUntilIdle()
        assertEquals(listOf("1", "3"), vm.state.value.bulk?.ids)
        assertEquals(listOf(listOf("1", "3")), gateway.reads)
        assertEquals(listOf(Triple("1", false, false)), gateway.writes)
        assertEquals(1, vm.state.value.bulk?.unchanged)
    }

    @Test fun allSelectionsAlreadyMatchingTheRequestedStateDoNotCreateAQueue() = runTest(scheduler) {
        for (subscribed in listOf(false, true)) {
            val gateway = Fake().apply {
                browse = { _, page -> WorkshopBatch(listOf(item("1").copy(subscribed = subscribed)), page, false, 1) }
            }
            val vm = model(gateway)
            vm.start(); advanceUntilIdle(); vm.selectLoaded()
            vm.manageSelected(subscribed); advanceUntilIdle()
            assertNull(vm.state.value.bulk)
            assertTrue(gateway.reads.isEmpty())
            assertTrue(gateway.writes.isEmpty())
        }
    }

    @Test fun stoppingFinishesCurrentWriteAndDoesNotStartTheNextOne() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val gateway = Fake().apply { mutate = { _, _, _ -> gate.await() } }
        val vm = model(gateway)
        vm.start(); advanceUntilIdle(); vm.selectLoaded(); vm.manageSelected(true); runCurrent()
        vm.stopBulk()
        assertTrue(vm.state.value.bulk?.running == true)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("1"), gateway.writes.map { it.first })
        assertEquals(1, vm.state.value.bulk?.updated)
        assertEquals(2, vm.state.value.bulk?.remaining)
        assertFalse(vm.state.value.bulk?.running == true)
    }

    @Test fun leavingImportWhileSubscribingKeepsTheQueueRunning() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val gateway = Fake().apply { mutate = { _, _, _ -> gate.await() } }
        val vm = model(gateway)
        vm.openImport(WorkshopShare(570, listOf("1", "2", "3")))
        advanceUntilIdle()
        vm.subscribeImported(true, false)
        runCurrent()
        vm.closeImport()
        vm.stopBrowsing()
        assertNull("Leaving the preview must not require stopping the import", vm.state.value.imported)
        assertTrue(vm.state.value.bulk?.running == true)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("1", "2", "3"), gateway.writes.map { it.first })
    }

    @Test fun destroyingPageViewModelCannotCancelRequestedBulkWrites() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val gateway = Fake().apply { mutate = { _, _, _ -> gate.await() } }
        val vm = model(gateway)
        val owner = ViewModelStore().apply { put("workshop", vm) }
        vm.start(); advanceUntilIdle(); vm.selectLoaded(); vm.manageSelected(true); runCurrent()
        owner.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("1", "2", "3"), gateway.writes.map { it.first })
    }

    @Test fun hidingProgressKeepsItReopenableAndDoesNotRequestStop() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val gateway = Fake().apply { mutate = { _, _, _ -> gate.await() } }
        val vm = model(gateway)
        vm.start(); advanceUntilIdle(); vm.selectLoaded(); vm.manageSelected(true); runCurrent()
        assertTrue(vm.state.value.bulkDialogVisible)
        vm.dismissBulk()
        assertFalse(vm.state.value.bulkDialogVisible)
        assertTrue(vm.state.value.bulk?.running == true)
        assertFalse(vm.state.value.bulk?.stopRequested == true)
        vm.showBulk()
        assertTrue(vm.state.value.bulkDialogVisible)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(3, vm.state.value.bulk?.updated)
    }

    @Test fun recreatedPageObservesTheSameQueueWithoutResubmittingIt() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val gateway = Fake().apply { mutate = { _, _, _ -> gate.await() } }
        val account = SteamWorkshopViewModelTest.account()
        val controller = InMemoryWorkshopBulkController(570, account, gateway)
        val first = SteamWorkshopViewModel(570, account, gateway, processingDispatcher = dispatcher, bulkController = controller)
        val owner = ViewModelStore().apply { put("workshop", first) }
        first.start(); advanceUntilIdle(); first.selectLoaded(); first.manageSelected(true); runCurrent()
        val requestId = first.state.value.bulk?.requestId
        owner.clear()
        val second = SteamWorkshopViewModel(570, account, gateway, processingDispatcher = dispatcher, bulkController = controller)
        second.start(); runCurrent()
        assertEquals(requestId, second.state.value.bulk?.requestId)
        assertTrue(second.state.value.bulk?.running == true)
        second.selectLoaded(); second.manageSelected(true)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("1", "2", "3"), gateway.writes.map { it.first })
        assertEquals(3, second.state.value.bulk?.updated)
    }

    @Test fun ambiguousWriteStopsAndRetryReconcilesBeforeSendingAnotherWrite() = runTest(scheduler) {
        var alreadySubscribed = false
        val gateway = Fake().apply {
            inspect = { ids -> ids.map { item(it).copy(subscribed = it == "1" && alreadySubscribed) } }
            mutate = { id, _, _ -> if (id == "1") { alreadySubscribed = true; throw java.io.IOException() } }
        }
        val vm = model(gateway)
        vm.start(); advanceUntilIdle(); vm.selectLoaded(); vm.manageSelected(true); advanceUntilIdle()
        assertEquals(listOf("1"), gateway.writes.map { it.first })
        assertEquals(WorkshopFailure.NETWORK, vm.state.value.bulk?.failure)
        assertNull(vm.state.value.items.first().subscribed)
        vm.retryBulk(); advanceUntilIdle()
        assertEquals(listOf("1", "2", "3"), gateway.writes.map { it.first })
        assertEquals(1, vm.state.value.bulk?.unchanged)
        assertEquals(2, vm.state.value.bulk?.updated)
    }

    @Test fun changingAccountsCannotMoveAnOngoingQueue() = runTest(scheduler) {
        val accounts = mutableListOf<Long>()
        val gateway = Fake().apply { accountRead = { accounts += it.id } }
        val vm = model(gateway)
        vm.start(); advanceUntilIdle(); vm.selectLoaded(); vm.manageSelected(true)
        vm.updateAccount(SteamWorkshopViewModelTest.account().copy(id = 999))
        advanceUntilIdle()
        assertTrue(accounts.isNotEmpty() && accounts.all { it == 1L })
    }

    @Test fun shareAllIgnoresVisibleFiltersAndIncludesUnavailableIdsAcrossPages() = runTest(scheduler) {
        val pages = mutableListOf<Int>()
        val gateway = Fake().apply { browse = { query, page ->
            assertTrue(query.search.isEmpty() && query.tags.isEmpty() && query.subscribedOnly)
            pages += page
            WorkshopBatch(listOf(item(page.toString())), page, page == 1, 3,
                subscriptionIds = if (page == 1) listOf("1", "9") else listOf("2"))
        } }
        val vm = model(gateway)
        vm.exportSubscriptions(); advanceUntilIdle()
        assertEquals(listOf(1, 2), pages)
        assertEquals(WorkshopShare(570, listOf("1", "9", "2")), WorkshopShareCode.decode(requireNotNull(vm.state.value.export?.code)))
    }

    @Test fun failedExportCannotShareOnlyItsFirstPage() = runTest(scheduler) {
        val gateway = Fake().apply { browse = { _, page ->
            if (page == 2) throw java.io.IOException()
            WorkshopBatch(listOf(item("1")), page, true, 40)
        } }
        val vm = model(gateway)
        vm.exportSubscriptions(); advanceUntilIdle()
        assertNull(vm.state.value.export?.code)
        assertEquals(WorkshopFailure.NETWORK, vm.state.value.export?.failure)
    }

    @Test fun dismissedExportCannotBePublishedByALateResponse() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val gateway = Fake().apply { browse = { _, page ->
            withContext(NonCancellable) { gate.await() }
            WorkshopBatch(listOf(item("1")), page, false, 1)
        } }
        val vm = model(gateway)
        vm.exportSubscriptions(); runCurrent(); vm.dismissExport(); gate.complete(Unit); advanceUntilIdle()
        assertNull(vm.state.value.export)
    }

    @Test fun oldImportAndCrossGameMetadataCannotReplaceTheActivePreview() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val gateway = Fake().apply { inspect = { ids ->
            if (ids.single() == "1") withContext(NonCancellable) { gate.await() }
            ids.map(::item)
        } }
        val vm = model(gateway)
        vm.openImport(WorkshopShare(570, listOf("1"))); runCurrent()
        vm.openImport(WorkshopShare(570, listOf("2"))); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("2"), vm.state.value.imported?.entries?.map { it.id })
        gateway.inspect = { ids -> ids.map { item(it).copy(appId = 550) } }
        vm.retryImport(); advanceUntilIdle()
        assertEquals(WorkshopFailure.INVALID_RESPONSE, vm.state.value.imported?.failure)
        vm.subscribeImported(true, false); advanceUntilIdle()
        assertTrue(gateway.writes.isEmpty())
    }

    @Test fun alreadySubscribedUnavailableAndUnknownItemsCannotBeSelectedOnImport() = runTest(scheduler) {
        val gateway = Fake().apply { inspect = { listOf(item("1").copy(subscribed = true), item("2").copy(subscribed = null)) } }
        val vm = model(gateway)
        vm.openImport(WorkshopShare(570, listOf("1", "2", "3"))); advanceUntilIdle()
        vm.selectImported(true); vm.subscribeImported(true, false); advanceUntilIdle()
        assertTrue(vm.state.value.imported?.selectedIds?.isEmpty() == true)
        assertTrue(gateway.writes.isEmpty())
    }

    @Test fun guestCannotStartImportWrites() = runTest(scheduler) {
        val gateway = Fake()
        val vm = model(gateway, null)
        vm.openImport(WorkshopShare(570, listOf("1"))); advanceUntilIdle()
        assertEquals(WorkshopFailure.LOGIN, vm.state.value.imported?.failure)
        vm.subscribeImported(true, false); advanceUntilIdle()
        assertTrue(gateway.writes.isEmpty())
    }

    private class Fake : SteamWorkshopGateway {
        val reads = mutableListOf<List<String>>()
        val writes = mutableListOf<Triple<String, Boolean, Boolean>>()
        var accountRead: (SteamAccount) -> Unit = {}
        var browse: suspend (WorkshopQuery, Int) -> WorkshopBatch = { _, page -> WorkshopBatch((1..3).map { item(it.toString()) }, page, false, 3) }
        var inspect: suspend (List<String>) -> List<WorkshopItem> = { it.map(::item) }
        var mutate: suspend (String, Boolean, Boolean) -> Unit = { _, _, _ -> }
        override suspend fun supportsWorkshop(appId: Int) = true
        override suspend fun browse(account: SteamAccount?, appId: Int, query: WorkshopQuery, page: Int) = browse(query, page)
        override suspend fun details(account: SteamAccount, appId: Int, id: String) = item(id)
        override suspend fun inspectItems(account: SteamAccount, appId: Int, ids: List<String>): List<WorkshopItem> {
            accountRead(account); reads += ids
            return inspect(ids)
        }
        override suspend fun setSubscription(account: SteamAccount, item: WorkshopItem, subscribe: Boolean, dependencies: Boolean) {
            accountRead(account)
            writes += Triple(item.id, subscribe, dependencies)
            mutate(item.id, subscribe, dependencies)
        }
    }
    companion object {
        private fun item(id: String) = WorkshopItem(id, 570, "Mod $id", canSubscribe = true, subscribed = false)
    }
}
