package takagi.ru.monica.steam.friends.chat.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import takagi.ru.monica.steam.core.RustSteamCoreNative
import takagi.ru.monica.steam.friends.chat.domain.SteamChatMessage
import takagi.ru.monica.steam.friends.chat.domain.SteamChatPage
import takagi.ru.monica.steam.friends.chat.domain.SteamChatReaction
import takagi.ru.monica.steam.friends.chat.domain.SteamChatReactionType
import takagi.ru.monica.steam.friends.chat.domain.SteamChatSession
import takagi.ru.monica.steam.friends.chat.domain.steamId64FromAccountId
import takagi.ru.monica.steam.network.SteamProtoField
import takagi.ru.monica.steam.network.SteamProtoReader

internal object SteamFriendChatParser {
    fun parseSessions(response: ByteArray): List<SteamChatSession> {
        RustSteamCoreNative.parseChatSessionsOrNull(response)
            ?.let(::decodeRustSessions)
            ?.let { return it }
        return parseSessionsKotlin(response)
    }

    fun parseMessages(response: ByteArray, partnerSteamId: String): SteamChatPage {
        RustSteamCoreNative.parseChatMessagesOrNull(response)
            ?.let { decodeRustMessages(it, partnerSteamId) }
            ?.let { return it }
        return parseMessagesKotlin(response, partnerSteamId)
    }

    fun parseSentMessage(
        response: ByteArray,
        accountSteamId: String,
        partnerSteamId: String,
        requestedBody: String
    ): SteamChatMessage {
        val fields = SteamProtoReader(response).parse()
        return SteamChatMessage(
            partnerSteamId = partnerSteamId,
            senderSteamId = accountSteamId,
            timestamp = fields[2]?.asLong?.coerceAtLeast(0L) ?: 0L,
            ordinal = fields[3]?.asLong?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            body = fields[4]?.asString.orEmpty()
                .ifBlank { fields[1]?.asString.orEmpty() }
                .ifBlank { requestedBody }
        )
    }

    private fun decodeRustSessions(payload: ByteArray): List<SteamChatSession>? = runCatching {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= RUST_SESSIONS_HEADER_BYTES) {
            "Rust chat sessions bridge is truncated"
        }
        val magic = ByteArray(4).also(buffer::get)
        require(magic.contentEquals(RUST_SESSIONS_MAGIC)) {
            "Rust chat sessions bridge version is unsupported"
        }
        val count = buffer.int
        require(count in 0..MAX_RUST_CHAT_ITEMS) {
            "Rust chat sessions count is invalid"
        }
        val expectedBytes = count.toLong() * RUST_SESSION_ITEM_BYTES
        require(buffer.remaining().toLong() == expectedBytes) {
            "Rust chat sessions bridge size is invalid"
        }
        buildList(count) {
            repeat(count) {
                add(
                    SteamChatSession(
                        partnerSteamId = buffer.long.toString(),
                        lastMessageTimestamp = buffer.long,
                        lastViewTimestamp = buffer.long,
                        unreadCount = buffer.int
                    )
                )
            }
        }
    }.getOrNull()

    private fun decodeRustMessages(
        payload: ByteArray,
        partnerSteamId: String
    ): SteamChatPage? = runCatching {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= RUST_MESSAGES_HEADER_BYTES) {
            "Rust chat messages bridge is truncated"
        }
        val magic = ByteArray(4).also(buffer::get)
        require(magic.contentEquals(RUST_MESSAGES_MAGIC)) {
            "Rust chat messages bridge version is unsupported"
        }
        val count = buffer.int
        require(count in 0..MAX_RUST_CHAT_ITEMS) {
            "Rust chat message count is invalid"
        }
        val moreAvailableRaw = buffer.int
        require(moreAvailableRaw == 0 || moreAvailableRaw == 1) {
            "Rust chat page flag is invalid"
        }

        val messages = buildList(count) {
            repeat(count) {
                require(buffer.remaining() >= RUST_MESSAGE_FIXED_BYTES) {
                    "Rust chat message item is truncated"
                }
                val senderSteamId = buffer.long.toString()
                val timestamp = buffer.long
                val ordinal = buffer.int
                val body = readRustString(buffer)
                require(buffer.remaining() >= 4) {
                    "Rust chat reaction count is truncated"
                }
                val reactionCount = buffer.int
                require(reactionCount in 0..MAX_RUST_CHAT_ITEMS) {
                    "Rust chat reaction count is invalid"
                }
                val reactions = buildList(reactionCount) {
                    repeat(reactionCount) {
                        require(buffer.remaining() >= 4) {
                            "Rust chat reaction is truncated"
                        }
                        val type = when (buffer.int) {
                            1 -> SteamChatReactionType.EMOTICON
                            2 -> SteamChatReactionType.STICKER
                            else -> error("Rust chat reaction type is invalid")
                        }
                        val name = readRustString(buffer)
                        require(buffer.remaining() >= 4) {
                            "Rust chat reactor count is truncated"
                        }
                        val reactorCount = buffer.int
                        require(reactorCount in 0..MAX_RUST_CHAT_ITEMS) {
                            "Rust chat reactor count is invalid"
                        }
                        require(buffer.remaining().toLong() >= reactorCount.toLong() * Long.SIZE_BYTES) {
                            "Rust chat reactor list is truncated"
                        }
                        add(
                            SteamChatReaction(
                                type = type,
                                name = name,
                                reactorSteamIds = buildList(reactorCount) {
                                    repeat(reactorCount) { add(buffer.long.toString()) }
                                }
                            )
                        )
                    }
                }
                add(
                    SteamChatMessage(
                        partnerSteamId = partnerSteamId,
                        senderSteamId = senderSteamId,
                        timestamp = timestamp,
                        ordinal = ordinal,
                        body = body,
                        reactions = reactions
                    )
                )
            }
        }
        require(!buffer.hasRemaining()) { "Rust chat messages bridge has trailing bytes" }
        SteamChatPage(
            messages = messages,
            moreAvailable = moreAvailableRaw == 1
        )
    }.getOrNull()

    private fun readRustString(buffer: ByteBuffer): String {
        require(buffer.remaining() >= 4) { "Rust chat string length is truncated" }
        val length = buffer.int
        require(length >= 0 && length <= buffer.remaining()) {
            "Rust chat string is invalid"
        }
        val bytes = ByteArray(length).also(buffer::get)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun parseSessionsKotlin(response: ByteArray): List<SteamChatSession> {
        return SteamProtoReader(response).parseAll()
            .asSequence()
            .filter { it.number == 1 && it.bytes != null }
            .mapNotNull { field ->
                val session = runCatching {
                    SteamProtoReader(field.bytes ?: return@mapNotNull null).parse()
                }.getOrNull() ?: return@mapNotNull null
                val accountId = session[1]?.asLong?.takeIf { it > 0L }
                    ?: return@mapNotNull null
                SteamChatSession(
                    partnerSteamId = steamId64FromAccountId(accountId),
                    lastMessageTimestamp = session[2]?.asLong?.coerceAtLeast(0L) ?: 0L,
                    lastViewTimestamp = session[3]?.asLong?.coerceAtLeast(0L) ?: 0L,
                    unreadCount = session[4]?.asLong?.coerceIn(0L, Int.MAX_VALUE.toLong())
                        ?.toInt() ?: 0
                )
            }
            .distinctBy(SteamChatSession::partnerSteamId)
            .sortedByDescending(SteamChatSession::lastMessageTimestamp)
            .toList()
    }

    private fun parseMessagesKotlin(response: ByteArray, partnerSteamId: String): SteamChatPage {
        val fields = SteamProtoReader(response).parseAll()
        val messages = fields
            .asSequence()
            .filter { it.number == 1 && it.bytes != null }
            .mapNotNull { field ->
                val messageFields = runCatching {
                    SteamProtoReader(field.bytes ?: return@mapNotNull null).parseAll()
                }.getOrNull() ?: return@mapNotNull null
                val accountId = messageFields.firstOrNull { it.number == 1 }
                    ?.asLong?.takeIf { it > 0L }
                    ?: return@mapNotNull null
                val body = messageFields.firstOrNull { it.number == 3 }
                    ?.asString.orEmpty().trimEnd()
                if (body.isBlank()) return@mapNotNull null
                SteamChatMessage(
                    partnerSteamId = partnerSteamId,
                    senderSteamId = steamId64FromAccountId(accountId),
                    timestamp = messageFields.firstOrNull { it.number == 2 }
                        ?.asLong?.coerceAtLeast(0L) ?: 0L,
                    ordinal = messageFields.firstOrNull { it.number == 4 }
                        ?.asLong?.coerceIn(0L, Int.MAX_VALUE.toLong())
                        ?.toInt() ?: 0,
                    body = body,
                    reactions = parseReactionsKotlin(messageFields)
                )
            }
            .distinctBy(SteamChatMessage::stableId)
            .sortedWith(compareBy<SteamChatMessage> { it.timestamp }.thenBy { it.ordinal })
            .toList()
        return SteamChatPage(
            messages = messages,
            moreAvailable = fields.firstOrNull { it.number == 4 }?.asBool == true
        )
    }

    private fun parseReactionsKotlin(messageFields: List<SteamProtoField>): List<SteamChatReaction> =
        messageFields.asSequence()
            .filter { it.number == 5 && it.bytes != null }
            .mapNotNull { field ->
                val fields = runCatching {
                    SteamProtoReader(field.bytes ?: return@mapNotNull null).parseAll()
                }.getOrNull() ?: return@mapNotNull null
                val type = when (fields.firstOrNull { it.number == 1 }?.asInt) {
                    1 -> SteamChatReactionType.EMOTICON
                    2 -> SteamChatReactionType.STICKER
                    else -> return@mapNotNull null
                }
                val name = fields.firstOrNull { it.number == 2 }
                    ?.asString.orEmpty().trim().trim(':')
                if (name.isBlank()) return@mapNotNull null
                SteamChatReaction(
                    type = type,
                    name = name,
                    reactorSteamIds = fields.asSequence()
                        .filter { it.number == 3 }
                        .mapNotNull { it.asLong.takeIf { accountId -> accountId > 0L } }
                        .map(::steamId64FromAccountId)
                        .distinct()
                        .toList()
                )
            }
            .toList()

    private val RUST_SESSIONS_MAGIC = byteArrayOf(
        'M'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte()
    )
    private val RUST_MESSAGES_MAGIC = byteArrayOf(
        'M'.code.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte()
    )
    private const val RUST_SESSIONS_HEADER_BYTES = 8
    private const val RUST_MESSAGES_HEADER_BYTES = 12
    private const val RUST_SESSION_ITEM_BYTES = 28L
    private const val RUST_MESSAGE_FIXED_BYTES = 24
    private const val MAX_RUST_CHAT_ITEMS = 100_000
}
