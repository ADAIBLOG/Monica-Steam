package takagi.ru.monica.steam.friends.groupchat.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import takagi.ru.monica.steam.core.RustSteamCoreNative
import takagi.ru.monica.steam.friends.chat.domain.steamId64FromAccountId
import takagi.ru.monica.steam.friends.groupchat.domain.SteamGroupChatRoom
import takagi.ru.monica.steam.friends.groupchat.domain.SteamGroupChatSummary
import takagi.ru.monica.steam.friends.groupchat.domain.steamGroupAvatarUrl

/** Decoder for the coarse-grained Rust group/room summary parser. */
internal object RustGroupChatSummariesParser {
    fun parseOrNull(response: ByteArray): List<SteamGroupChatSummary>? {
        val payload = RustSteamCoreNative.parseGroupChatSummariesOrNull(response) ?: return null
        return decodeOrNull(payload)
    }

    internal fun decodeForTest(payload: ByteArray): List<SteamGroupChatSummary>? =
        decodeOrNull(payload)

    private fun decodeOrNull(payload: ByteArray): List<SteamGroupChatSummary>? =
        runCatching { decode(payload) }.getOrNull()

    private fun decode(payload: ByteArray): List<SteamGroupChatSummary> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= HEADER_BYTES) { "Rust group summaries bridge is truncated" }
        val magic = ByteArray(4).also(buffer::get)
        require(magic.contentEquals(MAGIC)) {
            "Rust group summaries bridge version is unsupported"
        }
        val groupCount = buffer.int
        require(groupCount in 0..MAX_GROUPS) { "Rust group count is invalid" }
        require(buffer.remaining().toLong() >= groupCount.toLong() * MIN_GROUP_BYTES) {
            "Rust group summaries bridge is truncated"
        }

        val groups = ArrayList<SteamGroupChatSummary>(groupCount)
        repeat(groupCount) {
            require(buffer.remaining() >= MIN_GROUP_BYTES) { "Rust group summary is truncated" }
            val groupId = java.lang.Long.toUnsignedString(buffer.long)
            val ownerAccountId = buffer.long
            val activeMemberCount = buffer.int
            val activeVoiceMemberCountRaw = buffer.int
            val defaultChatId = java.lang.Long.toUnsignedString(buffer.long)
            val rank = buffer.int
            val name = readString(buffer).ifBlank { "Steam group" }
            val tagline = readString(buffer)
            val avatarUgcRaw = readBytes(buffer)
            val avatarLegacyRaw = readBytes(buffer)

            require(buffer.remaining() >= Int.SIZE_BYTES) {
                "Rust group top-member count is truncated"
            }
            val topMemberCount = buffer.int
            require(topMemberCount in 0..MAX_TOP_MEMBERS_PER_GROUP) {
                "Rust group top-member count is invalid"
            }
            require(buffer.remaining().toLong() >= topMemberCount.toLong() * Long.SIZE_BYTES) {
                "Rust group top members are truncated"
            }
            val topMemberSteamIds = ArrayList<String>(topMemberCount)
            repeat(topMemberCount) {
                val accountId = buffer.long
                require(accountId > 0L) { "Rust group top-member account id is invalid" }
                topMemberSteamIds += steamId64FromAccountId(accountId)
            }

            require(buffer.remaining() >= Int.SIZE_BYTES) {
                "Rust group room count is truncated"
            }
            val roomCount = buffer.int
            require(roomCount in 0..MAX_ROOMS_PER_GROUP) { "Rust group room count is invalid" }
            require(buffer.remaining().toLong() >= roomCount.toLong() * MIN_ROOM_BYTES) {
                "Rust group rooms are truncated"
            }
            val rooms = ArrayList<SteamGroupChatRoom>(roomCount)
            repeat(roomCount) {
                rooms += readRoom(buffer)
            }

            val avatarUrl = steamGroupAvatarUrl(avatarUgcRaw).ifBlank {
                steamGroupAvatarUrl(avatarLegacyRaw)
            }
            groups += SteamGroupChatSummary(
                groupId = groupId,
                name = name,
                tagline = tagline,
                ownerAccountId = ownerAccountId,
                activeMemberCount = activeMemberCount,
                activeVoiceMemberCount = maxOf(
                    activeVoiceMemberCountRaw.coerceAtLeast(0),
                    rooms.sumOf { room -> room.voiceMemberSteamIds.size }
                ),
                defaultChatId = defaultChatId,
                rooms = rooms,
                rank = rank,
                avatarUrl = avatarUrl,
                unreadCount = rooms.count(SteamGroupChatRoom::unread),
                topMemberSteamIds = topMemberSteamIds
            )
        }
        require(!buffer.hasRemaining()) { "Rust group summaries bridge has trailing bytes" }
        return groups
    }

    private fun readRoom(buffer: ByteBuffer): SteamGroupChatRoom {
        require(buffer.remaining() >= MIN_ROOM_BYTES) { "Rust group room is truncated" }
        val chatId = java.lang.Long.toUnsignedString(buffer.long)
        val sortOrder = buffer.int
        val lastMessageTimestamp = buffer.long
        val lastSenderAccountId = buffer.long
        val lastAcknowledgedTimestamp = buffer.long
        val voiceAllowedRaw = buffer.int
        require(lastMessageTimestamp >= 0L) { "Rust group room timestamp is invalid" }
        require(lastAcknowledgedTimestamp >= 0L) { "Rust group room ack is invalid" }
        require(voiceAllowedRaw == 0 || voiceAllowedRaw == 1) {
            "Rust group room voice flag is invalid"
        }
        val name = readString(buffer).ifBlank { "Chat" }
        val lastMessage = readString(buffer)
        require(buffer.remaining() >= Int.SIZE_BYTES) {
            "Rust group voice-member count is truncated"
        }
        val memberCount = buffer.int
        require(memberCount in 0..MAX_MEMBERS_PER_ROOM) {
            "Rust group voice-member count is invalid"
        }
        require(buffer.remaining().toLong() >= memberCount.toLong() * Long.SIZE_BYTES) {
            "Rust group voice members are truncated"
        }
        val voiceMemberSteamIds = ArrayList<String>(memberCount)
        repeat(memberCount) {
            val accountId = buffer.long
            require(accountId > 0L) { "Rust group voice-member account id is invalid" }
            voiceMemberSteamIds += steamId64FromAccountId(accountId)
        }
        return SteamGroupChatRoom(
            chatId = chatId,
            name = name,
            sortOrder = sortOrder,
            lastMessageTimestamp = lastMessageTimestamp,
            lastMessage = lastMessage,
            lastSenderSteamId = lastSenderAccountId
                .takeIf { it > 0L }
                ?.let(::steamId64FromAccountId)
                .orEmpty(),
            lastAcknowledgedTimestamp = lastAcknowledgedTimestamp,
            unread = lastMessageTimestamp > lastAcknowledgedTimestamp,
            voiceAllowed = voiceAllowedRaw == 1,
            voiceMemberSteamIds = voiceMemberSteamIds
        )
    }

    private fun readString(buffer: ByteBuffer): String =
        readBytes(buffer).toString(Charsets.UTF_8)

    private fun readBytes(buffer: ByteBuffer): ByteArray {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Rust group bytes are truncated" }
        val length = buffer.int
        require(length >= 0 && length <= buffer.remaining()) {
            "Rust group bytes length is invalid"
        }
        return ByteArray(length).also(buffer::get)
    }

    private val MAGIC = byteArrayOf(
        'M'.code.toByte(), 'G'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte()
    )
    private const val HEADER_BYTES = 8
    private const val MIN_GROUP_BYTES = 60
    private const val MIN_ROOM_BYTES = 52
    private const val MAX_GROUPS = 10_000
    private const val MAX_ROOMS_PER_GROUP = 10_000
    private const val MAX_MEMBERS_PER_ROOM = 100_000
    private const val MAX_TOP_MEMBERS_PER_GROUP = 100_000
}
