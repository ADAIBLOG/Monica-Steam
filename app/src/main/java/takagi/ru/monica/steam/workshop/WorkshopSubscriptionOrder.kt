package takagi.ru.monica.steam.workshop

import takagi.ru.monica.steam.core.RustSteamCoreNative

/** Only public numeric metadata crosses JNI; models and account state stay in Kotlin. */
internal object WorkshopSubscriptionOrder {
    fun sorted(items: List<WorkshopItem>, sort: WorkshopSort, useNative: Boolean = true): List<WorkshopItem> {
        val comparator = workshopSubscriptionComparator(sort)
        if ((1 until items.size).all { comparator.compare(items[it - 1], items[it]) <= 0 }) return items
        if (useNative && items.size in 1024..100_000 && RustSteamCoreNative.isAvailable) {
            nativeOrder(items, sort)?.let { return it.map(items::get) }
        }
        return sortSubscriptions(items, sort)
    }

    internal fun nativeOrder(items: List<WorkshopItem>, sort: WorkshopSort): IntArray? {
        val scores = LongArray(items.size)
        val ids = LongArray(items.size)
        for (index in items.indices) {
            val item = items[index]
            val id = item.id.toULongOrNull() ?: return null
            // Non-canonical IDs retain the exact Kotlin string ordering through fallback.
            if (id.toString() != item.id) return null
            ids[index] = id.toLong()
            scores[index] = when (sort) {
                WorkshopSort.MOST_SUBSCRIBED -> item.subscriptions
                WorkshopSort.NEWEST -> item.created
                else -> item.updated
            }
        }
        val order = RustSteamCoreNative.orderWorkshopSubscriptionsOrNull(scores, ids) ?: return null
        if (order.size != items.size) return null
        val seen = BooleanArray(items.size)
        for (index in order) {
            if (index !in items.indices || seen[index]) return null
            seen[index] = true
        }
        return order
    }
}

/** Each fetched page adds at most a small tail; retain the already sorted prefix. */
internal fun mergeWorkshopPage(old: List<WorkshopItem>, incoming: List<WorkshopItem>, query: WorkshopQuery): List<WorkshopItem> {
    val ids = old.mapTo(HashSet(old.size + incoming.size), WorkshopItem::id)
    val fresh = incoming.filter { ids.add(it.id) }
    if (!query.subscribedOnly) return old + fresh
    val comparator = workshopSubscriptionComparator(query.sort)
    val prefix = if ((1 until old.size).all { comparator.compare(old[it - 1], old[it]) <= 0 }) old
        else WorkshopSubscriptionOrder.sorted(old, query.sort)
    val tail = WorkshopSubscriptionOrder.sorted(fresh, query.sort)
    val result = ArrayList<WorkshopItem>(prefix.size + tail.size)
    var a = 0
    var b = 0
    while (a < prefix.size && b < tail.size) {
        if (comparator.compare(prefix[a], tail[b]) <= 0) result += prefix[a++] else result += tail[b++]
    }
    while (a < prefix.size) result += prefix[a++]
    while (b < tail.size) result += tail[b++]
    return result
}
