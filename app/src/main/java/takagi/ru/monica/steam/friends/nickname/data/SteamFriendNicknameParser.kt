package takagi.ru.monica.steam.friends.nickname.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import takagi.ru.monica.steam.core.RustSteamCoreNative
import takagi.ru.monica.steam.network.SteamProtoReader

internal object SteamFriendNicknameParser {
    fun parse(response: ByteArray): Map<String, String> {
        RustSteamCoreNative.parseFriendNicknamesOrNull(response)
            ?.let(::decodeRustBridge)
            ?.let { return it }
        return parseKotlin(response)
    }

    internal fun decodeRustBridgeForTest(payload: ByteArray): Map<String, String>? =
        decodeRustBridge(payload)

    private fun decodeRustBridge(payload: ByteArray): Map<String, String>? = runCatching {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= RUST_HEADER_BYTES) { "Rust nickname bridge is truncated" }
        val magic = ByteArray(4).also(buffer::get)
        require(magic.contentEquals(RUST_MAGIC)) { "Rust nickname bridge version is unsupported" }
        val count = buffer.int
        require(count in 0..MAX_RUST_NICKNAMES) { "Rust nickname count is invalid" }

        val nicknames = linkedMapOf<String, String>()
        repeat(count) {
            require(buffer.remaining() >= RUST_ITEM_FIXED_BYTES) {
                "Rust nickname item is truncated"
            }
            val steamId = buffer.long.toString()
            val nickname = readRustString(buffer)
            require(nickname.isNotBlank()) { "Rust nickname is blank" }
            nicknames[steamId] = nickname
        }
        require(!buffer.hasRemaining()) { "Rust nickname bridge has trailing bytes" }
        nicknames
    }.getOrNull()

    private fun readRustString(buffer: ByteBuffer): String {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Rust nickname string is truncated" }
        val length = buffer.int
        require(length >= 0 && length <= buffer.remaining()) {
            "Rust nickname string length is invalid"
        }
        return ByteArray(length).also(buffer::get).toString(Charsets.UTF_8)
    }

    private fun parseKotlin(response: ByteArray): Map<String, String> {
        val nicknames = linkedMapOf<String, String>()
        SteamProtoReader(response).parseAll()
            .asSequence()
            .filter { it.number == NICKNAMES_FIELD }
            .mapNotNull { it.bytes }
            .forEach { encodedNickname ->
                val fields = SteamProtoReader(encodedNickname).parse()
                val accountId = fields[ACCOUNT_ID_FIELD]?.asFixed32UnsignedLong ?: return@forEach
                val nickname = fields[NICKNAME_FIELD]?.asString?.trim().orEmpty()
                if (accountId == 0L || nickname.isBlank()) return@forEach
                nicknames[(INDIVIDUAL_STEAM_ID_BASE + accountId).toString()] = nickname
            }
        return nicknames
    }

    private val RUST_MAGIC = byteArrayOf(
        'M'.code.toByte(), 'S'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte()
    )
    private const val RUST_HEADER_BYTES = 8
    private const val RUST_ITEM_FIXED_BYTES = 12
    private const val MAX_RUST_NICKNAMES = 100_000
    private const val NICKNAMES_FIELD = 1
    private const val ACCOUNT_ID_FIELD = 1
    private const val NICKNAME_FIELD = 2
    private const val INDIVIDUAL_STEAM_ID_BASE = 76_561_197_960_265_728L
}
