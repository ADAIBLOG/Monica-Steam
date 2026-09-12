package takagi.ru.monica.steam.library

import java.nio.ByteBuffer
import java.nio.ByteOrder
import takagi.ru.monica.steam.core.RustSteamCoreNative

/** Thin decoder for the coarse-grained Rust StoreBrowse GetItems parser. */
internal object RustStoreItemsParser {
    fun parseOrNull(
        response: ByteArray,
        currency: String
    ): Map<Int, SteamStoreMetadata>? {
        val payload = RustSteamCoreNative.parseStoreItemsOrNull(response) ?: return null
        return decodeOrNull(
            payload = payload,
            currency = currency,
            fetchedAt = System.currentTimeMillis()
        )
    }

    internal fun decodeForTest(
        payload: ByteArray,
        currency: String = "CNY",
        fetchedAt: Long = 123L
    ): Map<Int, SteamStoreMetadata>? = decodeOrNull(payload, currency, fetchedAt)

    private fun decodeOrNull(
        payload: ByteArray,
        currency: String,
        fetchedAt: Long
    ): Map<Int, SteamStoreMetadata>? = runCatching {
        decode(payload, currency, fetchedAt)
    }.getOrNull()

    private fun decode(
        payload: ByteArray,
        currency: String,
        fetchedAt: Long
    ): Map<Int, SteamStoreMetadata> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= HEADER_BYTES) { "Rust StoreBrowse bridge is truncated" }
        val magic = ByteArray(4).also(buffer::get)
        require(magic.contentEquals(MAGIC)) {
            "Rust StoreBrowse bridge version is unsupported"
        }
        val count = buffer.int
        require(count in 0..MAX_ITEMS) { "Rust StoreBrowse item count is invalid" }
        require(buffer.remaining().toLong() >= count.toLong() * MIN_ITEM_BYTES) {
            "Rust StoreBrowse bridge is truncated"
        }

        val result = LinkedHashMap<Int, SteamStoreMetadata>(count)
        repeat(count) {
            require(buffer.remaining() >= MIN_ITEM_BYTES) { "Rust StoreBrowse item is truncated" }
            val appId = buffer.int
            val cloudState = buffer.int
            val hasPrice = buffer.int
            val finalPriceMinor = buffer.long
            val originalPriceMinor = buffer.long
            require(appId > 0) { "Rust StoreBrowse app id is invalid" }
            require(cloudState in -1..1) { "Rust StoreBrowse cloud state is invalid" }
            require(hasPrice == 0 || hasPrice == 1) { "Rust StoreBrowse price state is invalid" }
            if (hasPrice == 1) {
                require(finalPriceMinor >= 0L && originalPriceMinor >= 0L) {
                    "Rust StoreBrowse price is invalid"
                }
            }
            val headerImageUrl = readString(buffer)
            val price = if (hasPrice == 1) {
                SteamGamePrice(
                    currency = currency,
                    finalPriceMinor = finalPriceMinor,
                    originalPriceMinor = originalPriceMinor,
                    isAvailable = true,
                    fetchedAt = fetchedAt
                )
            } else {
                null
            }
            result[appId] = SteamStoreMetadata(
                headerImageUrl = headerImageUrl,
                price = price,
                supportsSteamCloud = when (cloudState) {
                    1 -> true
                    0 -> false
                    else -> null
                }
            )
        }
        require(!buffer.hasRemaining()) { "Rust StoreBrowse bridge has trailing bytes" }
        return result
    }

    private fun readString(buffer: ByteBuffer): String {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Rust StoreBrowse string is truncated" }
        val length = buffer.int
        require(length >= 0 && length <= buffer.remaining()) {
            "Rust StoreBrowse string length is invalid"
        }
        return ByteArray(length).also(buffer::get).toString(Charsets.UTF_8)
    }

    private val MAGIC = byteArrayOf(
        'M'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte()
    )
    private const val HEADER_BYTES = 8
    private const val MIN_ITEM_BYTES = 32
    private const val MAX_ITEMS = 100_000
}
