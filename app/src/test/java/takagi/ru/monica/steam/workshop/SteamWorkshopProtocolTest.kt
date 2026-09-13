package takagi.ru.monica.steam.workshop

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.steam.network.SteamProtoWriter

class SteamWorkshopProtocolTest {
    @Test fun liveSteamOneBasedIndexLoadsTheDotaSubscription() {
        // Reduced from the real Dota 2 CM response: total=1, startindex=1.
        val response = SteamProtoWriter().apply {
            writeVarint(1, 1)
            writeVarint(2, 1)
            writeMessage(3, detail("3791709672", "LLG-bots", 570))
        }
        val batch = SteamWorkshopProtocol.userFiles(response.toByteArray(), 570, 1)
        assertEquals(listOf("3791709672"), batch.items.map { it.id })
        assertEquals(true, batch.items.single().subscribed)
        assertEquals(1, batch.total)
        assertFalse(batch.hasMore)
    }

    @Test fun oneBasedPaginationKeepsTheThirtyFirstSubscriptionReachable() {
        val first = SteamProtoWriter().apply {
            writeVarint(1, 31)
            writeVarint(2, 1)
            repeat(30) { writeMessage(3, detail((it + 1).toString(), "Mod $it")) }
        }
        val last = SteamProtoWriter().apply {
            writeVarint(1, 31)
            writeVarint(2, 31)
            writeMessage(3, detail("31", "Last mod"))
        }
        val firstBatch = SteamWorkshopProtocol.userFiles(first.toByteArray(), 550, 1)
        val lastBatch = SteamWorkshopProtocol.userFiles(last.toByteArray(), 550, 2)
        assertEquals(30, firstBatch.items.size)
        assertTrue(firstBatch.hasMore)
        assertEquals(listOf("31"), lastBatch.items.map { it.id })
        assertEquals(2, lastBatch.page)
        assertFalse(lastBatch.hasMore)
    }

    @Test fun successfulEmptySubscriptionResponseUsesProtobufDefaults() {
        // GetUserFiles_Response has no required fields: total/startindex default to zero.
        // The CM envelope has already checked EResult before this body is decoded.
        for (payload in listOf(byteArrayOf(), SteamProtoWriter().apply { writeVarint(2, 0) }.toByteArray(),
            byteArrayOf(0x08, 0x00, 0x10, 0x01))) {
            val batch = SteamWorkshopProtocol.userFiles(payload, 550, 1)
            assertEquals(0, batch.total)
            assertEquals(1, batch.page)
            assertTrue(batch.items.isEmpty())
            assertFalse(batch.hasMore)
        }
    }

    @Test fun explicitEmptySubscriptionResponseStillWorks() {
        val response = SteamProtoWriter().apply { writeVarint(1, 0); writeVarint(2, 31) }
        val batch = SteamWorkshopProtocol.userFiles(response.toByteArray(), 550, 2)
        assertEquals(0, batch.total)
        assertEquals(2, batch.page)
        assertFalse(batch.hasMore)
    }

    @Test fun unavailableAndMalformedDetailsDoNotHideReadableSubscriptions() {
        val response = SteamProtoWriter().apply {
            writeVarint(1, 31)
            writeVarint(2, 1)
            writeMessage(3, detail("123", "Visible mod"))
            writeMessage(3, SteamProtoWriter().apply { writeVarint(1, 9); writeUint64(2, "124") })
            // A corrupt nested detail must be isolated to that row, not the entire page.
            writeBytes(3, byteArrayOf(0x80.toByte()))
        }
        val batch = SteamWorkshopProtocol.userFiles(response.toByteArray(), 550, 1)
        assertEquals(listOf("123"), batch.items.map { it.id })
        assertEquals(true, batch.items.single().subscribed)
        assertTrue(batch.hasMore)
        assertEquals(31, batch.total)
    }

    @Test fun unavailableRowsStillCountTowardsTheEndOfPagination() {
        val response = SteamProtoWriter().apply {
            writeVarint(1, 32)
            writeVarint(2, 31)
            repeat(2) { writeMessage(3, SteamProtoWriter().apply { writeVarint(1, 9) }) }
        }
        val batch = SteamWorkshopProtocol.userFiles(response.toByteArray(), 550, 2)
        assertTrue(batch.items.isEmpty())
        assertFalse(batch.hasMore)
        assertEquals(32, batch.total)
    }

    @Test fun missingTotalsWithRowsAndInvalidCountWireTypesAreNotEmptyLists() {
        val responses = listOf(
            SteamProtoWriter().apply { writeVarint(2, 1); writeMessage(3, detail("123", "Mod")) },
            SteamProtoWriter().apply { writeString(1, "not a count") },
            SteamProtoWriter().apply { writeVarint(1, 1); writeString(2, "not an offset") }
        )
        for (response in responses) {
            val failure = assertThrows(WorkshopException::class.java) {
                SteamWorkshopProtocol.userFiles(response.toByteArray(), 550, 1)
            }
            assertEquals(WorkshopFailure.INVALID_RESPONSE, failure.reason)
        }
    }

    @Test fun mismatchedPageAndTruncatedEnvelopeAreRejected() {
        val wrongPage = SteamProtoWriter().apply {
            writeVarint(1, 60); writeVarint(2, 31); writeMessage(3, detail("123", "Mod"))
        }
        assertThrows(WorkshopException::class.java) {
            SteamWorkshopProtocol.userFiles(wrongPage.toByteArray(), 550, 1)
        }
        assertThrows(Exception::class.java) {
            SteamWorkshopProtocol.userFiles(byteArrayOf(0x08), 550, 1)
        }
    }

    @Test fun malformedSuccessfulRowsAreNotReportedAsAnEmptySubscriptionLibrary() {
        val response = SteamProtoWriter().apply {
            writeVarint(1, 2)
            writeVarint(2, 1)
            writeMessage(3, detail("123", ""))
            writeBytes(3, byteArrayOf(0x80.toByte()))
        }
        val failure = assertThrows(WorkshopException::class.java) {
            SteamWorkshopProtocol.userFiles(response.toByteArray(), 550, 1)
        }
        assertEquals(WorkshopFailure.INVALID_RESPONSE, failure.reason)
    }

    @Test fun missingItemStatusDoesNotMasqueradeAsADeletedSubscription() {
        val response = SteamProtoWriter().apply {
            writeVarint(1, 1)
            writeVarint(2, 1)
            writeMessage(3, SteamProtoWriter().apply {
                writeUint64(2, "123"); writeVarint(5, 550); writeString(16, "Mod")
            })
        }
        val failure = assertThrows(WorkshopException::class.java) {
            SteamWorkshopProtocol.userFiles(response.toByteArray(), 550, 1)
        }
        assertEquals(WorkshopFailure.INVALID_RESPONSE, failure.reason)
    }

    private fun detail(id: String, title: String, appId: Int = 550) = SteamProtoWriter().apply {
        writeVarint(1, 1); writeUint64(2, id); writeVarint(5, appId.toLong()); writeString(16, title)
        writeBool(35, true)
    }
}
