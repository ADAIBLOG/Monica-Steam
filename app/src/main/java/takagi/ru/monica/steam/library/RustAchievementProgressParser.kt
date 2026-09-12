package takagi.ru.monica.steam.library

import java.nio.ByteBuffer
import java.nio.ByteOrder
import takagi.ru.monica.steam.core.RustSteamCoreNative

/** Thin decoder for the coarse-grained Rust GetAchievementsProgress parser. */
internal object RustAchievementProgressParser {
    fun parseOrNull(response: ByteArray): Map<Int, SteamGameAchievementProgress>? {
        val payload = RustSteamCoreNative.parseAchievementProgressOrNull(response) ?: return null
        return decodeOrNull(payload)
    }

    internal fun decodeForTest(payload: ByteArray): Map<Int, SteamGameAchievementProgress>? =
        decodeOrNull(payload)

    private fun decodeOrNull(payload: ByteArray): Map<Int, SteamGameAchievementProgress>? =
        runCatching { decode(payload) }.getOrNull()

    private fun decode(payload: ByteArray): Map<Int, SteamGameAchievementProgress> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= HEADER_BYTES) {
            "Rust achievement-progress bridge is truncated"
        }
        val magic = ByteArray(4).also(buffer::get)
        require(magic.contentEquals(MAGIC)) {
            "Rust achievement-progress bridge version is unsupported"
        }
        val count = buffer.int
        require(count in 0..MAX_ITEMS) { "Rust achievement-progress count is invalid" }
        require(buffer.remaining().toLong() == count.toLong() * ITEM_BYTES) {
            "Rust achievement-progress bridge size is invalid"
        }

        val result = LinkedHashMap<Int, SteamGameAchievementProgress>(count)
        repeat(count) {
            val appId = buffer.int
            val unlocked = buffer.int
            val total = buffer.int
            val allUnlockedRaw = buffer.int
            require(appId > 0) { "Rust achievement-progress app id is invalid" }
            require(allUnlockedRaw == 0 || allUnlockedRaw == 1) {
                "Rust achievement-progress completion flag is invalid"
            }
            result[appId] = SteamGameAchievementProgress(
                appId = appId,
                unlocked = unlocked,
                total = total,
                allUnlocked = allUnlockedRaw == 1
            )
        }
        return result
    }

    private val MAGIC = byteArrayOf(
        'M'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte()
    )
    private const val HEADER_BYTES = 8
    private const val ITEM_BYTES = 16
    private const val MAX_ITEMS = 100_000
}
