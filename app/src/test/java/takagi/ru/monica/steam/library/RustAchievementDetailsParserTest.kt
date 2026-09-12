package takagi.ru.monica.steam.library

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustAchievementDetailsParserTest {
    @Test
    fun bridgeLayoutProducesAchievements() {
        val payload = bridge(
            item(
                achieved = true,
                unlockTime = 1_700_000_000L,
                apiName = "ACH_WIN",
                displayName = "Winner",
                description = "Win once",
                icon = "https://cdn.example/icon.jpg",
                lockedIcon = "https://cdn.example/icon_gray.jpg"
            ),
            item(
                achieved = false,
                unlockTime = null,
                apiName = "ACH_SECRET",
                displayName = "Secret",
                description = "Hidden",
                icon = null,
                lockedIcon = null
            )
        )

        val achievements = RustAchievementDetailsParser.decodeForTest(payload)
        requireNotNull(achievements)
        assertEquals(2, achievements.size)
        assertEquals("ACH_WIN", achievements[0].apiName)
        assertEquals("Winner", achievements[0].displayName)
        assertTrue(achievements[0].achieved)
        assertEquals(1_700_000_000L, achievements[0].unlockTimeSeconds)
        assertEquals("https://cdn.example/icon.jpg", achievements[0].iconUrl)
        assertEquals("ACH_SECRET", achievements[1].apiName)
        assertTrue(!achievements[1].achieved)
        assertNull(achievements[1].unlockTimeSeconds)
        assertNull(achievements[1].iconUrl)
    }

    @Test
    fun corruptBridgeIsRejected() {
        assertNull(RustAchievementDetailsParser.decodeForTest(byteArrayOf(1, 2, 3)))
        assertNull(
            RustAchievementDetailsParser.decodeForTest(
                bridge(item(true, 10, "A", "A", "", null, null, achievedOverride = 7))
            )
        )
        assertNull(
            RustAchievementDetailsParser.decodeForTest(
                bridge(item(true, -1, "A", "A", "", null, null))
            )
        )
        val trailing = bridge(item(false, null, "A", "A", "", null, null)) + byteArrayOf(9)
        assertNull(RustAchievementDetailsParser.decodeForTest(trailing))
    }

    private fun bridge(vararg items: ByteArray): ByteArray = ByteBuffer.allocate(
        8 + items.sumOf(ByteArray::size)
    )
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte()))
        .putInt(items.size)
        .also { buffer -> items.forEach(buffer::put) }
        .array()

    private fun item(
        achieved: Boolean,
        unlockTime: Long?,
        apiName: String,
        displayName: String,
        description: String,
        icon: String?,
        lockedIcon: String?,
        achievedOverride: Int? = null
    ): ByteArray {
        val apiBytes = apiName.toByteArray(Charsets.UTF_8)
        val displayBytes = displayName.toByteArray(Charsets.UTF_8)
        val descriptionBytes = description.toByteArray(Charsets.UTF_8)
        val iconBytes = icon?.toByteArray(Charsets.UTF_8)
        val lockedBytes = lockedIcon?.toByteArray(Charsets.UTF_8)
        val size = 16 +
            4 + apiBytes.size +
            4 + displayBytes.size +
            4 + descriptionBytes.size +
            4 + (iconBytes?.size ?: 0) +
            4 + (lockedBytes?.size ?: 0)
        return ByteBuffer.allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(achievedOverride ?: if (achieved) 1 else 0)
            .putInt(if (unlockTime != null) 1 else 0)
            .putLong(unlockTime ?: 0L)
            .putRequired(apiBytes)
            .putRequired(displayBytes)
            .putRequired(descriptionBytes)
            .putOptional(iconBytes)
            .putOptional(lockedBytes)
            .array()
    }

    private fun ByteBuffer.putRequired(bytes: ByteArray): ByteBuffer =
        putInt(bytes.size).put(bytes)

    private fun ByteBuffer.putOptional(bytes: ByteArray?): ByteBuffer = if (bytes == null) {
        putInt(-1)
    } else {
        putInt(bytes.size).put(bytes)
    }
}
