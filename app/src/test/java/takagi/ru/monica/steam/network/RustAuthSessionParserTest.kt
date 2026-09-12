package takagi.ru.monica.steam.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustAuthSessionParserTest {
    @Test
    fun clientIdsDecodeInOrderAndRejectTrailingBytes() {
        val payload = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf('M'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte()))
            .putInt(2)
            .putLong(7L)
            .putLong(-1L)
            .array()

        assertEquals(listOf(7L, -1L), RustAuthSessionParser.decodeClientIdsForTest(payload))
        assertNull(RustAuthSessionParser.decodeClientIdsForTest(payload + byteArrayOf(1)))
    }

    @Test
    fun sessionInfoDecodesStringsAndVersion() {
        val payload = sessionInfoBridge(3, "127.0.0.1", "Guangzhou", "CN", "Android")
        val info = RustAuthSessionParser.decodeSessionInfoForTest(payload)
        requireNotNull(info)
        assertEquals(3, info.version)
        assertEquals("127.0.0.1", info.ip)
        assertEquals("Guangzhou", info.city)
        assertEquals("CN", info.country)
        assertEquals("Android", info.deviceName)
    }

    @Test
    fun confirmationBridgeAcceptsOnlyBooleanFlags() {
        fun payload(value: Int) = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf('M'.code.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte()))
            .putInt(value)
            .array()

        assertTrue(RustAuthSessionParser.decodeConfirmationForTest(payload(1)) == true)
        assertFalse(RustAuthSessionParser.decodeConfirmationForTest(payload(0)) == true)
        assertNull(RustAuthSessionParser.decodeConfirmationForTest(payload(7)))
    }

    private fun sessionInfoBridge(
        version: Int,
        ip: String,
        city: String,
        country: String,
        device: String
    ): ByteArray {
        val strings = listOf(ip, city, country, device).map { it.toByteArray(Charsets.UTF_8) }
        return ByteBuffer.allocate(8 + strings.sumOf { 4 + it.size })
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf('M'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte()))
            .putInt(version)
            .also { buffer ->
                strings.forEach { bytes -> buffer.putInt(bytes.size).put(bytes) }
            }
            .array()
    }
}
