package takagi.ru.monica.steam.library

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustStoreItemsParserTest {
    @Test
    fun bridgeLayoutProducesStoreMetadata() {
        val payload = bridge(
            item(
                appId = 10,
                cloudState = 1,
                hasPrice = true,
                finalPrice = 1_290L,
                originalPrice = 8_600L,
                header = "https://cdn.example/header.jpg"
            ),
            item(
                appId = 20,
                cloudState = -1,
                hasPrice = false,
                finalPrice = 0L,
                originalPrice = 0L,
                header = ""
            )
        )

        val metadata = RustStoreItemsParser.decodeForTest(
            payload = payload,
            currency = "CNY",
            fetchedAt = 456L
        )
        requireNotNull(metadata)
        assertEquals(listOf(10, 20), metadata.keys.toList())
        val paid = metadata.getValue(10)
        assertEquals("https://cdn.example/header.jpg", paid.headerImageUrl)
        assertEquals(true, paid.supportsSteamCloud)
        assertEquals("CNY", paid.price?.currency)
        assertEquals(1_290L, paid.price?.finalPriceMinor)
        assertEquals(8_600L, paid.price?.originalPriceMinor)
        assertEquals(456L, paid.price?.fetchedAt)
        val unknown = metadata.getValue(20)
        assertNull(unknown.price)
        assertNull(unknown.supportsSteamCloud)
    }

    @Test
    fun corruptBridgeIsRejected() {
        assertNull(RustStoreItemsParser.decodeForTest(byteArrayOf(1, 2, 3)))
        assertNull(
            RustStoreItemsParser.decodeForTest(
                bridge(item(10, 7, true, 1, 1, ""))
            )
        )
        assertNull(
            RustStoreItemsParser.decodeForTest(
                bridge(item(10, 0, true, -1, 1, ""))
            )
        )
        val trailing = bridge(item(10, 0, false, 0, 0, "")) + byteArrayOf(9)
        assertNull(RustStoreItemsParser.decodeForTest(trailing))
    }

    @Test
    fun duplicateAppKeepsFirstPositionAndLastValue() {
        val payload = bridge(
            item(10, 0, true, 100, 100, "old"),
            item(20, 0, false, 0, 0, "other"),
            item(10, 1, true, 300, 400, "new")
        )
        val metadata = RustStoreItemsParser.decodeForTest(payload)
        requireNotNull(metadata)
        assertEquals(listOf(10, 20), metadata.keys.toList())
        assertEquals("new", metadata.getValue(10).headerImageUrl)
        assertEquals(300L, metadata.getValue(10).price?.finalPriceMinor)
        assertTrue(metadata.getValue(10).supportsSteamCloud == true)
    }

    private fun bridge(vararg items: ByteArray): ByteArray {
        val size = 8 + items.sumOf(ByteArray::size)
        return ByteBuffer.allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte()))
            .putInt(items.size)
            .also { buffer -> items.forEach(buffer::put) }
            .array()
    }

    private fun item(
        appId: Int,
        cloudState: Int,
        hasPrice: Boolean,
        finalPrice: Long,
        originalPrice: Long,
        header: String
    ): ByteArray {
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(32 + headerBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(appId)
            .putInt(cloudState)
            .putInt(if (hasPrice) 1 else 0)
            .putLong(finalPrice)
            .putLong(originalPrice)
            .putInt(headerBytes.size)
            .put(headerBytes)
            .array()
    }
}
