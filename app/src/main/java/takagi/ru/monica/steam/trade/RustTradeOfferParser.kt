package takagi.ru.monica.steam.trade

import java.nio.ByteBuffer
import java.nio.ByteOrder
import takagi.ru.monica.steam.core.RustSteamCoreNative

internal object RustTradeOfferParser {
    private const val STEAM_ID64_OFFSET = 76561197960265728L

    fun parseOrNull(response: ByteArray): SteamTradeOffersSnapshot? =
        RustSteamCoreNative.parseTradeOffersOrNull(response)?.let(::decode)

    internal fun decodeForTest(payload: ByteArray): SteamTradeOffersSnapshot? = decode(payload)

    private fun decode(payload: ByteArray): SteamTradeOffersSnapshot? = runCatching {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= HEADER_BYTES) { "Rust trade bridge is truncated" }
        require(readMagic(buffer).contentEquals(MAGIC)) {
            "Rust trade bridge version is unsupported"
        }
        val receivedCount = readCount(buffer, "received offer")
        val sentCount = readCount(buffer, "sent offer")
        require(receivedCount.toLong() + sentCount.toLong() <= MAX_OFFERS) {
            "Rust trade offer count is invalid"
        }

        val received = ArrayList<SteamTradeOffer>(receivedCount)
        repeat(receivedCount) {
            received += readOffer(buffer, SteamTradeOfferDirection.RECEIVED)
        }
        val sent = ArrayList<SteamTradeOffer>(sentCount)
        repeat(sentCount) {
            sent += readOffer(buffer, SteamTradeOfferDirection.SENT)
        }
        require(!buffer.hasRemaining()) { "Rust trade bridge has trailing bytes" }
        SteamTradeOffersSnapshot(received = received, sent = sent)
    }.getOrNull()

    private fun readOffer(
        buffer: ByteBuffer,
        direction: SteamTradeOfferDirection
    ): SteamTradeOffer {
        require(buffer.remaining() >= FIXED_OFFER_BYTES) { "Rust trade offer is truncated" }
        val id = buffer.long.toULong().toString()
        val partnerAccountId = buffer.long
        val stateCode = buffer.int
        val createdAt = buffer.long
        val updatedAt = buffer.long
        val expirationTime = buffer.long
        val escrowEndDate = buffer.long
        val confirmationMethod = buffer.int
        val message = readString(buffer)
        val itemsToGive = readItems(buffer)
        val itemsToReceive = readItems(buffer)
        return SteamTradeOffer(
            id = id,
            direction = direction,
            partnerAccountId = partnerAccountId,
            partnerSteamId = (partnerAccountId + STEAM_ID64_OFFSET).toString(),
            message = message,
            state = SteamTradeOfferState.fromCode(stateCode),
            rawStateCode = stateCode,
            itemsToGive = itemsToGive,
            itemsToReceive = itemsToReceive,
            createdAt = createdAt,
            updatedAt = updatedAt,
            expirationTime = expirationTime,
            escrowEndDate = escrowEndDate,
            confirmationMethod = confirmationMethod
        )
    }

    private fun readItems(buffer: ByteBuffer): List<SteamTradeOfferItem> {
        val count = readCount(buffer, "trade item")
        require(count <= MAX_ITEMS) { "Rust trade item count is invalid" }
        return List(count) {
            require(buffer.remaining() >= FIXED_ITEM_BYTES) { "Rust trade item is truncated" }
            val appId = buffer.int
            val contextId = buffer.long.toULong().toString()
            val assetId = buffer.long.toULong().toString()
            val classId = buffer.long.toULong().toString()
            val instanceId = buffer.long.toULong().toString()
            val amount = buffer.int
            val flags = buffer.int
            require(flags and VALID_ITEM_FLAGS.inv() == 0) { "Rust trade item flags are invalid" }
            SteamTradeOfferItem(
                appId = appId,
                contextId = contextId,
                assetId = assetId,
                classId = classId,
                instanceId = instanceId,
                amount = amount,
                name = readString(buffer),
                type = readString(buffer),
                iconUrl = readString(buffer),
                tradable = flags and FLAG_TRADABLE != 0,
                marketable = flags and FLAG_MARKETABLE != 0,
                missing = flags and FLAG_MISSING != 0
            )
        }
    }

    private fun readCount(buffer: ByteBuffer, label: String): Int {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Rust $label count is truncated" }
        val count = buffer.int
        require(count >= 0) { "Rust $label count is negative" }
        return count
    }

    private fun readString(buffer: ByteBuffer): String {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Rust trade string is truncated" }
        val length = buffer.int
        require(length >= 0 && length <= buffer.remaining()) { "Rust trade string length is invalid" }
        return ByteArray(length).also(buffer::get).toString(Charsets.UTF_8)
    }

    private fun readMagic(buffer: ByteBuffer): ByteArray = ByteArray(4).also(buffer::get)

    private val MAGIC = byteArrayOf(
        'M'.code.toByte(),
        'T'.code.toByte(),
        'O'.code.toByte(),
        '1'.code.toByte()
    )
    private const val HEADER_BYTES = 12
    private const val FIXED_OFFER_BYTES = 56
    private const val FIXED_ITEM_BYTES = 44
    private const val MAX_OFFERS = 100_000L
    private const val MAX_ITEMS = 1_000_000
    private const val FLAG_TRADABLE = 1
    private const val FLAG_MARKETABLE = 1 shl 1
    private const val FLAG_MISSING = 1 shl 2
    private const val VALID_ITEM_FLAGS = FLAG_TRADABLE or FLAG_MARKETABLE or FLAG_MISSING
}
