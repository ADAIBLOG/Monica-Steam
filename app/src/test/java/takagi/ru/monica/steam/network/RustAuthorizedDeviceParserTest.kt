package takagi.ru.monica.steam.network

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustAuthorizedDeviceParserTest {
    @Test
    fun decodePreservesUnsignedTokenAndNestedUsage() {
        val payload = bridge(
            record(
                tokenId = -2L,
                description = "This phone",
                platformType = 3,
                loggedIn = true,
                isCurrent = true,
                firstSeen = Usage(10L, "US", "CA", "San Francisco"),
                lastSeen = Usage(20L, "US", "WA", "Seattle")
            ),
            record(
                tokenId = 42L,
                description = "Old phone",
                platformType = 2,
                loggedIn = false,
                isCurrent = false,
                firstSeen = null,
                lastSeen = null
            )
        )

        val decoded = requireNotNull(RustAuthorizedDeviceParser.decodeForTest(payload))

        assertEquals(2, decoded.size)
        val current = decoded[0]
        assertEquals("18446744073709551614", current.tokenId)
        assertEquals("This phone", current.description)
        assertEquals(3, current.platformType)
        assertTrue(current.loggedIn)
        assertTrue(current.isCurrent)
        assertEquals("San Francisco", current.firstSeen?.city)
        assertEquals(20L, current.lastSeen?.timeSeconds)

        val old = decoded[1]
        assertEquals("42", old.tokenId)
        assertFalse(old.loggedIn)
        assertFalse(old.isCurrent)
        assertNull(old.firstSeen)
        assertNull(old.lastSeen)
    }

    @Test
    fun absentTokenRemainsBlankInsteadOfBecomingZero() {
        val decoded = requireNotNull(
            RustAuthorizedDeviceParser.decodeForTest(
                bridge(
                    record(
                        tokenId = null,
                        description = "Description only",
                        platformType = 0,
                        loggedIn = false,
                        isCurrent = false,
                        firstSeen = null,
                        lastSeen = null
                    )
                )
            )
        )
        assertEquals("", decoded.single().tokenId)
    }

    @Test
    fun invalidOrTruncatedBridgeFallsBack() {
        assertNull(RustAuthorizedDeviceParser.decodeForTest(byteArrayOf(1, 2, 3)))

        val wrongMagic = bridge(
            record(1L, "Phone", 1, false, false, null, null)
        ).also { it[0] = 'X'.code.toByte() }
        assertNull(RustAuthorizedDeviceParser.decodeForTest(wrongMagic))

        val valid = bridge(record(1L, "Phone", 1, false, false, null, null))
        assertNull(RustAuthorizedDeviceParser.decodeForTest(valid.copyOf(valid.size - 1)))
    }

    private fun bridge(vararg records: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte()))
        out.writeInt(records.size)
        records.forEach(out::write)
        return out.toByteArray()
    }

    private fun record(
        tokenId: Long?,
        description: String,
        platformType: Int,
        loggedIn: Boolean,
        isCurrent: Boolean,
        firstSeen: Usage?,
        lastSeen: Usage?
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeInt(if (tokenId != null) 1 else 0)
        out.writeLong(tokenId ?: 0L)
        out.writeInt(platformType)
        out.writeInt(if (loggedIn) 1 else 0)
        out.writeInt(if (isCurrent) 1 else 0)
        out.writeString(description)
        out.writeUsage(firstSeen)
        out.writeUsage(lastSeen)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeUsage(usage: Usage?) {
        writeInt(if (usage != null) 1 else 0)
        if (usage == null) return
        writeLong(usage.timeSeconds)
        writeString(usage.country)
        writeString(usage.state)
        writeString(usage.city)
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

    private data class Usage(
        val timeSeconds: Long,
        val country: String,
        val state: String,
        val city: String
    )
}
