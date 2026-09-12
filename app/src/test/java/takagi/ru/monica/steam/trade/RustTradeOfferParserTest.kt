package takagi.ru.monica.steam.trade

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustTradeOfferParserTest {
    @Test
    fun decodesUnsignedIdsDescriptionsAndDirections() {
        val received = offer(
            id = -2L,
            partnerAccountId = 12_345L,
            message = "Received",
            stateCode = 2,
            createdAt = 100L,
            updatedAt = 120L,
            itemsToGive = emptyList(),
            itemsToReceive = listOf(
                Item(
                    appId = 730,
                    contextId = 2L,
                    assetId = 8L,
                    classId = 10L,
                    instanceId = 0L,
                    amount = 1,
                    flags = 3,
                    name = "Proto Item",
                    type = "Rifle",
                    iconUrl = "https://cdn.example/icon"
                )
            )
        )
        val sent = offer(
            id = 42L,
            partnerAccountId = 54_321L,
            message = "Sent",
            stateCode = 9,
            createdAt = 50L,
            updatedAt = 60L,
            itemsToGive = listOf(
                Item(753, 6L, 9L, 20L, 1L, 2, 4, "Card", "Trading Card", "")
            ),
            itemsToReceive = emptyList()
        )

        val decoded = requireNotNull(RustTradeOfferParser.decodeForTest(bridge(listOf(received), listOf(sent))))

        assertEquals(1, decoded.received.size)
        assertEquals(1, decoded.sent.size)
        val incoming = decoded.received.single()
        assertEquals("18446744073709551614", incoming.id)
        assertEquals(SteamTradeOfferDirection.RECEIVED, incoming.direction)
        assertEquals("76561197960278073", incoming.partnerSteamId)
        assertEquals(SteamTradeOfferState.ACTIVE, incoming.state)
        assertEquals("Proto Item", incoming.itemsToReceive.single().name)
        assertTrue(incoming.itemsToReceive.single().tradable)
        assertTrue(incoming.itemsToReceive.single().marketable)
        assertFalse(incoming.itemsToReceive.single().missing)

        val outgoing = decoded.sent.single()
        assertEquals(SteamTradeOfferDirection.SENT, outgoing.direction)
        assertEquals(SteamTradeOfferState.NEEDS_CONFIRMATION, outgoing.state)
        assertTrue(outgoing.itemsToGive.single().missing)
    }

    @Test
    fun preservesUnknownStateAndWrappedAmount() {
        val encoded = offer(
            id = 1L,
            partnerAccountId = 1L,
            message = "",
            stateCode = 99,
            createdAt = 0L,
            updatedAt = 0L,
            itemsToGive = listOf(
                Item(1, 0L, 0L, 0L, 0L, Int.MIN_VALUE, 0, "", "", "")
            ),
            itemsToReceive = emptyList()
        )
        val decoded = requireNotNull(RustTradeOfferParser.decodeForTest(bridge(listOf(encoded), emptyList())))
        assertEquals(SteamTradeOfferState.UNKNOWN, decoded.received.single().state)
        assertEquals(99, decoded.received.single().rawStateCode)
        assertEquals(Int.MIN_VALUE, decoded.received.single().itemsToGive.single().amount)
    }

    @Test
    fun invalidMagicFlagsAndTrailingBytesReturnNullForFallback() {
        val validOffer = offer(1L, 1L, "", 2, 0L, 0L, emptyList(), emptyList())
        val wrongMagic = bridge(listOf(validOffer), emptyList()).also { it[0] = 'X'.code.toByte() }
        assertNull(RustTradeOfferParser.decodeForTest(wrongMagic))

        val badFlagsOffer = offer(
            1L,
            1L,
            "",
            2,
            0L,
            0L,
            listOf(Item(1, 1L, 1L, 1L, 0L, 1, 8, "", "", "")),
            emptyList()
        )
        assertNull(RustTradeOfferParser.decodeForTest(bridge(listOf(badFlagsOffer), emptyList())))

        val trailing = bridge(listOf(validOffer), emptyList()) + byteArrayOf(1)
        assertNull(RustTradeOfferParser.decodeForTest(trailing))
    }

    private fun bridge(received: List<ByteArray>, sent: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'O'.code.toByte(), '1'.code.toByte()))
        out.writeInt(received.size)
        out.writeInt(sent.size)
        received.forEach(out::write)
        sent.forEach(out::write)
        return out.toByteArray()
    }

    private fun offer(
        id: Long,
        partnerAccountId: Long,
        message: String,
        stateCode: Int,
        createdAt: Long,
        updatedAt: Long,
        itemsToGive: List<Item>,
        itemsToReceive: List<Item>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeLong(id)
        out.writeLong(partnerAccountId)
        out.writeInt(stateCode)
        out.writeLong(createdAt)
        out.writeLong(updatedAt)
        out.writeLong(0L)
        out.writeLong(0L)
        out.writeInt(0)
        out.writeString(message)
        out.writeItems(itemsToGive)
        out.writeItems(itemsToReceive)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeItems(items: List<Item>) {
        writeInt(items.size)
        items.forEach { item ->
            writeInt(item.appId)
            writeLong(item.contextId)
            writeLong(item.assetId)
            writeLong(item.classId)
            writeLong(item.instanceId)
            writeInt(item.amount)
            writeInt(item.flags)
            writeString(item.name)
            writeString(item.type)
            writeString(item.iconUrl)
        }
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun ByteArrayOutputStream.writeLong(value: Long) {
        write(ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
    }

    private data class Item(
        val appId: Int,
        val contextId: Long,
        val assetId: Long,
        val classId: Long,
        val instanceId: Long,
        val amount: Int,
        val flags: Int,
        val name: String,
        val type: String,
        val iconUrl: String
    )
}
