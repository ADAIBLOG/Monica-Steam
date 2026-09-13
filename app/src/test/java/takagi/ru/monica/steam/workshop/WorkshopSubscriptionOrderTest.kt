package takagi.ru.monica.steam.workshop

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import takagi.ru.monica.steam.core.RustSteamCoreNative

class WorkshopSubscriptionOrderTest {
    private fun nativeAvailable() {
        if (System.getProperty("steam.native.required") == "true") assertTrue(RustSteamCoreNative.isAvailable)
        assumeTrue(RustSteamCoreNative.isAvailable)
    }

    @Test fun nativeOrderMatchesKotlinForEverySortAndExtremeValues() {
        nativeAvailable()
        val random = java.util.Random(73)
        val items = List(4096) { index -> WorkshopItem(
            id = if (index % 101 == 0) "18446744073709551615" else (index + 1).toString(),
            appId = 294100, title = "中文 Mod $index", subscriptions = random.nextLong(),
            created = (index % 5).toLong(), updated = if (index % 2 == 0) Long.MIN_VALUE else Long.MAX_VALUE
        ) }
        for (sort in WorkshopSort.entries) {
            val indices = requireNotNull(WorkshopSubscriptionOrder.nativeOrder(items, sort))
            assertEquals(sortSubscriptions(items, sort), indices.map(items::get))
            assertEquals(sortSubscriptions(items, sort), WorkshopSubscriptionOrder.sorted(items, sort))
        }
    }

    @Test fun nonCanonicalIdsKeepExactKotlinOrdering() {
        val items = List(1100) { WorkshopItem(if (it % 2 == 0) "000$it" else "+$it", 294100, "Mod", updated = 7) }
        assertNull(WorkshopSubscriptionOrder.nativeOrder(items, WorkshopSort.UPDATED))
        assertEquals(sortSubscriptions(items, WorkshopSort.UPDATED), WorkshopSubscriptionOrder.sorted(items, WorkshopSort.UPDATED))
    }

    @Test fun emptyAndSmallListsPreserveFallbackBehavior() {
        assertTrue(WorkshopSubscriptionOrder.sorted(emptyList(), WorkshopSort.UPDATED).isEmpty())
        val items = listOf("2", "10", "1").map { WorkshopItem(it, 294100, "Mod", updated = 7) }
        assertEquals(listOf("1", "10", "2"), WorkshopSubscriptionOrder.sorted(items, WorkshopSort.UPDATED).map { it.id })
    }

    @Test fun incrementalMergeMatchesPreviousWholeListSortIncludingDuplicateAndChangedRows() {
        val random = java.util.Random(81)
        for (sort in WorkshopSort.entries) {
            var old = emptyList<WorkshopItem>()
            repeat(20) { page ->
                val incoming = List(30) { index -> WorkshopItem(((page * 25) + index).toString(), 294100,
                    "Mod", updated = random.nextInt(10).toLong(), created = random.nextLong(), subscriptions = random.nextLong()) }
                // A fetched detail can change an existing row's ordering key.
                if (page == 10) old = old.mapIndexed { index, item -> if (index == 0) item.copy(updated = Long.MIN_VALUE) else item }
                val expected = sortSubscriptions((old + incoming).distinctBy { it.id }, sort)
                old = mergeWorkshopPage(old, incoming, WorkshopQuery(sort = sort, subscribedOnly = true))
                assertEquals(expected, old)
            }
        }
    }

    @Test fun publicPageMergeRetainsServerOrderAndExistingDetails() {
        val old = listOf(WorkshopItem("3", 294100, "Existing detail"))
        val incoming = listOf(WorkshopItem("3", 294100, "Old summary"), WorkshopItem("1", 294100, "Next"))
        assertEquals(old + incoming.last(), mergeWorkshopPage(old, incoming, WorkshopQuery()))
    }
}
