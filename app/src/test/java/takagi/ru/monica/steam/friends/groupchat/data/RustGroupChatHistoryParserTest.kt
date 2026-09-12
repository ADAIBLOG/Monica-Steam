package takagi.ru.monica.steam.friends.groupchat.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.steam.friends.groupchat.domain.SteamGroupChatReactionType

class RustGroupChatHistoryParserTest {
    @Test
    fun bridgeLayoutProducesMessagesReactionsAndLocalizedEvents() {
        val payload = bridge(
            moreAvailable = true,
            messages = arrayOf(
                message(
                    senderAccountId = 39_734_274L,
                    timestamp = 300L,
                    ordinal = 2,
                    deleted = false,
                    eventType = 0,
                    rawBody = "Hello",
                    eventText = "",
                    reactions = arrayOf(reaction(2, 3, true, "party"))
                ),
                message(
                    senderAccountId = 0L,
                    timestamp = 301L,
                    ordinal = 3,
                    deleted = false,
                    eventType = 5,
                    rawBody = "",
                    eventText = "",
                    reactions = emptyArray()
                )
            )
        )

        val page = RustGroupChatHistoryParser.decodeForTest(payload)
        requireNotNull(page)
        assertTrue(page.moreAvailable)
        assertEquals(2, page.messages.size)
        assertEquals("76561198000000002", page.messages[0].senderSteamId)
        assertEquals("Hello", page.messages[0].body)
        assertEquals(SteamGroupChatReactionType.STICKER, page.messages[0].reactions.single().type)
        assertEquals(3, page.messages[0].reactions.single().count)
        assertTrue(page.messages[0].reactions.single().hasUserReacted)
        assertEquals("邀请了一位成员加入群聊", page.messages[1].body)
        assertEquals(5, page.messages[1].serverEventType)
        assertFalse(page.messages[1].deleted)
    }

    @Test
    fun corruptFlagsTruncationAndTrailingBytesAreRejected() {
        assertNull(RustGroupChatHistoryParser.decodeForTest(byteArrayOf(1, 2, 3)))
        assertNull(
            RustGroupChatHistoryParser.decodeForTest(
                bridge(
                    moreAvailableRaw = 7,
                    messages = emptyArray()
                )
            )
        )
        assertNull(
            RustGroupChatHistoryParser.decodeForTest(
                bridge(
                    messages = arrayOf(
                        message(
                            1L, 10L, 1, false, 0, "A", "", emptyArray(), deletedRaw = 3
                        )
                    )
                )
            )
        )
        assertNull(
            RustGroupChatHistoryParser.decodeForTest(
                bridge(
                    messages = arrayOf(
                        message(
                            1L,
                            10L,
                            1,
                            false,
                            0,
                            "A",
                            "",
                            arrayOf(reaction(9, 1, false, "bad"))
                        )
                    )
                )
            )
        )
        val trailing = bridge(
            messages = arrayOf(message(1L, 10L, 1, false, 0, "A", "", emptyArray()))
        ) + byteArrayOf(9)
        assertNull(RustGroupChatHistoryParser.decodeForTest(trailing))
    }

    private fun bridge(
        moreAvailable: Boolean = false,
        messages: Array<ByteArray>,
        moreAvailableRaw: Int = if (moreAvailable) 1 else 0
    ): ByteArray = ByteBuffer.allocate(12 + messages.sumOf(ByteArray::size))
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte()))
        .putInt(messages.size)
        .putInt(moreAvailableRaw)
        .also { buffer -> messages.forEach(buffer::put) }
        .array()

    private fun message(
        senderAccountId: Long,
        timestamp: Long,
        ordinal: Int,
        deleted: Boolean,
        eventType: Int,
        rawBody: String,
        eventText: String,
        reactions: Array<ByteArray>,
        deletedRaw: Int = if (deleted) 1 else 0
    ): ByteArray {
        val rawBodyBytes = rawBody.toByteArray(Charsets.UTF_8)
        val eventTextBytes = eventText.toByteArray(Charsets.UTF_8)
        val size = 28 + 4 + rawBodyBytes.size + 4 + eventTextBytes.size + 4 +
            reactions.sumOf(ByteArray::size)
        return ByteBuffer.allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(senderAccountId)
            .putLong(timestamp)
            .putInt(ordinal)
            .putInt(deletedRaw)
            .putInt(eventType)
            .putInt(rawBodyBytes.size)
            .put(rawBodyBytes)
            .putInt(eventTextBytes.size)
            .put(eventTextBytes)
            .putInt(reactions.size)
            .also { buffer -> reactions.forEach(buffer::put) }
            .array()
    }

    private fun reaction(
        type: Int,
        count: Int,
        hasUserReacted: Boolean,
        name: String
    ): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(16 + nameBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(type)
            .putInt(count)
            .putInt(if (hasUserReacted) 1 else 0)
            .putInt(nameBytes.size)
            .put(nameBytes)
            .array()
    }
}
