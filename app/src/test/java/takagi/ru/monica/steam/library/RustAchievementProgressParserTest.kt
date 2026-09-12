package takagi.ru.monica.steam.library

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustAchievementProgressParserTest {
    @Test
    fun bridgeLayoutProducesProgressMap() {
        val payload = bridge(
            item(730, 10, 10, true),
            item(570, 5, 12, false)
        )

        val progress = RustAchievementProgressParser.decodeForTest(payload)
        requireNotNull(progress)
        assertEquals(listOf(730, 570), progress.keys.toList())
        assertEquals(10, progress.getValue(730).unlocked)
        assertEquals(10, progress.getValue(730).total)
        assertTrue(progress.getValue(730).allUnlocked)
        assertEquals(5, progress.getValue(570).unlocked)
        assertEquals(12, progress.getValue(570).total)
    }

    @Test
    fun corruptBridgeIsRejected() {
        assertNull(RustAchievementProgressParser.decodeForTest(byteArrayOf(1, 2, 3)))
        val invalidFlag = bridge(item(730, 1, 2, false, flagOverride = 7))
        assertNull(RustAchievementProgressParser.decodeForTest(invalidFlag))
        val trailing = bridge(item(730, 1, 2, false)) + byteArrayOf(9)
        assertNull(RustAchievementProgressParser.decodeForTest(trailing))
    }

    private fun bridge(vararg items: ByteArray): ByteArray {
        return ByteBuffer.allocate(8 + items.size * 16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte()))
            .putInt(items.size)
            .also { buffer -> items.forEach(buffer::put) }
            .array()
    }

    private fun item(
        appId: Int,
        unlocked: Int,
        total: Int,
        allUnlocked: Boolean,
        flagOverride: Int? = null
    ): ByteArray = ByteBuffer.allocate(16)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(appId)
        .putInt(unlocked)
        .putInt(total)
        .putInt(flagOverride ?: if (allUnlocked) 1 else 0)
        .array()
}
