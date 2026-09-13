package takagi.ru.monica.steam.workshop

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.steam.data.SteamAccount
import takagi.ru.monica.steam.network.*
import takagi.ru.monica.steam.network.cm.SteamCmGateway

class SteamWorkshopBatchProtocolTest {
    @Test fun inspectionUsesOneDetailsRequestAndOneAccountStatusRequestForTheWholeChunk() = runTest {
        val methods = mutableListOf<String>()
        val cm = object : SteamCmGateway {
            override fun callService(account: SteamAccount, method: String, request: ByteArray): ByteArray {
                methods += method
                val fields = SteamProtoReader(request).parseAll()
                return if (method == "PublishedFile.GetDetails#1") {
                    assertEquals(listOf("1", "2", "3"), fields.filter { it.number == 1 }.map { it.asFixed64UnsignedString })
                    assertEquals(570, fields.first { it.number == 14 }.asInt)
                    SteamProtoWriter().apply {
                        writeMessage(1, detail("1", 570)); writeMessage(1, detail("2", 570))
                        writeMessage(1, detail("3", 550))
                    }.toByteArray()
                } else {
                    assertEquals("PublishedFile.AreFilesInSubscriptionList#1", method)
                    assertEquals(listOf("1", "2"), fields.filter { it.number == 2 }.map { it.asFixed64UnsignedString })
                    SteamProtoWriter().apply {
                        writeMessage(1, status(1, false)); writeMessage(1, status(2, true))
                    }.toByteArray()
                }
            }
            override fun exchangeClientMessage(account: SteamAccount, requestEMsg: Int, responseEMsg: Int, request: ByteArray): ByteArray = error("No client messages")
        }
        val service = SteamWorkshopService(SteamApiClient(OkHttpClient.Builder().addInterceptor { error("No Web API") }.build()),
            StandardTestDispatcher(testScheduler), cm)
        val items = service.inspectItems(SteamWorkshopViewModelTest.account(), 570, listOf("1", "2", "3"))
        assertEquals(listOf("1", "2"), items.map { it.id })
        assertEquals(listOf(false, true), items.map { it.subscribed })
        assertEquals(2, methods.size)
    }

    @Test fun missingOrInvalidStatusIsNeverAssumedToMeanUnsubscribed() {
        val incomplete = SteamProtoWriter().apply { writeMessage(1, status(1, false)) }.toByteArray()
        assertThrows(WorkshopException::class.java) { SteamWorkshopProtocol.statuses(incomplete, listOf("1", "2")) }
        for (row in listOf(
            SteamProtoWriter().apply { writeFixed64(1, 1); writeString(2, "false") },
            SteamProtoWriter().apply { writeFixed64(1, 1); writeVarint(2, 2) },
            SteamProtoWriter().apply { writeFixed64(1, 999); writeBool(2, false) }
        )) assertThrows(WorkshopException::class.java) {
            SteamWorkshopProtocol.statuses(SteamProtoWriter().apply { writeMessage(1, row) }.toByteArray(), listOf("1"))
        }
    }

    @Test fun unavailableDetailsAreIsolatedButMissingResultIsMalformed() {
        val unavailable = SteamProtoWriter().apply {
            writeMessage(1, SteamProtoWriter().apply { writeVarint(1, 9); writeUint64(2, "1") })
            writeMessage(1, detail("2", 570))
        }.toByteArray()
        assertEquals(listOf("2"), SteamWorkshopProtocol.items(unavailable, 570, listOf("1", "2")).map { it.id })
        val broken = SteamProtoWriter().apply {
            writeMessage(1, SteamProtoWriter().apply { writeUint64(2, "1"); writeVarint(5, 570); writeString(16, "Mod") })
        }.toByteArray()
        assertThrows(WorkshopException::class.java) { SteamWorkshopProtocol.items(broken, 570, listOf("1")) }
    }

    @Test fun subscriptionExportRetainsKnownIdsEvenWhenDetailsAreUnavailable() {
        val response = SteamProtoWriter().apply {
            writeVarint(1, 2); writeVarint(2, 1)
            writeMessage(3, detail("1", 570))
            writeMessage(3, SteamProtoWriter().apply { writeVarint(1, 9); writeUint64(2, "2") })
        }.toByteArray()
        val batch = SteamWorkshopProtocol.userFiles(response, 570, 1)
        assertEquals(listOf("1"), batch.items.map { it.id })
        assertEquals(listOf("1", "2"), batch.subscriptionIds)
        assertFalse(batch.hasMore)
    }

    private fun detail(id: String, appId: Int) = SteamProtoWriter().apply {
        writeVarint(1, 1); writeUint64(2, id); writeVarint(5, appId.toLong()); writeString(16, "Mod $id"); writeBool(35, true)
    }
    private fun status(id: Long, subscribed: Boolean) = SteamProtoWriter().apply { writeFixed64(1, id); writeBool(2, subscribed) }
}
