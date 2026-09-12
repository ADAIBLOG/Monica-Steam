package takagi.ru.monica.steam.friends.nickname.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RustFriendNicknameParserTest {
    @Test
    fun bridgeDecodesLinkedMapAndRejectsCorruption() {
        val first = item(76_561_198_000_000_002L, "Alice")
        val second = item(76_561_198_000_000_003L, "Bob")
        val payload = ByteBuffer.allocate(8 + first.size + second.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte()))
            .putInt(2)
            .put(first)
            .put(second)
            .array()

        val decoded = SteamFriendNicknameParser.decodeRustBridgeForTest(payload)
        requireNotNull(decoded)
        assertEquals(
            listOf("76561198000000002", "76561198000000003"),
            decoded.keys.toList()
        )
        assertEquals("Alice", decoded["76561198000000002"])
        assertEquals("Bob", decoded["76561198000000003"])

        assertNull(SteamFriendNicknameParser.decodeRustBridgeForTest(payload + byteArrayOf(1)))
    }

    private fun item(steamId: Long, nickname: String): ByteArray {
        val bytes = nickname.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(12 + bytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(steamId)
            .putInt(bytes.size)
            .put(bytes)
            .array()
    }
}
