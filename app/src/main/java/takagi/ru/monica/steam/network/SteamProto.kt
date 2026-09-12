package takagi.ru.monica.steam.network

import java.io.ByteArrayOutputStream

class SteamProtoWriter private constructor(initialPayload: ByteArray?) {
    private val out = ByteArrayOutputStream()

    init {
        initialPayload?.let(out::write)
    }

    constructor() : this(null)

    fun toByteArray(): ByteArray = out.toByteArray()

    fun writeVarint(field: Int, value: Long) {
        writeTag(field, 0)
        writeVarintRaw(value)
    }

    fun writeUint64(field: Int, value: Long) {
        writeVarint(field, value)
    }

    fun writeUint64(field: Int, value: String) {
        val unsigned = value.toULongOrNull()
            ?: throw IllegalArgumentException("uint64 value is out of range")
        writeTag(field, 0)
        writeVarintRaw(unsigned)
    }

    fun writeBool(field: Int, value: Boolean) {
        writeVarint(field, if (value) 1L else 0L)
    }

    fun writeString(field: Int, value: String) {
        writeBytes(field, value.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(field: Int, bytes: ByteArray) {
        writeTag(field, 2)
        writeVarintRaw(bytes.size.toLong())
        out.write(bytes)
    }

    fun writePackedVarints(field: Int, values: Iterable<Long>) {
        val packed = SteamProtoWriter()
        values.forEach(packed::writeVarintValue)
        writeBytes(field, packed.toByteArray())
    }

    fun writeMessage(field: Int, message: SteamProtoWriter) {
        writeBytes(field, message.toByteArray())
    }

    fun writeFixed64(field: Int, value: Long) {
        writeTag(field, 1)
        var current = value
        repeat(8) {
            out.write((current and 0xffL).toInt())
            current = current shr 8
        }
    }

    fun writeFixed32(field: Int, value: Long) {
        writeTag(field, 5)
        var current = value
        repeat(4) {
            out.write((current and 0xffL).toInt())
            current = current shr 8
        }
    }

    private fun writeTag(field: Int, wireType: Int) {
        require(field > 0) { "protobuf field number must be positive" }
        writeVarintRaw(((field shl 3) or wireType).toLong())
    }

    private fun writeVarintValue(value: Long) {
        writeVarintRaw(value)
    }

    private fun writeVarintRaw(value: Long) {
        writeVarintRaw(value.toULong())
    }

    private fun writeVarintRaw(initial: ULong) {
        var current = initial
        while (current >= 0x80uL) {
            out.write(((current and 0x7fuL).toInt()) or 0x80)
            current = current shr 7
        }
        out.write(current.toInt())
    }

    companion object {
        /** Wrap an already encoded protobuf message without re-encoding its fields. */
        fun fromEncoded(payload: ByteArray): SteamProtoWriter = SteamProtoWriter(payload)
    }
}

data class SteamProtoField(
    val number: Int,
    val wireType: Int,
    val varint: Long? = null,
    val bytes: ByteArray? = null
) {
    val asString: String
        get() = bytes?.toString(Charsets.UTF_8).orEmpty()

    val asInt: Int
        get() = varint?.toInt() ?: 0

    val asLong: Long
        get() = varint ?: 0L

    val asBool: Boolean
        get() = (varint ?: 0L) != 0L

    val asFixed64: Long
        get() {
            val fixed = bytes ?: return 0L
            if (fixed.size < 8) return 0L
            var value = 0L
            for (index in 7 downTo 0) {
                value = (value shl 8) or (fixed[index].toInt() and 0xff).toLong()
            }
            return value
        }

    val asFixed64UnsignedString: String
        get() {
            val fixed = bytes ?: return "0"
            if (fixed.size < 8) return "0"
            var value = 0uL
            for (index in 7 downTo 0) {
                value = (value shl 8) or (fixed[index].toInt() and 0xff).toULong()
            }
            return value.toString()
        }

    val asFixed32UnsignedLong: Long
        get() {
            val fixed = bytes ?: return 0L
            if (fixed.size < 4) return 0L
            var value = 0L
            for (index in 3 downTo 0) {
                value = (value shl 8) or (fixed[index].toInt() and 0xff).toLong()
            }
            return value
        }
}

class SteamProtoReader(private val data: ByteArray) {
    private var pos = 0

    fun parseAll(): List<SteamProtoField> {
        val fields = ArrayList<SteamProtoField>()
        while (pos < data.size) {
            val key = readVarintRaw()
            val field = (key ushr 3).toInt()
            require(field > 0) { "Invalid protobuf field number" }
            val wire = (key and 0x7L).toInt()
            when (wire) {
                0 -> fields += SteamProtoField(field, wire, varint = readVarintRaw())
                1 -> fields += SteamProtoField(field, wire, bytes = readFixed(8))
                2 -> {
                    val length = readVarintRaw()
                    require(length >= 0L && length <= Int.MAX_VALUE.toLong()) {
                        "Invalid protobuf field length"
                    }
                    fields += SteamProtoField(field, wire, bytes = readBytes(length.toInt()))
                }
                5 -> fields += SteamProtoField(field, wire, bytes = readFixed(4))
                else -> error("Unsupported protobuf wire type $wire")
            }
        }
        return fields
    }

    fun parse(): Map<Int, SteamProtoField> = parseAll().associateBy { it.number }

    private fun readVarintRaw(): Long {
        var result = 0L
        var shift = 0
        repeat(10) { index ->
            require(pos < data.size) { "Truncated protobuf varint" }
            val byte = data[pos++].toInt() and 0xff
            if (index == 9) {
                require(byte <= 1) { "Protobuf varint exceeds 64 bits" }
            }
            result = result or ((byte and 0x7f).toLong() shl shift)
            if ((byte and 0x80) == 0) return result
            shift += 7
        }
        error("Protobuf varint exceeds 64 bits")
    }

    private fun readBytes(length: Int): ByteArray {
        require(length >= 0 && length <= data.size - pos) { "Truncated protobuf field" }
        val end = pos + length
        val bytes = data.copyOfRange(pos, end)
        pos = end
        return bytes
    }

    private fun readFixed(length: Int): ByteArray = readBytes(length)

    companion object {
        fun decodePackedVarints(bytes: ByteArray): List<Long> {
            val reader = SteamProtoReader(bytes)
            val values = ArrayList<Long>()
            while (reader.pos < bytes.size) {
                values += reader.readVarintRaw()
            }
            return values
        }
    }
}
