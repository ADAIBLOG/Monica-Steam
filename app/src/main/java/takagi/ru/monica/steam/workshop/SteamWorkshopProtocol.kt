package takagi.ru.monica.steam.workshop

import takagi.ru.monica.steam.network.SteamProtoField
import takagi.ru.monica.steam.network.SteamProtoReader
import takagi.ru.monica.steam.network.SteamProtoWriter

/** Field numbers from steammessages_publishedfile.steamclient.proto. */
internal object SteamWorkshopProtocol {
    const val SUBSCRIPTION_PAGE_SIZE = 30

    fun subscriptionRequest(item: WorkshopItem, subscribe: Boolean, dependencies: Boolean) = SteamProtoWriter().apply {
        writeUint64(1, item.id)
        writeVarint(2, 1) // k_EPublishedFileUserList_Subscribed
        writeVarint(3, item.appId.toLong())
        writeBool(4, true) // Notify the desktop Steam client.
        if (subscribe) writeBool(5, dependencies)
    }

    fun statusRequest(appId: Int, ids: List<String>) = SteamProtoWriter().apply {
        writeVarint(1, appId.toLong())
        ids.forEach { writeFixed64(2, it.toULong().toLong()) }
        writeVarint(3, 1)
    }

    fun status(bytes: ByteArray, id: String): Boolean {
        val entry = SteamProtoReader(bytes).parseAll().filter { it.number == 1 }.map { it.nested() }
            .firstOrNull { it[1]?.asFixed64UnsignedString == id }
            ?: throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
        return entry[2]?.asBool ?: false
    }

    fun details(bytes: ByteArray, appId: Int, id: String): WorkshopItem {
        val entry = SteamProtoReader(bytes).parseAll().firstOrNull { it.number == 1 }
            ?: throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
        return item(entry.bytes ?: byteArrayOf(), appId).also {
            if (it.id != id) throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
        }
    }

    fun items(bytes: ByteArray, appId: Int, ids: List<String>): List<WorkshopItem> {
        val rows = SteamProtoReader(bytes).parseAll().filter { it.number == 1 }
        if (rows.isEmpty()) throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
        return rows.mapNotNull { row ->
            if (row.wireType != 2) throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
            val fields = row.nested()
            val result = fields[1]?.takeIf { it.wireType == 0 }?.varint
                ?: throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
            if (result != 1L) return@mapNotNull null
            // A shared ID may belong to another game or a removed item.
            val game = fields[5]?.takeIf { it.wireType == 0 }?.asInt
                ?: throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
            if (game != appId) return@mapNotNull null
            item(requireNotNull(row.bytes), appId).also {
                if (it.id !in ids) throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
            }
        }.distinctBy { it.id }
    }

    fun statuses(bytes: ByteArray, ids: List<String>): Map<String, Boolean> {
        val values = SteamProtoReader(bytes).parseAll().filter { it.number == 1 }.map { row ->
            if (row.wireType != 2) throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
            val fields = row.nested()
            val id = fields[1]?.takeIf { it.wireType == 1 }?.asFixed64UnsignedString
            val status = fields[2]
            if (id !in ids || (status != null && (status.wireType != 0 || status.varint !in 0L..1L))) {
                throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
            }
            requireNotNull(id) to (status?.asBool ?: false)
        }
        if (values.size != ids.size || values.map { it.first }.toSet() != ids.toSet()) {
            throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
        }
        return values.toMap()
    }

    fun userFiles(bytes: ByteArray, appId: Int, page: Int): WorkshopBatch {
        require(appId > 0 && page > 0)
        val fields = SteamProtoReader(bytes).parseAll()
        // Both counts are optional uint32 fields with a zero default. The CM
        // envelope already validated EResult, so an empty body is an empty list.
        val total = fields.countField(1)
        val start = fields.countField(2)
        val rows = fields.filter { it.number == 3 }
        // Steam startindex is one-based: pages begin at 1, 31, 61, ... .
        // Keep the consumed-row count zero-based when deciding whether to load more.
        val offset = (page - 1L) * SUBSCRIPTION_PAGE_SIZE
        if (rows.isNotEmpty() && (total == 0 || start.toLong() != offset + 1)) {
            throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
        }
        // Steam may include deleted/private entries in the subscription list.  A
        // single malformed detail must not make the whole page unreadable.
        var malformedRows = 0
        val subscriptionIds = linkedSetOf<String>()
        val items = rows.mapNotNull { row ->
            try {
                if (row.wireType != 2) throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
                val detail = row.nested()
                val rowId = detail[2]?.asLong?.toULong()?.toString()
                if (rowId != null && rowId != "0" && (detail[5] == null || detail[5]?.asInt == appId)) subscriptionIds += rowId
                item(requireNotNull(row.bytes), appId).copy(subscribed = true)
            } catch (error: WorkshopException) {
                if (error.reason != WorkshopFailure.UNAVAILABLE) malformedRows++
                null
            } catch (_: Exception) {
                malformedRows++
                null
            }
        }
        // A changed schema must not look like an empty subscription library.
        if (items.isEmpty() && malformedRows > 0) throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
        return WorkshopBatch(items, page, rows.isNotEmpty() && offset + rows.size < total, total,
            subscriptionIds = subscriptionIds.toList())
    }

    fun dependencies(bytes: ByteArray, appId: Int, ids: List<String>): List<WorkshopItem> =
        SteamProtoReader(bytes).parseAll().filter { it.number == 1 && it.nested()[1]?.asInt == 1 }
            .filter { it.nested()[5]?.asInt == appId }
            .map { item(it.bytes ?: byteArrayOf(), appId) }.filter { it.id in ids }

    private fun item(bytes: ByteArray, appId: Int): WorkshopItem {
        val all = SteamProtoReader(bytes).parseAll()
        val f = all.associateBy { it.number }
        val result = f[1]?.takeIf { it.wireType == 0 }?.varint
            ?: throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
        if (result != 1L) throw WorkshopException(WorkshopFailure.UNAVAILABLE)
        val id = f[2]?.asLong?.toULong()?.toString()
        val title = f[16]?.asString.orEmpty()
        if (id == null || id == "0" || f[5]?.asInt != appId || title.isBlank()) {
            throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
        }
        return WorkshopItem(
            id = id, appId = appId, title = title, author = f[3]?.asFixed64UnsignedString.orEmpty(),
            preview = f[11]?.asString.orEmpty(), description = f[17]?.asString ?: f[18]?.asString.orEmpty(),
            tags = all.filter { it.number == 52 }.map { it.nested().let { t -> t[3]?.asString ?: t[1]?.asString.orEmpty() } },
            subscriptions = f[36]?.asLong ?: 0, updated = f[20]?.asLong ?: 0, size = f[8]?.asLong ?: 0,
            created = f[19]?.asLong ?: 0,
            fileType = f[34]?.asInt ?: 0, canSubscribe = f[35]?.asBool == true,
            children = all.filter { it.number == 53 }.mapNotNull { it.nested()[1]?.asLong?.toULong()?.toString() },
            childCount = f[49]?.asInt ?: 0,
            previews = all.filter { it.number == 51 }.mapNotNull {
                val p = it.nested()
                if ((p[7]?.asInt ?: 0) == 0) p[3]?.asString else null
            },
            banned = f[28]?.asBool == true, incompatible = f[32]?.asBool == true
        )
    }

    private fun SteamProtoField.nested(): Map<Int, SteamProtoField> =
        SteamProtoReader(bytes ?: byteArrayOf()).parse()

    private fun List<SteamProtoField>.countField(number: Int): Int {
        val field = lastOrNull { it.number == number } ?: return 0
        val value = field.varint
        if (field.wireType != 0 || value == null || value !in 0..Int.MAX_VALUE.toLong()) {
            throw WorkshopException(WorkshopFailure.INVALID_RESPONSE)
        }
        return value.toInt()
    }
}
