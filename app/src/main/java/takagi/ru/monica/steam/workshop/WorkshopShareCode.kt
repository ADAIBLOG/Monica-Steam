package takagi.ru.monica.steam.workshop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal data class WorkshopShare(val appId: Int, val itemIds: List<String>)
internal enum class WorkshopShareProblem { INVALID, EMPTY, TOO_LARGE }
internal class WorkshopShareCodeException(val problem: WorkshopShareProblem) : Exception(problem.name)

/** Portable, versioned data only. Account information and executable URLs are never encoded. */
internal object WorkshopShareCode {
    const val MAX_ITEMS = 5_000
    const val MAX_TEXT_LENGTH = 66_000
    const val CODE_PREFIX = "MONICA-WS1:"
    const val LINK_PREFIX = "monica://workshop/v1/"
    private const val MAGIC = 0x4d575331
    private const val MAX_BYTES = 12 + MAX_ITEMS * 8
    private val token = Regex("(?:MONICA-WS1:|monica://workshop/v1/)([A-Za-z0-9_-]+)")

    fun encode(share: WorkshopShare): String {
        if (share.appId <= 0 || share.itemIds.any { !validId(it) }) invalid()
        if (share.itemIds.isEmpty()) throw WorkshopShareCodeException(WorkshopShareProblem.EMPTY)
        if (share.itemIds.size > MAX_ITEMS) throw WorkshopShareCodeException(WorkshopShareProblem.TOO_LARGE)
        val ids = share.itemIds.map { it.toULong().toString() }.distinct()
        val output = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(output)).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(share.appId)
            data.writeInt(ids.size)
            ids.forEach { data.writeLong(it.toULong().toLong()) }
        }
        return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }

    fun link(code: String): String {
        require(code.startsWith(CODE_PREFIX))
        return LINK_PREFIX + code.removePrefix(CODE_PREFIX)
    }

    fun decode(text: String): WorkshopShare {
        if (text.length > MAX_TEXT_LENGTH) throw WorkshopShareCodeException(WorkshopShareProblem.TOO_LARGE)
        val matches = token.findAll(text).take(3).toList()
        val payloads = matches.map { it.groupValues[1] }.distinct()
        if (payloads.size != 1 || matches.size > 2) invalid()
        // Reject a truncated token followed by URI parameters or Base64 padding.
        if (matches.any { text.getOrNull(it.range.last + 1) in listOf('=', '?', '&', '/', '%') }) invalid()
        try {
            val compressed = Base64.getUrlDecoder().decode(payloads.single())
            val output = ByteArrayOutputStream()
            GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = gzip.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) throw WorkshopShareCodeException(WorkshopShareProblem.TOO_LARGE)
                    output.write(buffer, 0, count)
                }
            }
            DataInputStream(ByteArrayInputStream(output.toByteArray())).use { data ->
                if (data.readInt() != MAGIC) invalid()
                val appId = data.readInt()
                val count = data.readInt()
                if (appId <= 0 || count <= 0) invalid()
                if (count > MAX_ITEMS) throw WorkshopShareCodeException(WorkshopShareProblem.TOO_LARGE)
                if (output.size() != 12 + count * 8) invalid()
                val ids = List(count) { data.readLong().toULong().toString().also { if (it == "0") invalid() } }.distinct()
                return WorkshopShare(appId, ids)
            }
        } catch (error: WorkshopShareCodeException) { throw error }
        catch (_: Exception) { invalid() }
    }

    fun validId(id: String): Boolean = id.length in 1..20 && id.all { it in '0'..'9' } &&
        id.toULongOrNull()?.let { it > 0uL } == true

    private fun invalid(): Nothing = throw WorkshopShareCodeException(WorkshopShareProblem.INVALID)
}
