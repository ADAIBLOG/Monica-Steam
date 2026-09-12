package takagi.ru.monica.steam.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import takagi.ru.monica.steam.core.RustSteamCoreNative

internal object RustAuthorizedDeviceParser {
    fun parseOrNull(response: ByteArray): List<SteamAuthorizedDevice>? =
        RustSteamCoreNative.parseAuthorizedDevicesOrNull(response)?.let(::decode)

    internal fun decodeForTest(payload: ByteArray): List<SteamAuthorizedDevice>? = decode(payload)

    private fun decode(payload: ByteArray): List<SteamAuthorizedDevice>? = runCatching {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= HEADER_BYTES) { "Rust authorized-device bridge is truncated" }
        require(readMagic(buffer).contentEquals(MAGIC)) {
            "Rust authorized-device bridge version is unsupported"
        }
        val count = buffer.int
        require(count in 0..MAX_ITEMS) { "Rust authorized-device count is invalid" }

        val devices = ArrayList<SteamAuthorizedDevice>(count)
        repeat(count) {
            require(buffer.remaining() >= FIXED_DEVICE_BYTES) {
                "Rust authorized-device record is truncated"
            }
            val hasToken = readFlag(buffer, "token presence")
            val rawToken = buffer.long
            val platformType = buffer.int
            val loggedIn = readFlag(buffer, "logged-in")
            val isCurrent = readFlag(buffer, "current-device")
            val description = readString(buffer)
            val firstSeen = readUsage(buffer)
            val lastSeen = readUsage(buffer)
            devices += SteamAuthorizedDevice(
                tokenId = if (hasToken) rawToken.toULong().toString() else "",
                description = description,
                platformType = platformType,
                loggedIn = loggedIn,
                firstSeen = firstSeen,
                lastSeen = lastSeen,
                isCurrent = isCurrent
            )
        }
        require(!buffer.hasRemaining()) { "Rust authorized-device bridge has trailing bytes" }
        devices
    }.getOrNull()

    private fun readUsage(buffer: ByteBuffer): SteamAuthorizedDeviceUsage? {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Rust authorized-device usage is truncated" }
        if (!readFlag(buffer, "usage presence")) return null
        require(buffer.remaining() >= Long.SIZE_BYTES) { "Rust authorized-device usage time is truncated" }
        return SteamAuthorizedDeviceUsage(
            timeSeconds = buffer.long,
            country = readString(buffer),
            state = readString(buffer),
            city = readString(buffer)
        )
    }

    private fun readFlag(buffer: ByteBuffer, label: String): Boolean {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Rust authorized-device $label flag is truncated" }
        return when (val raw = buffer.int) {
            0 -> false
            1 -> true
            else -> error("Rust authorized-device $label flag is invalid: $raw")
        }
    }

    private fun readString(buffer: ByteBuffer): String {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Rust authorized-device string is truncated" }
        val length = buffer.int
        require(length >= 0 && length <= buffer.remaining()) {
            "Rust authorized-device string length is invalid"
        }
        return ByteArray(length).also(buffer::get).toString(Charsets.UTF_8)
    }

    private fun readMagic(buffer: ByteBuffer): ByteArray = ByteArray(4).also(buffer::get)

    private val MAGIC = byteArrayOf(
        'M'.code.toByte(),
        'S'.code.toByte(),
        'D'.code.toByte(),
        '1'.code.toByte()
    )
    private const val HEADER_BYTES = 8
    private const val FIXED_DEVICE_BYTES = 24
    private const val MAX_ITEMS = 100_000
}
