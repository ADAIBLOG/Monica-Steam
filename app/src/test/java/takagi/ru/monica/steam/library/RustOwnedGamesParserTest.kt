package takagi.ru.monica.steam.library

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RustOwnedGamesParserTest {
    @Test
    fun bridgeLayoutProducesSteamGames() {
        val payload = bridge(
            game(570, 12, 345, 1_700_000_000L, "Dota 2", "abc"),
            game(730, 34, 567, 1_710_000_000L, "Counter-Strike 2", "def")
        )

        val games = RustOwnedGamesParser.decodeForTest(payload)
        requireNotNull(games)
        assertEquals(2, games.size)
        assertEquals(570, games[0].appId)
        assertEquals("Dota 2", games[0].name)
        assertEquals(12, games[0].playtimeRecentMinutes)
        assertEquals(345, games[0].playtimeForeverMinutes)
        assertEquals("abc", games[0].iconHash)
        assertEquals(1_700_000_000L, games[0].lastPlayedAt)
        assertEquals(730, games[1].appId)
    }

    @Test
    fun corruptBridgeIsRejected() {
        assertNull(RustOwnedGamesParser.decodeForTest(byteArrayOf(1, 2, 3)))
        val trailing = bridge(game(1, 0, 0, 0, "A", "")) + byteArrayOf(9)
        assertNull(RustOwnedGamesParser.decodeForTest(trailing))
    }

    private fun bridge(vararg games: ByteArray): ByteArray {
        val size = 8 + games.sumOf(ByteArray::size)
        return ByteBuffer.allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte()))
            .putInt(games.size)
            .also { buffer -> games.forEach(buffer::put) }
            .array()
    }

    private fun game(
        appId: Int,
        recent: Int,
        forever: Int,
        lastPlayedAt: Long,
        name: String,
        icon: String
    ): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val iconBytes = icon.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(28 + nameBytes.size + iconBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(appId)
            .putInt(recent)
            .putInt(forever)
            .putLong(lastPlayedAt)
            .putInt(nameBytes.size)
            .put(nameBytes)
            .putInt(iconBytes.size)
            .put(iconBytes)
            .array()
    }
}
