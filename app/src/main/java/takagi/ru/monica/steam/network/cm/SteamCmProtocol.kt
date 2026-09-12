package takagi.ru.monica.steam.network.cm

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import takagi.ru.monica.steam.core.RustSteamCoreNative
import takagi.ru.monica.steam.network.SteamProtoReader
import takagi.ru.monica.steam.network.SteamProtoWriter

internal data class SteamCmHeader(
    val steamId: Long = 0L,
    val sessionId: Int = 0,
    val jobIdSource: Long = SteamCmProtocol.JOB_ID_NONE,
    val jobIdTarget: Long = SteamCmProtocol.JOB_ID_NONE,
    val targetJobName: String? = null,
    val eResult: Int? = null,
    val transportError: Int? = null,
    val errorMessage: String? = null
)

internal data class SteamCmEnvelope(
    val eMsg: Int,
    val header: SteamCmHeader,
    val body: ByteArray
)

internal object SteamCmProtocol {
    const val EMSG_MULTI = 1
    const val EMSG_SERVICE_METHOD = 146
    const val EMSG_SERVICE_METHOD_RESPONSE = 147
    const val EMSG_SERVICE_METHOD_CALL_FROM_CLIENT = 151
    const val EMSG_SERVICE_METHOD_SEND_TO_CLIENT = 152
    const val EMSG_CLIENT_LOGON_RESPONSE = 751
    const val EMSG_CLIENT_LOGGED_OFF = 757
    const val EMSG_CLIENT_ACCOUNT_INFO = 768
    const val EMSG_CLIENT_REMOVE_FRIEND = 714
    const val EMSG_CLIENT_FRIENDS_LIST = 767
    const val EMSG_CLIENT_ADD_FRIEND = 791
    const val EMSG_CLIENT_ADD_FRIEND_RESPONSE = 792
    const val EMSG_CLIENT_HIDE_FRIEND = 5552
    const val EMSG_CLIENT_LOGON = 5514
    const val EMSG_CLIENT_GET_EMOTICON_LIST = 9330
    const val EMSG_CLIENT_EMOTICON_LIST = 9331
    const val JOB_ID_NONE = -1L
    const val WEB_PROTOCOL_VERSION = 65_580L
    const val WEB_CLIENT_OS_TYPE = 4_294_966_596L

    fun encodeMessage(
        eMsg: Int,
        steamId: Long,
        sessionId: Int,
        body: ByteArray,
        jobIdSource: Long = JOB_ID_NONE,
        jobIdTarget: Long = JOB_ID_NONE,
        targetJobName: String? = null
    ): ByteArray {
        RustSteamCoreNative.encodeCmMessageOrNull(
            eMsg = eMsg,
            steamId = steamId,
            sessionId = sessionId,
            body = body,
            jobIdSource = jobIdSource,
            jobIdTarget = jobIdTarget,
            targetJobName = targetJobName
        )?.let { return it }

        val header = SteamProtoWriter().apply {
            writeFixed64(1, steamId)
            writeVarint(2, sessionId.toLong())
            writeFixed64(10, jobIdSource)
            writeFixed64(11, jobIdTarget)
            targetJobName?.let { writeString(12, it) }
        }.toByteArray()
        return ByteBuffer.allocate(8 + header.size + body.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(eMsg or Int.MIN_VALUE)
            .putInt(header.size)
            .put(header)
            .put(body)
            .array()
    }

    fun webLogonBody(webLogonToken: String): ByteArray {
        RustSteamCoreNative.webLogonBodyOrNull(webLogonToken)?.let { return it }
        return SteamProtoWriter().apply {
            writeVarint(1, WEB_PROTOCOL_VERSION)
            writeVarint(7, WEB_CLIENT_OS_TYPE)
            writeVarint(32, 4L)
            writeVarint(33, 2L)
            writeString(80, "anonymous")
            writeString(103, webLogonToken)
        }.toByteArray()
    }

    fun decodeMessages(payload: ByteArray): List<SteamCmEnvelope> {
        RustSteamCoreNative.decodeCmMessagesOrNull(payload)
            ?.let(::decodeRustBridge)
            ?.let { return it }
        return decodeMessagesKotlin(payload, depth = 0)
    }

    private fun decodeRustBridge(payload: ByteArray): List<SteamCmEnvelope>? = runCatching {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= 8) { "Rust CM bridge is truncated" }
        val magic = ByteArray(4).also(buffer::get)
        require(magic.contentEquals(RUST_BRIDGE_MAGIC)) { "Rust CM bridge version is unsupported" }
        val count = buffer.int
        require(count >= 0 && count <= MAX_RUST_BRIDGE_MESSAGES) {
            "Rust CM bridge message count is invalid"
        }

        buildList(count) {
            repeat(count) {
                require(buffer.remaining() >= RUST_BRIDGE_FIXED_BYTES) {
                    "Rust CM bridge item is truncated"
                }
                val eMsg = buffer.int
                val steamId = buffer.long
                val sessionId = buffer.int
                val jobIdSource = buffer.long
                val jobIdTarget = buffer.long
                val eResultRaw = buffer.int
                val transportErrorRaw = buffer.int
                val targetJobName = readBridgeString(buffer)
                val errorMessage = readBridgeString(buffer)
                require(buffer.remaining() >= 4) { "Rust CM bridge body length is truncated" }
                val bodyLength = buffer.int
                require(bodyLength >= 0 && bodyLength <= buffer.remaining()) {
                    "Rust CM bridge body is invalid"
                }
                val body = ByteArray(bodyLength).also(buffer::get)
                add(
                    SteamCmEnvelope(
                        eMsg = eMsg,
                        header = SteamCmHeader(
                            steamId = steamId,
                            sessionId = sessionId,
                            jobIdSource = jobIdSource,
                            jobIdTarget = jobIdTarget,
                            targetJobName = targetJobName,
                            eResult = eResultRaw.takeUnless { it == Int.MIN_VALUE },
                            transportError = transportErrorRaw.takeUnless { it == Int.MIN_VALUE },
                            errorMessage = errorMessage
                        ),
                        body = body
                    )
                )
            }
        }.also {
            require(!buffer.hasRemaining()) { "Rust CM bridge has trailing bytes" }
        }
    }.getOrNull()

    private fun readBridgeString(buffer: ByteBuffer): String? {
        require(buffer.remaining() >= 4) { "Rust CM bridge string length is truncated" }
        val length = buffer.int
        if (length == -1) return null
        require(length >= 0 && length <= buffer.remaining()) {
            "Rust CM bridge string is invalid"
        }
        return ByteArray(length)
            .also(buffer::get)
            .toString(StandardCharsets.UTF_8)
            .takeIf(String::isNotBlank)
    }

    private fun decodeMessagesKotlin(payload: ByteArray, depth: Int): List<SteamCmEnvelope> {
        require(depth <= MAX_MULTI_DEPTH) { "Steam CM multi-message nesting is too deep" }
        val envelope = decodeSingleKotlin(payload)
        if (envelope.eMsg != EMSG_MULTI) return listOf(envelope)
        val fields = SteamProtoReader(envelope.body).parse()
        val compressedSize = fields[1]?.asLong?.coerceAtLeast(0L) ?: 0L
        val packed = fields[2]?.bytes ?: return emptyList()
        val unpacked = if (compressedSize > 0L) {
            GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
        } else {
            packed
        }
        if (compressedSize > 0L) {
            require(unpacked.size.toLong() == compressedSize) {
                "Steam CM multi-message size mismatch"
            }
        }
        val messages = mutableListOf<SteamCmEnvelope>()
        var offset = 0
        while (offset < unpacked.size) {
            require(unpacked.size - offset >= 4) { "Steam CM multi-message is truncated" }
            val length = ByteBuffer.wrap(unpacked, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            offset += 4
            require(length >= 0 && length <= unpacked.size - offset) {
                "Steam CM multi-message item is invalid"
            }
            messages += decodeMessagesKotlin(
                unpacked.copyOfRange(offset, offset + length),
                depth + 1
            )
            offset += length
        }
        return messages
    }

    private fun decodeSingleKotlin(payload: ByteArray): SteamCmEnvelope {
        require(payload.size >= 8) { "Steam CM message is too short" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val rawEMsg = buffer.int
        require(rawEMsg and Int.MIN_VALUE != 0) { "Steam CM message has no protobuf header" }
        val eMsg = rawEMsg and Int.MAX_VALUE
        val headerLength = buffer.int
        require(headerLength >= 0 && headerLength <= buffer.remaining()) {
            "Steam CM protobuf header is invalid"
        }
        val headerBytes = ByteArray(headerLength).also(buffer::get)
        val body = ByteArray(buffer.remaining()).also(buffer::get)
        val fields = SteamProtoReader(headerBytes).parse()
        return SteamCmEnvelope(
            eMsg = eMsg,
            header = SteamCmHeader(
                steamId = fields[1]?.asFixed64 ?: 0L,
                sessionId = fields[2]?.asInt ?: 0,
                jobIdSource = fields[10]?.asFixed64 ?: JOB_ID_NONE,
                jobIdTarget = fields[11]?.asFixed64 ?: JOB_ID_NONE,
                targetJobName = fields[12]?.asString?.takeIf(String::isNotBlank),
                eResult = fields[13]?.asInt,
                errorMessage = fields[14]?.asString?.takeIf(String::isNotBlank),
                transportError = fields[17]?.asInt
            ),
            body = body
        )
    }

    private val RUST_BRIDGE_MAGIC = byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
    private const val RUST_BRIDGE_FIXED_BYTES = 40
    private const val MAX_RUST_BRIDGE_MESSAGES = 100_000
    private const val MAX_MULTI_DEPTH = 2
}
