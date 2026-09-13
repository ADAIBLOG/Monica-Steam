package takagi.ru.monica.steam.workshop

import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkshopPresetRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun restartRestoresNamedGameScopedShareCodesWithoutAccountData() = runTest {
        val root = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FileWorkshopPresetRepository(root, dispatcher)
        val saved = repository.save(570, " 二次元 MOD ", WorkshopShare(570, listOf("01", "2", "1"))).single()
        val restored = FileWorkshopPresetRepository(root, dispatcher).load(570).single()
        assertEquals(saved, restored)
        assertEquals("二次元 MOD", restored.name)
        assertEquals(listOf("1", "2"), restored.share.itemIds)
        assertTrue(repository.load(550).isEmpty())
        val text = File(root, "570.json").readText()
        assertTrue(text.contains(WorkshopShareCode.CODE_PREFIX))
        for (forbidden in listOf("accountId", "steamId", "accessToken", "refreshToken", "sharedSecret")) assertFalse(text.contains(forbidden))
    }

    @Test fun renameReplaceAndDeleteKeepOtherPresetsAndGames() = runTest {
        val repository = FileWorkshopPresetRepository(temporary.newFolder(), StandardTestDispatcher(testScheduler))
        val first = repository.save(570, "Anime", WorkshopShare(570, listOf("1"))).single()
        repository.save(570, "Realistic", WorkshopShare(570, listOf("2")))
        val otherGame = repository.save(550, "Anime", WorkshopShare(550, listOf("3"))).single()
        repository.save(570, "Anime revised", first.share, first.id)
        val updated = repository.save(570, "Anime revised", WorkshopShare(570, listOf("4", "5")), first.id)
        assertEquals(2, updated.size)
        assertEquals(listOf("4", "5"), updated.single { it.id == first.id }.share.itemIds)
        assertEquals("Realistic", repository.delete(570, first.id).single().name)
        assertEquals(listOf(otherGame), repository.load(550))
    }

    @Test fun duplicateNamesAndInvalidUpdatesLeaveTheLastSavedCatalogIntact() = runTest {
        val root = temporary.newFolder()
        val repository = FileWorkshopPresetRepository(root, StandardTestDispatcher(testScheduler))
        val original = repository.save(570, "Realistic", WorkshopShare(570, listOf("1")))
        val bytes = File(root, "570.json").readBytes()
        suspend fun rejected(problem: WorkshopPresetProblem, action: suspend () -> Unit) {
            try { action(); fail("Expected $problem") }
            catch (error: WorkshopPresetException) { assertEquals(problem, error.problem) }
        }
        rejected(WorkshopPresetProblem.DUPLICATE_NAME) { repository.save(570, " realistic ", WorkshopShare(570, listOf("2"))) }
        rejected(WorkshopPresetProblem.NAME) { repository.save(570, "\n", original.single().share) }
        rejected(WorkshopPresetProblem.NAME) { repository.save(570, "a".repeat(MAX_PRESET_NAME + 1), original.single().share) }
        rejected(WorkshopPresetProblem.MISSING) { repository.save(570, "Gone", original.single().share, "missing") }
        assertArrayEquals(bytes, File(root, "570.json").readBytes())
        assertEquals(original, repository.load(570))
    }

    @Test fun interruptedWriteAndCorruptCatalogNeverSilentlyOverwriteSavedPresets() = runTest {
        val root = temporary.newFolder()
        val repository = FileWorkshopPresetRepository(root, StandardTestDispatcher(testScheduler))
        val original = repository.save(570, "Anime", WorkshopShare(570, listOf("1")))
        File(root, "570.tmp").writeText("{\"presets\":")
        assertEquals(original, repository.load(570))
        File(root, "570.json").writeText("{broken")
        try { repository.save(570, "Realistic", WorkshopShare(570, listOf("2"))); fail("Corruption must be reported") }
        catch (_: kotlinx.serialization.SerializationException) { }
        assertEquals("{broken", File(root, "570.json").readText())
    }

    @Test fun presetCapacityAllowsReplacingExistingEntriesButRejectsAnotherOne() = runTest {
        val repository = InMemoryWorkshopPresetRepository()
        repeat(MAX_PRESETS_PER_GAME) { repository.save(570, "Preset $it", WorkshopShare(570, listOf("1"))) }
        val first = repository.load(570).first()
        assertEquals(MAX_PRESETS_PER_GAME, repository.save(570, "Updated", first.share, first.id).size)
        try { repository.save(570, "Overflow", first.share); fail("Capacity must be bounded") }
        catch (error: WorkshopPresetException) { assertEquals(WorkshopPresetProblem.LIMIT, error.problem) }
    }

    @Test fun crossGameAndEmptyCodesCannotBecomePresets() = runTest {
        val repository = FileWorkshopPresetRepository(temporary.newFolder(), StandardTestDispatcher(testScheduler))
        try { repository.save(570, "Wrong game", WorkshopShare(550, listOf("1"))); fail("Wrong game") }
        catch (_: IllegalArgumentException) { }
        try { repository.save(570, "Empty", WorkshopShare(570, emptyList())); fail("Empty code") }
        catch (error: WorkshopShareCodeException) { assertEquals(WorkshopShareProblem.EMPTY, error.problem) }
        assertTrue(repository.load(570).isEmpty())
    }
}
