package takagi.ru.monica.steam.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamProtoTest {
    @Test
    fun signedVarintsPreserveTheir64BitBitPatterns() {
        val bytes = SteamProtoWriter().apply {
            writeVarint(1, -1L)
            writeVarint(2, Long.MIN_VALUE)
            writeVarint(3, Long.MAX_VALUE)
        }.toByteArray()

        val fields = SteamProtoReader(bytes).parse()
        assertEquals(-1L, fields[1]?.asLong)
        assertEquals(Long.MIN_VALUE, fields[2]?.asLong)
        assertEquals(Long.MAX_VALUE, fields[3]?.asLong)
    }

    @Test
    fun uint64DecimalStringSupportsTheFullUnsignedRange() {
        val bytes = SteamProtoWriter().apply {
            writeUint64(1, ULong.MAX_VALUE.toString())
        }.toByteArray()

        val field = SteamProtoReader(bytes).parse()[1]
        assertEquals(-1L, field?.asLong)
        assertThrows(IllegalArgumentException::class.java) {
            SteamProtoWriter().writeUint64(1, "18446744073709551616")
        }
    }

    @Test
    fun fixed64AvoidsBigIntegerWhilePreservingSignedAndUnsignedViews() {
        val bytes = SteamProtoWriter().apply {
            writeFixed64(1, -1L)
            writeFixed64(2, Long.MIN_VALUE)
        }.toByteArray()
        val fields = SteamProtoReader(bytes).parse()

        assertEquals(-1L, fields[1]?.asFixed64)
        assertEquals(ULong.MAX_VALUE.toString(), fields[1]?.asFixed64UnsignedString)
        assertEquals(Long.MIN_VALUE, fields[2]?.asFixed64)
        assertEquals(Long.MIN_VALUE.toULong().toString(), fields[2]?.asFixed64UnsignedString)
    }

    @Test
    fun packedVarintsUsePrimitive64BitDecoding() {
        val bytes = SteamProtoWriter().apply {
            writePackedVarints(1, listOf(1L, 127L, 128L, -1L))
        }.toByteArray()
        val packed = SteamProtoReader(bytes).parse()[1]?.bytes
        requireNotNull(packed)

        assertEquals(
            listOf(1L, 127L, 128L, -1L),
            SteamProtoReader.decodePackedVarints(packed)
        )
    }

    @Test
    fun malformedVarintsAndLengthsAreRejectedBeforeCopying() {
        assertThrows(IllegalArgumentException::class.java) {
            SteamProtoReader(byteArrayOf(0x08, 0x80.toByte())).parse()
        }
        assertThrows(IllegalArgumentException::class.java) {
            SteamProtoReader(byteArrayOf(0x0a, 0x05, 0x01)).parse()
        }
        assertThrows(IllegalArgumentException::class.java) {
            SteamProtoReader(byteArrayOf(0x08) + ByteArray(10) { 0xff.toByte() }).parse()
        }
    }

    @Test
    fun primitiveEncodingStaysCompatibleWithKnownVarintBytes() {
        val bytes = SteamProtoWriter().apply { writeVarint(1, 300L) }.toByteArray()
        assertTrue(bytes.contentEquals(byteArrayOf(0x08, 0xac.toByte(), 0x02)))
    }
}
