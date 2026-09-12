package takagi.ru.monica.steam.friends.groupchat.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.steam.friends.groupchat.domain.SteamGroupChatRoomType

class RustGroupChatSummariesParserTest {
    @Test
    fun bridgeProducesExistingGroupDomainSemantics() {
        val legacySha = ByteArray(20) { it.toByte() }
        val payload = bridge(
            group(
                groupId = 8001L,
                ownerAccountId = 39_734_274L,
                activeMemberCount = 12,
                activeVoiceMemberCount = 1,
                defaultChatId = 9002L,
                rank = 50,
                name = "",
                tagline = "Play together",
                avatarUgc = ByteArray(0),
                avatarLegacy = legacySha,
                topMembers = longArrayOf(39_734_274L, 39_734_275L),
                rooms = arrayOf(
                    room(
                        chatId = 9001L,
                        sortOrder = 1,
                        lastMessageTimestamp = 200L,
                        lastSenderAccountId = 39_734_274L,
                        lastAcknowledgedTimestamp = 150L,
                        voiceAllowed = false,
                        name = "",
                        lastMessage = "Hello",
                        voiceMembers = longArrayOf()
                    ),
                    room(
                        chatId = 9002L,
                        sortOrder = 2,
                        lastMessageTimestamp = 100L,
                        lastSenderAccountId = 0L,
                        lastAcknowledgedTimestamp = 100L,
                        voiceAllowed = true,
                        name = "Voice",
                        lastMessage = "",
                        voiceMembers = longArrayOf(39_734_274L, 39_734_275L)
                    )
                )
            )
        )

        val groups = RustGroupChatSummariesParser.decodeForTest(payload)
        requireNotNull(groups)
        val group = groups.single()
        assertEquals("8001", group.groupId)
        assertEquals("Steam group", group.name)
        assertEquals("Play together", group.tagline)
        assertEquals("9002", group.preferredChatId)
        assertEquals(2, group.activeVoiceMemberCount)
        assertEquals(1, group.unreadCount)
        assertEquals(
            listOf("76561198000000002", "76561198000000003"),
            group.topMemberSteamIds
        )
        assertEquals("Chat", group.rooms[0].name)
        assertTrue(group.rooms[0].unread)
        assertEquals("76561198000000002", group.rooms[0].lastSenderSteamId)
        assertEquals(SteamGroupChatRoomType.TEXT, group.rooms[0].type)
        assertEquals(SteamGroupChatRoomType.VOICE, group.rooms[1].type)
        assertFalse(group.rooms[1].unread)
        assertEquals(
            "https://community.akamai.steamstatic.com/images/chaticons/00/01/02/" +
                "000102030405060708090a0b0c0d0e0f10111213_256.jpg",
            group.avatarUrl
        )
    }

    @Test
    fun unsignedIdsAndUgcAvatarStayKotlinSide() {
        val payload = bridge(
            group(
                groupId = -6L,
                ownerAccountId = 0L,
                activeMemberCount = 0,
                activeVoiceMemberCount = 0,
                defaultChatId = -7L,
                rank = 0,
                name = "Group",
                tagline = "",
                avatarUgc = "http://example.com/avatar.png".toByteArray(),
                avatarLegacy = ByteArray(0),
                topMembers = longArrayOf(),
                rooms = emptyArray()
            )
        )
        val group = RustGroupChatSummariesParser.decodeForTest(payload)?.single()
        requireNotNull(group)
        assertEquals(java.lang.Long.toUnsignedString(-6L), group.groupId)
        assertEquals(java.lang.Long.toUnsignedString(-7L), group.defaultChatId)
        assertEquals("https://example.com/avatar.png", group.avatarUrl)
    }

    @Test
    fun corruptCountsFlagsAndTrailingBytesAreRejected() {
        assertNull(RustGroupChatSummariesParser.decodeForTest(byteArrayOf(1, 2, 3)))
        assertNull(
            RustGroupChatSummariesParser.decodeForTest(
                bridge(
                    group(
                        1L, 0L, 0, 0, 1L, 0, "G", "", ByteArray(0), ByteArray(0),
                        longArrayOf(),
                        arrayOf(room(1L, 0, 0L, 0L, 0L, false, "R", "", longArrayOf(), 7))
                    )
                )
            )
        )
        val trailing = bridge(
            group(
                1L, 0L, 0, 0, 1L, 0, "G", "", ByteArray(0), ByteArray(0),
                longArrayOf(), emptyArray()
            )
        ) + byteArrayOf(9)
        assertNull(RustGroupChatSummariesParser.decodeForTest(trailing))
    }

    private fun bridge(vararg groups: ByteArray): ByteArray = ByteBuffer.allocate(
        8 + groups.sumOf(ByteArray::size)
    )
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(byteArrayOf('M'.code.toByte(), 'G'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte()))
        .putInt(groups.size)
        .also { buffer -> groups.forEach(buffer::put) }
        .array()

    private fun group(
        groupId: Long,
        ownerAccountId: Long,
        activeMemberCount: Int,
        activeVoiceMemberCount: Int,
        defaultChatId: Long,
        rank: Int,
        name: String,
        tagline: String,
        avatarUgc: ByteArray,
        avatarLegacy: ByteArray,
        topMembers: LongArray,
        rooms: Array<ByteArray>
    ): ByteArray {
        val nameBytes = name.toByteArray()
        val taglineBytes = tagline.toByteArray()
        val size = 36 +
            4 + nameBytes.size +
            4 + taglineBytes.size +
            4 + avatarUgc.size +
            4 + avatarLegacy.size +
            4 + topMembers.size * 8 +
            4 + rooms.sumOf(ByteArray::size)
        return ByteBuffer.allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(groupId)
            .putLong(ownerAccountId)
            .putInt(activeMemberCount)
            .putInt(activeVoiceMemberCount)
            .putLong(defaultChatId)
            .putInt(rank)
            .putBytes(nameBytes)
            .putBytes(taglineBytes)
            .putBytes(avatarUgc)
            .putBytes(avatarLegacy)
            .putInt(topMembers.size)
            .also { buffer -> topMembers.forEach(buffer::putLong) }
            .putInt(rooms.size)
            .also { buffer -> rooms.forEach(buffer::put) }
            .array()
    }

    private fun room(
        chatId: Long,
        sortOrder: Int,
        lastMessageTimestamp: Long,
        lastSenderAccountId: Long,
        lastAcknowledgedTimestamp: Long,
        voiceAllowed: Boolean,
        name: String,
        lastMessage: String,
        voiceMembers: LongArray,
        voiceAllowedRaw: Int = if (voiceAllowed) 1 else 0
    ): ByteArray {
        val nameBytes = name.toByteArray()
        val lastMessageBytes = lastMessage.toByteArray()
        val size = 40 + 4 + nameBytes.size + 4 + lastMessageBytes.size + 4 + voiceMembers.size * 8
        return ByteBuffer.allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(chatId)
            .putInt(sortOrder)
            .putLong(lastMessageTimestamp)
            .putLong(lastSenderAccountId)
            .putLong(lastAcknowledgedTimestamp)
            .putInt(voiceAllowedRaw)
            .putBytes(nameBytes)
            .putBytes(lastMessageBytes)
            .putInt(voiceMembers.size)
            .also { buffer -> voiceMembers.forEach(buffer::putLong) }
            .array()
    }

    private fun ByteBuffer.putBytes(bytes: ByteArray): ByteBuffer =
        putInt(bytes.size).put(bytes)
}
