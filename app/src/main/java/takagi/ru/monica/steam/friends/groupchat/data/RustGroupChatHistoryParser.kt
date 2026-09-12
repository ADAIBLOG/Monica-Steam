package takagi.ru.monica.steam.friends.groupchat.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import takagi.ru.monica.steam.core.RustSteamCoreNative
import takagi.ru.monica.steam.friends.chat.domain.steamId64FromAccountId
import takagi.ru.monica.steam.friends.groupchat.domain.SteamGroupChatMessage
import takagi.ru.monica.steam.friends.groupchat.domain.SteamGroupChatMessagePage
import takagi.ru.monica.steam.friends.groupchat.domain.SteamGroupChatReaction
import takagi.ru.monica.steam.friends.groupchat.domain.SteamGroupChatReactionType
import takagi.ru.monica.steam.friends.groupchat.domain.steamGroupEventText

/** Decoder for the coarse-grained Rust group-chat history parser. */
internal object RustGroupChatHistoryParser {
    fun parseOrNull(
        response: ByteArray,
        groupId: String,
        chatId: String
    ): SteamGroupChatMessagePage? {
        val payload = RustSteamCoreNative.parseGroupChatHistoryOrNull(response) ?: return null
        return decodeOrNull(payload, groupId, chatId)
    }

    internal fun decodeForTest(
        payload: ByteArray,
        groupId: String = "8001",
        chatId: String = "9001"
    ): SteamGroupChatMessagePage? = decodeOrNull(payload, groupId, chatId)

    private fun decodeOrNull(
        payload: ByteArray,
        groupId: String,
        chatId: String
    ): SteamGroupChatMessagePage? = runCatching {
        decode(payload, groupId, chatId)
    }.getOrNull()

    private fun decode(
        payload: ByteArray,
        groupId: String,
        chatId: String
    ): SteamGroupChatMessagePage {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= HEADER_BYTES) { "Rust group-chat bridge is truncated" }
        val magic = ByteArray(4).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Rust group-chat bridge version is unsupported" }
        val count = buffer.int
        val moreAvailableRaw = buffer.int
        require(count in 0..MAX_MESSAGES) { "Rust group-chat message count is invalid" }
        require(moreAvailableRaw == 0 || moreAvailableRaw == 1) {
            "Rust group-chat more-available flag is invalid"
        }
        require(buffer.remaining().toLong() >= count.toLong() * MIN_MESSAGE_BYTES) {
            "Rust group-chat bridge is truncated"
        }

        val messages = ArrayList<SteamGroupChatMessage>(count)
        repeat(count) {
            require(buffer.remaining() >= MIN_MESSAGE_BYTES) { "Rust group-chat message is truncated" }
            val senderAccountId = buffer.long
            val timestamp = buffer.long
            val ordinal = buffer.int
            val deletedRaw = buffer.int
            val eventType = buffer.int
            require(timestamp >= 0L) { "Rust group-chat timestamp is invalid" }
            require(deletedRaw == 0 || deletedRaw == 1) {
                "Rust group-chat deleted flag is invalid"
            }
            val rawBody = readString(buffer)
            val eventText = readString(buffer)
            require(buffer.remaining() >= Int.SIZE_BYTES) {
                "Rust group-chat reaction count is truncated"
            }
            val reactionCount = buffer.int
            require(reactionCount in 0..MAX_REACTIONS_PER_MESSAGE) {
                "Rust group-chat reaction count is invalid"
            }
            require(buffer.remaining().toLong() >= reactionCount.toLong() * MIN_REACTION_BYTES) {
                "Rust group-chat reactions are truncated"
            }

            val reactions = ArrayList<SteamGroupChatReaction>(reactionCount)
            repeat(reactionCount) {
                val typeRaw = buffer.int
                val countRaw = buffer.int
                val userReactedRaw = buffer.int
                require(typeRaw == 1 || typeRaw == 2) {
                    "Rust group-chat reaction type is invalid"
                }
                require(countRaw >= 0) { "Rust group-chat reaction count is invalid" }
                require(userReactedRaw == 0 || userReactedRaw == 1) {
                    "Rust group-chat reaction user flag is invalid"
                }
                val name = readString(buffer)
                require(name.isNotBlank()) { "Rust group-chat reaction name is blank" }
                reactions += SteamGroupChatReaction(
                    type = if (typeRaw == 2) {
                        SteamGroupChatReactionType.STICKER
                    } else {
                        SteamGroupChatReactionType.EMOTICON
                    },
                    name = name,
                    count = countRaw,
                    hasUserReacted = userReactedRaw == 1
                )
            }

            val body = rawBody.ifBlank {
                if (eventType > 0) steamGroupEventText(eventType, eventText) else ""
            }
            require(body.isNotBlank()) { "Rust group-chat message body is blank" }
            messages += SteamGroupChatMessage(
                groupId = groupId,
                chatId = chatId,
                senderSteamId = senderAccountId
                    .takeIf { it > 0L }
                    ?.let(::steamId64FromAccountId)
                    .orEmpty(),
                timestamp = timestamp,
                ordinal = ordinal,
                body = body,
                deleted = deletedRaw == 1,
                serverEventType = eventType,
                reactions = reactions
            )
        }
        require(!buffer.hasRemaining()) { "Rust group-chat bridge has trailing bytes" }
        return SteamGroupChatMessagePage(
            messages = messages,
            moreAvailable = moreAvailableRaw == 1
        )
    }

    private fun readString(buffer: ByteBuffer): String {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Rust group-chat string is truncated" }
        val length = buffer.int
        require(length >= 0 && length <= buffer.remaining()) {
            "Rust group-chat string length is invalid"
        }
        return ByteArray(length).also(buffer::get).toString(Charsets.UTF_8)
    }

    private val MAGIC = byteArrayOf(
        'M'.code.toByte(), 'S'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte()
    )
    private const val HEADER_BYTES = 12
    private const val MIN_MESSAGE_BYTES = 40
    private const val MIN_REACTION_BYTES = 16
    private const val MAX_MESSAGES = 100_000
    private const val MAX_REACTIONS_PER_MESSAGE = 4_096
}
