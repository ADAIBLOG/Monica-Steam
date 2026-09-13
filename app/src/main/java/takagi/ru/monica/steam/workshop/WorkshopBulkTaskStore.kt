package takagi.ru.monica.steam.workshop

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Identifiers only: workers resolve credentials from the original account store at execution time. */
@Serializable
internal data class WorkshopBulkTarget(
    val handleKey: String, val accountId: Long, val steamId: String, val appId: Int
) {
    val key: String get() = MessageDigest.getInstance("SHA-256")
        .digest("$handleKey|workshop|$appId".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

@Serializable
internal data class WorkshopBulkTask(
    val target: WorkshopBulkTarget,
    val gameName: String,
    val accountName: String,
    val state: WorkshopBulkState,
    val schema: Int = if (state.preset == null) 1 else 2
) {
    fun validate() {
        require(schema in 1..2 && target.appId > 0 && target.handleKey.length in 1..512)
        require(WorkshopShareCode.validId(target.steamId))
        require(target.handleKey.endsWith("|${target.accountId}|${target.steamId}"))
        require(UUID.fromString(state.requestId).toString() == state.requestId)
        val maxOperations = if (state.preset == null) WorkshopShareCode.MAX_ITEMS else WorkshopShareCode.MAX_ITEMS * 2
        require(state.ids.size in 1..maxOperations && state.ids.all(WorkshopShareCode::validId))
        state.preset?.let { preset ->
            require(schema == 2 && !state.dependencies)
            preset.validate(target.appId)
            require(state.ids == preset.operationIds)
        }
        if (state.presetVerified) require(state.preset != null && !state.running && state.remaining == 0 && state.failed == 0 && state.failure == null)
        val ids = state.ids.toSet()
        require(ids.size == state.ids.size && state.results.size <= ids.size)
        require(state.results.map { it.id }.distinct().size == state.results.size)
        require(state.results.all { it.id in ids && it.title.length <= 256 })
        require(gameName.length <= 256 && accountName.length <= 256 && state.currentTitle.length <= 256)
    }
}

/** Blocking filesystem boundary; callers use their application/worker IO scope. */
internal class WorkshopBulkTaskStore(private val directory: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun read(key: String): WorkshopBulkTask? {
        val file = file(key)
        if (!file.exists()) return null
        require(file.length() <= MAX_BYTES)
        return json.decodeFromString<WorkshopBulkTask>(file.readText(Charsets.UTF_8)).also {
            it.validate()
            require(it.target.key == key)
        }
    }

    fun write(task: WorkshopBulkTask) {
        task.validate()
        check(directory.isDirectory || directory.mkdirs())
        val destination = file(task.target.key)
        val temporary = File(directory, "${task.target.key}.tmp")
        val bytes = json.encodeToString(task).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(key: String) { Files.deleteIfExists(file(key).toPath()) }

    private fun file(key: String): File {
        require(validKey(key))
        return File(directory, "$key.json")
    }

    companion object {
        // A replacement can contain 5000 targets plus 5000 removals, including bounded Unicode titles.
        private const val MAX_BYTES = 16 * 1024 * 1024
        fun validKey(key: String): Boolean = key.matches(Regex("[a-f0-9]{64}"))
    }
}
