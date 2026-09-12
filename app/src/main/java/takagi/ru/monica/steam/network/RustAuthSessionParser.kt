package takagi.ru.monica.steam.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import takagi.ru.monica.steam.core.RustSteamCoreNative

internal data class RustAuthSessionInfo(
    val version: Int,
    val ip: String,
    val city: String,
    val country: String,
    val deviceName: String
)

internal object RustAuthSessionParser {
    fun pendingClientIdsOrNull(response: ByteArray): List<Long>? =
        RustSteamCoreNative.parsePendingLoginClientIdsOrNull(response)?.let(::decodeClientIds)

    fun sessionInfoOrNull(response: ByteArray): RustAuthSessionInfo? =
        RustSteamCoreNative.parseAuthSessionInfoOrNull(response)?.let(::decodeSessionInfo)

    fun confirmationSuccessOrNull(response: ByteArray): Boolean? =
        RustSteamCoreNative.parseAuthConfirmationOrNull(response)?.let(::decodeConfirmation)

    internal fun decodeClientIdsForTest(payload: ByteArray): List<Long>? = decodeClientIds(payload)
    internal fun decodeSessionInfoForTest(payload: ByteArray): RustAuthSessionInfo? = decodeSessionInfo(payload)
    internal fun decodeConfirmationForTest(payload: ByteArray): Boolean? = decodeConfirmation(payload)

    private fun decodeClientIds(payload: ByteArray): List<Long>? = runCatching {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= 8) { "Rust auth client-id bridge is truncated" }
        require(readMagic(buffer).contentEquals(CLIENT_IDS_MAGIC)) {
            "Rust auth client-id bridge version is unsupported"
        }
        val count = buffer.int
        require(count in 0..MAX_ITEMS) { "Rust auth client-id count is invalid" }
        require(buffer.remaining().toLong() == count.toLong() * Long.SIZE_BYTES) {
            "Rust auth client-id bridge size is invalid"
        }
        List(count) { buffer.long }
    }.getOrNull()

    private fun decodeSessionInfo(payload: ByteArray): RustAuthSessionInfo? = runCatching {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= 8) { "Rust auth session-info bridge is truncated" }
        require(readMagic(buffer).contentEquals(SESSION_INFO_MAGIC)) {
            "Rust auth session-info bridge version is unsupported"
        }
        val info = RustAuthSessionInfo(
            version = buffer.int,
            ip = readString(buffer),
            city = readString(buffer),
            country = readString(buffer),
            deviceName = readString(buffer)
        )
        require(!buffer.hasRemaining()) { "Rust auth session-info bridge has trailing bytes" }
        info
    }.getOrNull()

    private fun decodeConfirmation(payload: ByteArray): Boolean? = runCatching {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() == 8) { "Rust auth confirmation bridge size is invalid" }
        require(readMagic(buffer).contentEquals(CONFIRMATION_MAGIC)) {
            "Rust auth confirmation bridge version is unsupported"
        }
        when (val raw = buffer.int) {
            0 -> false
            1 -> true
            else -> error("Rust auth confirmation flag is invalid: $raw")
        }
    }.getOrNull()

    private fun readMagic(buffer: ByteBuffer): ByteArray = ByteArray(4).also(buffer::get)

    private fun readString(buffer: ByteBuffer): String {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Rust auth string is truncated" }
        val length = buffer.int
        require(length >= 0 && length <= buffer.remaining()) { "Rust auth string length is invalid" }
        return ByteArray(length).also(buffer::get).toString(Charsets.UTF_8)
    }

    private val CLIENT_IDS_MAGIC = byteArrayOf('M'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
    private val SESSION_INFO_MAGIC = byteArrayOf('M'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte())
    private val CONFIRMATION_MAGIC = byteArrayOf('M'.code.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
    private const val MAX_ITEMS = 100_000
}
