package takagi.ru.monica.steam.workshop

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import takagi.ru.monica.steam.data.SteamAccount
import takagi.ru.monica.steam.session.domain.SteamAccountSessionResolver

@OptIn(ExperimentalCoroutinesApi::class)
class WorkshopBulkPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = SteamWorkshopViewModelTest.account()
    private fun target(origin: String = "room", appId: Int = 570) =
        WorkshopBulkTarget("$origin|${account.id}|${account.steamId}", account.id, account.steamId, appId)
    private fun task(state: WorkshopBulkState = WorkshopBulkState(listOf("1", "2", "3"), true, false)) =
        WorkshopBulkTask(target(), "Dota 2", "Test account", state)

    @Test fun freshStoreRestoresTaskIdentityProgressAndStopRequest() {
        val root = temporary.newFolder()
        val original = task().let { it.copy(state = it.state.copy(stopRequested = true,
            results = listOf(WorkshopBulkResult("1", "One", WorkshopBulkOutcome.UPDATED)))) }
        WorkshopBulkTaskStore(root).write(original)
        assertEquals(original, WorkshopBulkTaskStore(root).read(original.target.key))
        val text = File(root, "${original.target.key}.json").readText()
        assertFalse(text.contains("accessToken"))
        assertFalse(text.contains("refreshToken"))
        assertFalse(text.contains("sharedSecret"))
    }

    @Test fun interruptedReplacementKeepsTheLastCompleteCheckpoint() {
        val root = temporary.newFolder()
        val original = task()
        val store = WorkshopBulkTaskStore(root)
        store.write(original)
        File(root, "${original.target.key}.tmp").writeText("{\"incomplete\":")
        assertEquals(original, WorkshopBulkTaskStore(root).read(original.target.key))
        val stopped = original.copy(state = original.state.copy(running = false, stopRequested = true))
        store.write(stopped)
        assertEquals(stopped, store.read(stopped.target.key))
    }

    @Test fun gameAndStorageOriginsHaveSeparateQueues() {
        val keys = listOf(target(), target(appId = 550), target("mdbx:10:entry"), target("mdbx:11:entry")).map { it.key }
        assertEquals(4, keys.distinct().size)
    }

    @Test fun duplicateOrForeignResultsCannotBecomeAResumableCheckpoint() {
        val store = WorkshopBulkTaskStore(temporary.newFolder())
        val result = WorkshopBulkResult("1", "One", WorkshopBulkOutcome.UPDATED)
        assertThrows(IllegalArgumentException::class.java) { store.write(task().let {
            it.copy(state = it.state.copy(results = listOf(result, result)))
        }) }
        val foreign = task().state.copy(results = listOf(result.copy(id = "4")))
        assertThrows(IllegalArgumentException::class.java) { store.write(task(foreign)) }
        assertThrows(IllegalArgumentException::class.java) { store.read("../outside") }
    }

    @Test fun processRestartReconcilesAnAcceptedWriteWhoseReplyWasInterrupted() = runTest {
        val root = temporary.newFolder()
        val store = WorkshopBulkTaskStore(root)
        val gateway = Fake()
        val gate = CompletableDeferred<Unit>()
        gateway.afterWrite = { if (it == "2") gate.await() }
        var checkpoint = task()
        store.write(checkpoint)
        val job = launch {
            WorkshopBulkRunner(570, account, gateway).run(checkpoint.state, { false }) { next ->
                checkpoint = checkpoint.copy(state = next)
                store.write(checkpoint)
            }
        }
        runCurrent()
        advanceTimeBy(351); runCurrent()
        assertEquals(listOf("1", "2"), gateway.writes)
        job.cancelAndJoin()
        val restored = requireNotNull(WorkshopBulkTaskStore(root).read(checkpoint.target.key))
        assertEquals(listOf("1"), restored.state.results.map { it.id })
        val result = WorkshopBulkRunner(570, account, gateway).run(restored.state, { false }) { }
        assertFalse(result.running)
        assertEquals(listOf("1", "2", "3"), gateway.writes)
        assertEquals(WorkshopBulkOutcome.UNCHANGED, result.results.single { it.id == "2" }.outcome)
        assertEquals(3, result.results.size)
    }

    @Test fun replayingAnOlderCheckpointSkipsAllAlreadySubscribedItems() = runTest {
        val gateway = Fake().apply { subscribed += listOf("1", "2") }
        val result = WorkshopBulkRunner(570, account, gateway).run(task().state, { false }) { }
        assertEquals(listOf("3"), gateway.writes)
        assertEquals(2, result.unchanged)
        assertEquals(1, result.updated)
    }

    @Test fun stoppedCheckpointMakesNoFurtherSteamRequests() = runTest {
        val gateway = Fake()
        val state = task().state.copy(stopRequested = true)
        val result = WorkshopBulkRunner(570, account, gateway).run(state, { false }) { }
        assertEquals(0, gateway.reads)
        assertTrue(gateway.writes.isEmpty())
        assertFalse(result.running)
        assertEquals(3, result.remaining)
    }

    @Test fun sessionResolutionCannotRedirectTheQueueToAnotherAccount() = runTest {
        val gateway = Fake()
        val result = WorkshopBulkRunner(570, account, gateway,
            SteamAccountSessionResolver { current, _ -> current.copy(id = current.id + 1) })
            .run(task().state, { false }) { }
        assertEquals(WorkshopFailure.LOGIN, result.failure)
        assertEquals(0, gateway.reads)
        assertTrue(gateway.writes.isEmpty())
    }

    private class Fake : SteamWorkshopGateway {
        val subscribed = mutableSetOf<String>()
        val writes = mutableListOf<String>()
        var reads = 0
        var afterWrite: suspend (String) -> Unit = { }
        override suspend fun supportsWorkshop(appId: Int) = true
        override suspend fun browse(account: SteamAccount?, appId: Int, query: WorkshopQuery, page: Int) =
            WorkshopBatch(emptyList(), page, false, 0)
        override suspend fun details(account: SteamAccount, appId: Int, id: String) =
            WorkshopItem(id, appId, "Item $id", canSubscribe = true, subscribed = id in subscribed)
        override suspend fun inspectItems(account: SteamAccount, appId: Int, ids: List<String>): List<WorkshopItem> {
            reads++
            return ids.map { details(account, appId, it) }
        }
        override suspend fun setSubscription(account: SteamAccount, item: WorkshopItem, subscribe: Boolean, dependencies: Boolean) {
            writes += item.id
            if (subscribe) subscribed += item.id else subscribed -= item.id
            afterWrite(item.id)
        }
    }
}
