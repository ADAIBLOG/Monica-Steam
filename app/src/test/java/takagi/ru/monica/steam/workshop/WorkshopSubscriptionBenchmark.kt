package takagi.ru.monica.steam.workshop

import takagi.ru.monica.steam.core.RustSteamCoreNative

/** Manual host benchmark; includes input packing, JNI, output validation and list mapping. */
object WorkshopSubscriptionBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        check(RustSteamCoreNative.isAvailable) { "Build the release host JNI library and set steamNativeTestLibraryDir" }
        for (size in listOf(30, 256, 1024, 10_000)) {
            val random = java.util.Random(73)
            val items = List(size) { index ->
                WorkshopItem((10_000_000L + index).toString(), 294100, "Mod $index",
                    updated = random.nextLong(), subscriptions = random.nextLong())
            }
            check(WorkshopSubscriptionOrder.nativeOrder(items, WorkshopSort.UPDATED) != null)
            check(WorkshopSubscriptionOrder.sorted(items, WorkshopSort.UPDATED, false) ==
                WorkshopSubscriptionOrder.sorted(items, WorkshopSort.UPDATED, true))
            repeat(100) { WorkshopSubscriptionOrder.sorted(items, WorkshopSort.UPDATED, it % 2 == 0) }
            val times = Array(2) { LongArray(250) }
            repeat(250) { round ->
                repeat(2) { variant ->
                    val mode = (round + variant) % 2
                    val start = System.nanoTime()
                    val result = WorkshopSubscriptionOrder.sorted(items, WorkshopSort.UPDATED, mode == 1)
                    times[mode][round] = System.nanoTime() - start
                    check(result.size == size)
                }
            }
            repeat(2) { mode ->
                times[mode].sort()
                println("items=$size optimized=${mode == 1} median_ms=${times[mode][125] / 1e6} p95_ms=${times[mode][237] / 1e6}")
            }
        }
    }
}
