package takagi.ru.monica.steam.workshop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class WorkshopShareCodeTest {
    @Test fun portableCodeAndLinkPreserveGameAndUnsignedIds() {
        val share = WorkshopShare(570, listOf("3791709672", "18446744073709551615"))
        val code = WorkshopShareCode.encode(share)
        assertEquals(share, WorkshopShareCode.decode(code))
        assertEquals(share, WorkshopShareCode.decode("Dota 2 · Monica Workshop\n${WorkshopShareCode.link(code)}"))
        assertTrue(code.startsWith("MONICA-WS1:"))
        assertFalse(code.contains("steamId"))
    }

    @Test fun duplicateIdsAreCanonicalizedWithoutChangingOrder() {
        val code = WorkshopShareCode.encode(WorkshopShare(550, listOf("2", "0001", "2", "1")))
        assertEquals(listOf("2", "1"), WorkshopShareCode.decode(code).itemIds)
    }

    @Test fun multipleDifferentSharesAndUnknownVersionsAreRejected() {
        val one = WorkshopShareCode.encode(WorkshopShare(570, listOf("1")))
        val two = WorkshopShareCode.encode(WorkshopShare(550, listOf("2")))
        for (text in listOf("$one $two", one.replace("WS1:", "WS2:"), "https://example.com/570/1", one + "?app=550")) {
            assertThrows(WorkshopShareCodeException::class.java) { WorkshopShareCode.decode(text) }
        }
    }

    @Test fun corruptAndTruncatedCompressedPayloadsAreRejected() {
        val code = WorkshopShareCode.encode(WorkshopShare(570, listOf("1", "2")))
        val data = Base64.getUrlDecoder().decode(code.removePrefix(WorkshopShareCode.CODE_PREFIX))
        data[data.lastIndex - 4] = (data[data.lastIndex - 4].toInt() xor 0x40).toByte()
        for (text in listOf(code.dropLast(5), WorkshopShareCode.CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(data))) {
            assertThrows(WorkshopShareCodeException::class.java) { WorkshopShareCode.decode(text) }
        }
    }

    @Test fun invalidIdsGamesAndEmptyListsCannotBeExported() {
        val invalid = listOf(WorkshopShare(0, listOf("1")), WorkshopShare(-1, listOf("1")),
            WorkshopShare(570, emptyList()), WorkshopShare(570, listOf("0")), WorkshopShare(570, listOf("-1")),
            WorkshopShare(570, listOf("18446744073709551616")), WorkshopShare(570, listOf("1/2")))
        invalid.forEach { assertThrows(WorkshopShareCodeException::class.java) { WorkshopShareCode.encode(it) } }
    }

    @Test fun maximumLibraryRoundTripsAndLimitsAreEnforcedBeforeAllocation() {
        val share = WorkshopShare(570, (1..WorkshopShareCode.MAX_ITEMS).map { it.toString() })
        val code = WorkshopShareCode.encode(share)
        assertTrue(code.length < WorkshopShareCode.MAX_TEXT_LENGTH)
        assertEquals(share, WorkshopShareCode.decode(code))
        assertEquals(WorkshopShareProblem.TOO_LARGE, assertThrows(WorkshopShareCodeException::class.java) {
            WorkshopShareCode.encode(share.copy(itemIds = share.itemIds + "9000"))
        }.problem)
        assertThrows(WorkshopShareCodeException::class.java) { WorkshopShareCode.decode("x".repeat(WorkshopShareCode.MAX_TEXT_LENGTH + 1)) }
    }

    @Test fun compressedBombAndInvalidDeclaredCountsAreRejected() {
        fun encode(body: DataOutputStream.() -> Unit): String {
            val out = ByteArrayOutputStream()
            DataOutputStream(GZIPOutputStream(out)).use(body)
            return WorkshopShareCode.CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        }
        for (code in listOf(
            encode { write(ByteArray(100_000)) },
            encode { writeInt(0x4d575331); writeInt(570); writeInt(Int.MAX_VALUE) },
            encode { writeInt(0x4d575331); writeInt(570); writeInt(1); writeLong(0) },
            encode { writeInt(0x4d575331); writeInt(570); writeInt(1); writeLong(1); writeByte(1) }
        )) assertThrows(WorkshopShareCodeException::class.java) { WorkshopShareCode.decode(code) }
    }
}
