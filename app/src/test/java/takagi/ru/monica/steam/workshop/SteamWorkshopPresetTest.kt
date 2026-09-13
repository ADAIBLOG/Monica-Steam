package takagi.ru.monica.steam.workshop

import androidx.lifecycle.ViewModelStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import takagi.ru.monica.steam.session.domain.SteamAccountSessionResolver

@OptIn(ExperimentalCoroutinesApi::class)
class SteamWorkshopPresetTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val account = SteamWorkshopViewModelTest.account()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun preset(ids: List<String> = listOf("2", "3"), appId: Int = 570) =
        WorkshopPreset(UUID.randomUUID().toString(), "Realistic MODs", WorkshopShare(appId, ids), 1)
    private fun model(gateway: WorkshopPresetTestGateway = WorkshopPresetTestGateway(),
        repository: WorkshopPresetRepository = InMemoryWorkshopPresetRepository()) =
        SteamWorkshopViewModel(570, account, gateway, processingDispatcher = dispatcher, presetRepository = repository)

    @Test fun creationReadsEverySubscriptionPageAndIncludesUnavailableIdsWithoutFilters() = runTest(scheduler) {
        val gateway = WorkshopPresetTestGateway().apply {
            subscribed.clear(); subscribed += (1..31).map(Int::toString); unavailable += "9"
        }
        val repository = InMemoryWorkshopPresetRepository()
        val vm = model(gateway, repository)
        vm.query(WorkshopQuery(search = "No match", tags = setOf("Mod"), subscribedOnly = true))
        advanceUntilIdle()
        gateway.queries.clear()
        vm.presets.fromSubscriptions(); advanceUntilIdle()
        assertEquals(31, vm.presets.state.value.draft?.count)
        assertTrue(gateway.queries.all { it.search.isEmpty() && it.tags.isEmpty() && it.subscribedOnly })
        vm.presets.name("二次元 MOD"); vm.presets.save(); advanceUntilIdle()
        assertEquals((1..31).map(Int::toString), repository.load(570).single().share.itemIds)
        assertTrue(vm.presets.state.value.visible)
        assertNull(vm.presets.state.value.draft)
        assertTrue(gateway.writes.isEmpty())
        val second = model(gateway, repository)
        second.showPresets(); advanceUntilIdle()
        assertEquals("二次元 MOD", second.presets.state.value.library.items.single().name)
    }

    @Test fun bothSharedSelectionAndAnImportedWholeListCanBeSavedWithoutChangingSubscriptions() = runTest(scheduler) {
        val gateway = WorkshopPresetTestGateway()
        val repository = InMemoryWorkshopPresetRepository()
        val vm = model(gateway, repository)
        vm.start(); advanceUntilIdle(); vm.toggleSelection("1"); vm.toggleSelection("3")
        vm.exportSelected(); advanceUntilIdle(); vm.saveExportAsPreset(); advanceUntilIdle()
        vm.presets.name("Selection"); vm.presets.save(); advanceUntilIdle()
        assertEquals(listOf("1", "3"), repository.load(570).single().share.itemIds)
        vm.openImport(WorkshopShare(570, listOf("1", "2", "4"))); advanceUntilIdle()
        assertEquals(setOf("4"), vm.state.value.imported?.selectableIds)
        vm.saveImportAsPreset(); advanceUntilIdle(); vm.presets.name("Imported"); vm.presets.save(); advanceUntilIdle()
        assertEquals(listOf("1", "2", "4"), repository.load(570).single { it.name == "Imported" }.share.itemIds)
        assertTrue("Saving must include existing subscriptions without writing them", gateway.writes.isEmpty())
    }

    @Test fun previewIsReadOnlyAndExplicitSwitchAppliesBothDirectionsToTheVisibleItems() = runTest(scheduler) {
        val gateway = WorkshopPresetTestGateway()
        val vm = model(gateway)
        vm.start(); advanceUntilIdle()
        vm.presets.preview(preset()); advanceUntilIdle()
        val preview = requireNotNull(vm.presets.state.value.preview)
        assertTrue(preview.canApply)
        assertEquals(listOf("3"), preview.plan?.addIds)
        assertEquals(listOf("1"), preview.plan?.removeIds)
        assertEquals(1, preview.plan?.retained)
        assertTrue(gateway.writes.isEmpty())
        vm.applyPreset(); advanceUntilIdle()
        assertEquals(false, vm.state.value.items.single { it.id == "1" }.subscribed)
        assertEquals(true, vm.state.value.items.single { it.id == "3" }.subscribed)
        assertTrue(vm.state.value.bulk?.presetVerified == true)
    }

    @Test fun partialOrChangingListsCannotProduceAConfirmationOrWrites() = runTest(scheduler) {
        for (badSecondPage in listOf(false, true)) {
            val gateway = WorkshopPresetTestGateway().apply {
                subscribed.clear(); subscribed += (1..31).map(Int::toString)
                if (badSecondPage) beforeBrowse = { page, _ -> if (page == 2) throw IOException() }
                else overrideBrowse = { page -> WorkshopBatch(listOf(item("1")), page, false, 31) }
            }
            val vm = model(gateway)
            vm.presets.preview(preset()); advanceUntilIdle(); vm.applyPreset(); advanceUntilIdle()
            assertFalse(vm.presets.state.value.preview?.canApply == true)
            assertNull(vm.presets.state.value.preview?.plan)
            assertNull(vm.state.value.bulk)
            assertTrue(gateway.writes.isEmpty())
        }
    }

    @Test fun unavailableTargetsAreShownAndCannotBeApplied() = runTest(scheduler) {
        val gateway = WorkshopPresetTestGateway().apply { unavailable += "3" }
        val vm = model(gateway)
        vm.presets.preview(preset()); advanceUntilIdle()
        assertEquals(listOf("3"), vm.presets.state.value.preview?.unavailableIds)
        assertFalse(vm.presets.state.value.preview?.canApply == true)
        vm.applyPreset(); advanceUntilIdle()
        assertNull(vm.state.value.bulk)
        assertTrue(gateway.writes.isEmpty())
    }

    @Test fun delayedCancelledPreviewCannotReplaceANewerPresetPreview() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val gateway = WorkshopPresetTestGateway().apply { beforeBrowse = { _, count ->
            if (count == 1) withContext(NonCancellable) { gate.await() }
        } }
        val vm = model(gateway)
        vm.presets.preview(preset()); runCurrent()
        val newer = preset(listOf("1", "4"))
        vm.presets.preview(newer); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(newer, vm.presets.state.value.preview?.preset)
        assertEquals(listOf("4"), vm.presets.state.value.preview?.plan?.addIds)
        assertTrue(gateway.writes.isEmpty())
        vm.stopBrowsing()
        assertNull(vm.presets.state.value.preview)
    }

    @Test fun pageDestructionAndPresetEditsDoNotChangeTheCapturedBackgroundSwitch() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val gateway = WorkshopPresetTestGateway().apply { afterWrite = { id, _ -> if (id == "3") gate.await() } }
        val repository = InMemoryWorkshopPresetRepository()
        val original = repository.save(570, "Realistic", WorkshopShare(570, listOf("2", "3"))).single()
        val controller = InMemoryWorkshopBulkController(570, account, gateway)
        val vm = SteamWorkshopViewModel(570, account, gateway, processingDispatcher = dispatcher,
            bulkController = controller, presetRepository = repository)
        val owner = ViewModelStore().apply { put("workshop", vm) }
        vm.presets.preview(original); advanceUntilIdle(); vm.applyPreset(); advanceUntilIdle()
        assertTrue(controller.state.value?.running == true)
        repository.save(570, original.name, WorkshopShare(570, listOf("4")), original.id)
        vm.updateAccount(account.copy(id = 999))
        owner.clear()
        gate.complete(Unit); advanceUntilIdle()
        val second = SteamWorkshopViewModel(570, account, gateway, processingDispatcher = dispatcher,
            bulkController = controller, presetRepository = repository)
        runCurrent()
        assertTrue(second.state.value.bulk?.presetVerified == true)
        assertEquals(setOf("2", "3"), gateway.subscribed)
        assertTrue(gateway.accounts.all { it == account.id })
    }

    @Test fun mismatchedGameOrResolvedAccountCannotReadOrApplyThePreset() = runTest(scheduler) {
        val gateway = WorkshopPresetTestGateway()
        val vm = SteamWorkshopViewModel(570, account, gateway,
            resolver = SteamAccountSessionResolver { current, _ -> current.copy(steamId = "76561198000000999") },
            processingDispatcher = dispatcher)
        vm.presets.preview(preset(appId = 550)); advanceUntilIdle()
        assertNull(vm.presets.state.value.preview)
        vm.presets.preview(preset()); advanceUntilIdle()
        assertEquals(WorkshopFailure.LOGIN, vm.presets.state.value.preview?.failure)
        assertEquals(0, gateway.browseCount)
        assertTrue(gateway.writes.isEmpty())
    }

    @Test fun anAlreadyMatchingSetHasNoChangesAndSavingAnEmptySetReportsTheProblem() = runTest(scheduler) {
        val gateway = WorkshopPresetTestGateway()
        val vm = model(gateway)
        vm.presets.preview(preset(listOf("1", "2"))); advanceUntilIdle()
        assertTrue(vm.presets.state.value.preview?.canApply == true)
        assertTrue(vm.presets.state.value.preview?.changes?.isEmpty() == true)
        vm.presets.dismissPreview()
        gateway.subscribed.clear()
        vm.presets.fromSubscriptions(); advanceUntilIdle()
        assertEquals(WorkshopShareProblem.EMPTY, vm.presets.state.value.draft?.shareProblem)
        vm.presets.name("Empty"); vm.presets.save(); advanceUntilIdle()
        assertTrue(vm.presets.state.value.library.items.isEmpty())
    }
}
