package takagi.ru.monica.steam.library

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import takagi.ru.monica.steam.core.RustSteamCoreNative

/** Thin decoder for the coarse-grained Rust GetOwnedGames parser. */
internal object RustOwnedGamesParser {
    fun parseOrNull(response: ByteArray): List<SteamGame>? {
        val payload = RustSteamCoreNative.parseOwnedGamesOrNull(response) ?: return null
        return decodeOrNull(payload)
    }

    internal fun decodeForTest(payload: ByteArray): List<SteamGame>? = decodeOrNull(payload)

    private fun decodeOrNull(payload: ByteArray): List<SteamGame>? =
        runCatching { decode(payload) }.getOrNull()

    private fun decode(payload: ByteArray): List<SteamGame> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= HEADER_BYTES) { "Rust owned-games bridge is truncated" }
        val magic = ByteArray(4).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Rust owned-games bridge version is unsupported" }
        val count = buffer.int
        require(count in 0..MAX_GAMES) { "Rust owned-games count is invalid" }

        val games = buildList(count) {
            repeat(count) {
                require(buffer.remaining() >= FIXED_ITEM_BYTES) {
                    "Rust owned-games item is truncated"
                }
                val appId = buffer.int
                val recentMinutes = buffer.int
                val foreverMinutes = buffer.int
                val lastPlayedAt = buffer.long
                val name = readString(buffer)
                val iconHash = readString(buffer)
                add(
                    SteamGame(
                        appId = appId,
                        name = name,
                        playtimeForeverMinutes = foreverMinutes,
                        playtimeRecentMinutes = recentMinutes,
                        iconHash = iconHash,
                        lastPlayedAt = lastPlayedAt
                    )
                )
            }
        }
        require(!buffer.hasRemaining()) { "Rust owned-games bridge has trailing bytes" }
        return games
    }

    private fun readString(buffer: ByteBuffer): String {
        require(buffer.remaining() >= Int.SIZE_BYTES) {
            "Rust owned-games string length is truncated"
        }
        val length = buffer.int
        require(length >= 0 && length <= buffer.remaining()) {
            "Rust owned-games string is invalid"
        }
        val bytes = ByteArray(length).also(buffer::get)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private val MAGIC = byteArrayOf(
        'M'.code.toByte(), 'S'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte()
    )
    private const val HEADER_BYTES = 8
    private const val FIXED_ITEM_BYTES = 20
    private const val MAX_GAMES = 100_000
}
