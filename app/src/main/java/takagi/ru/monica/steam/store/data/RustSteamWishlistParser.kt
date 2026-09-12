package takagi.ru.monica.steam.store.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import takagi.ru.monica.steam.core.RustSteamCoreNative
import takagi.ru.monica.steam.store.domain.SteamWishlistItem

internal object RustSteamWishlistParser {
    fun parseOrNull(response: ByteArray): List<SteamWishlistItem>? =
        RustSteamCoreNative.parseWishlistOrNull(response)?.let(::decode)

    internal fun decodeForTest(payload: ByteArray): List<SteamWishlistItem>? = decode(payload)

    private fun decode(payload: ByteArray): List<SteamWishlistItem>? = runCatching {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= HEADER_BYTES) { "Rust wishlist bridge is truncated" }
        require(readMagic(buffer).contentEquals(MAGIC)) {
            "Rust wishlist bridge version is unsupported"
        }
        val count = buffer.int
        require(count in 0..MAX_ITEMS) { "Rust wishlist item count is invalid" }

        val items = ArrayList<SteamWishlistItem>(count)
        repeat(count) {
            require(buffer.remaining() >= FIXED_ITEM_BYTES) { "Rust wishlist item is truncated" }
            val appId = buffer.int
            val hasPackage = readFlag(buffer, "package presence")
            val packageId = buffer.int
            val discountPercent = buffer.int
            val priority = buffer.int
            val addedAtEpochSeconds = buffer.long
            items += SteamWishlistItem(
                appId = appId,
                name = readString(buffer),
                imageUrl = readString(buffer),
                packageId = if (hasPackage) packageId else null,
                discountPercent = discountPercent,
                formattedInitialPrice = readString(buffer),
                formattedFinalPrice = readString(buffer),
                priority = priority,
                addedAtEpochSeconds = addedAtEpochSeconds
            )
        }
        require(!buffer.hasRemaining()) { "Rust wishlist bridge has trailing bytes" }
        items
    }.getOrNull()

    private fun readFlag(buffer: ByteBuffer, label: String): Boolean {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Rust wishlist $label is truncated" }
        return when (val raw = buffer.int) {
            0 -> false
            1 -> true
            else -> error("Rust wishlist $label is invalid: $raw")
        }
    }

    private fun readString(buffer: ByteBuffer): String {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Rust wishlist string is truncated" }
        val length = buffer.int
        require(length >= 0 && length <= buffer.remaining()) {
            "Rust wishlist string length is invalid"
        }
        return ByteArray(length).also(buffer::get).toString(Charsets.UTF_8)
    }

    private fun readMagic(buffer: ByteBuffer): ByteArray = ByteArray(4).also(buffer::get)

    private val MAGIC = byteArrayOf(
        'M'.code.toByte(),
        'S'.code.toByte(),
        'W'.code.toByte(),
        '1'.code.toByte()
    )
    private const val HEADER_BYTES = 8
    private const val FIXED_ITEM_BYTES = 28
    private const val MAX_ITEMS = 100_000
}
