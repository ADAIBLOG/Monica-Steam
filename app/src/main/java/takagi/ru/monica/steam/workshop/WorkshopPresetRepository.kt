package takagi.ru.monica.steam.workshop

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface WorkshopPresetRepository {
    suspend fun load(appId: Int): List<WorkshopPreset>
    suspend fun save(appId: Int, name: String, share: WorkshopShare, replacingId: String? = null): List<WorkshopPreset>
    suspend fun delete(appId: Int, id: String): List<WorkshopPreset>
}

private fun updatedPresets(
    current: List<WorkshopPreset>, appId: Int, name: String, share: WorkshopShare, replacingId: String?
): List<WorkshopPreset> {
    val trimmed = name.trim()
    if (trimmed.isBlank() || trimmed.length > MAX_PRESET_NAME || trimmed.any { it.isISOControl() }) {
        throw WorkshopPresetException(WorkshopPresetProblem.NAME)
    }
    if (current.any { it.id != replacingId && it.name.equals(trimmed, ignoreCase = true) }) {
        throw WorkshopPresetException(WorkshopPresetProblem.DUPLICATE_NAME)
    }
    if (replacingId != null && current.none { it.id == replacingId }) throw WorkshopPresetException(WorkshopPresetProblem.MISSING)
    if (replacingId == null && current.size >= MAX_PRESETS_PER_GAME) throw WorkshopPresetException(WorkshopPresetProblem.LIMIT)
    require(share.appId == appId)
    val normalized = WorkshopShareCode.decode(WorkshopShareCode.encode(share))
    val preset = WorkshopPreset(replacingId ?: UUID.randomUUID().toString(), trimmed, normalized, System.currentTimeMillis())
    return (current.filterNot { it.id == replacingId } + preset).sortedByDescending { it.updatedAt }
}

/** Share codes and names only; a preset is reusable across this game's accounts. */
internal class FileWorkshopPresetRepository(
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : WorkshopPresetRepository {
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun load(appId: Int): List<WorkshopPreset> = withContext(io) { lock.withLock { read(appId) } }

    override suspend fun save(appId: Int, name: String, share: WorkshopShare, replacingId: String?): List<WorkshopPreset> =
        withContext(io) { lock.withLock {
            updatedPresets(read(appId), appId, name, share, replacingId).also { write(appId, it) }
        } }

    override suspend fun delete(appId: Int, id: String): List<WorkshopPreset> = withContext(io) { lock.withLock {
        read(appId).filterNot { it.id == id }.also { write(appId, it) }
    } }

    private fun read(appId: Int): List<WorkshopPreset> {
        val file = file(appId)
        if (!file.exists()) return emptyList()
        require(file.length() <= MAX_BYTES)
        val catalog = json.decodeFromString<PresetCatalog>(file.readText(Charsets.UTF_8))
        require(catalog.schema == 1 && catalog.appId == appId && catalog.presets.size <= MAX_PRESETS_PER_GAME)
        val ids = hashSetOf<String>()
        val names = hashSetOf<String>()
        return catalog.presets.map { record ->
            require(UUID.fromString(record.id).toString() == record.id && ids.add(record.id))
            require(record.name.isNotBlank() && record.name == record.name.trim() && record.name.length <= MAX_PRESET_NAME &&
                record.name.none { it.isISOControl() } && names.add(record.name.lowercase(java.util.Locale.ROOT)))
            require(record.updatedAt >= 0)
            val share = WorkshopShareCode.decode(record.code)
            require(share.appId == appId)
            WorkshopPreset(record.id, record.name, share, record.updatedAt)
        }.sortedByDescending { it.updatedAt }
    }

    private fun write(appId: Int, presets: List<WorkshopPreset>) {
        val destination = file(appId)
        check(directory.isDirectory || directory.mkdirs())
        val catalog = PresetCatalog(appId = appId, presets = presets.map {
            PresetRecord(it.id, it.name, WorkshopShareCode.encode(it.share), it.updatedAt)
        })
        val bytes = json.encodeToString(catalog).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val temporary = File(directory, "$appId.tmp")
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun file(appId: Int): File {
        require(appId > 0)
        return File(directory, "$appId.json")
    }

    @Serializable private data class PresetCatalog(val schema: Int = 1, val appId: Int, val presets: List<PresetRecord>)
    @Serializable private data class PresetRecord(val id: String, val name: String, val code: String, val updatedAt: Long)

    companion object { private const val MAX_BYTES = 8 * 1024 * 1024 }
}

/** Host-side default; the Android screen always injects the application-owned disk repository. */
internal class InMemoryWorkshopPresetRepository : WorkshopPresetRepository {
    private val games = mutableMapOf<Int, List<WorkshopPreset>>()
    override suspend fun load(appId: Int) = games[appId].orEmpty()
    override suspend fun save(appId: Int, name: String, share: WorkshopShare, replacingId: String?): List<WorkshopPreset> =
        updatedPresets(load(appId), appId, name, share, replacingId).also { games[appId] = it }
    override suspend fun delete(appId: Int, id: String): List<WorkshopPreset> =
        load(appId).filterNot { it.id == id }.also { games[appId] = it }
}
