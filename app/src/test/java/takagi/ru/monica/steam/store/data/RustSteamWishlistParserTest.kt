package takagi.ru.monica.steam.store.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RustSteamWishlistParserTest {
    @Test
    fun decodesWishlistItemWithPackageAndLocalizedPrices() {
        val payload = bridge(
            item(
                appId = 620,
                packageId = 1234,
                discountPercent = 50,
                priority = 3,
                addedAtEpochSeconds = 1_700_000_000L,
                name = "Portal 2",
                imageUrl = "https://shared.akamai.steamstatic.com/store_item_assets/apps/620/capsule.jpg",
                formattedInitialPrice = "¥ 42.00",
                formattedFinalPrice = "¥ 21.00"
            )
        )

        val decoded = requireNotNull(RustSteamWishlistParser.decodeForTest(payload)).single()

        assertEquals(620, decoded.appId)
        assertEquals("Portal 2", decoded.name)
        assertEquals(1234, decoded.packageId)
        assertEquals(50, decoded.discountPercent)
        assertEquals("¥ 42.00", decoded.formattedInitialPrice)
        assertEquals("¥ 21.00", decoded.formattedFinalPrice)
        assertEquals(3, decoded.priority)
        assertEquals(1_700_000_000L, decoded.addedAtEpochSeconds)
    }

    @Test
    fun absentPackageRemainsNull() {
        val payload = bridge(
            item(
                appId = 570,
                packageId = null,
                discountPercent = 0,
                priority = 0,
                addedAtEpochSeconds = 0L,
                name = "Dota 2",
                imageUrl = "",
                formattedInitialPrice = "",
                formattedFinalPrice = ""
            )
        )

        val decoded = requireNotNull(RustSteamWishlistParser.decodeForTest(payload)).single()
        assertNull(decoded.packageId)
    }

    @Test
    fun invalidBridgeReturnsNullForKotlinFallback() {
        val valid = bridge(
            item(620, 1234, 50, 3, 1L, "Portal 2", "image", "42", "21")
        )

        val wrongMagic = valid.copyOf().also { it[0] = 'X'.code.toByte() }
        assertNull(RustSteamWishlistParser.decodeForTest(wrongMagic))
        assertNull(RustSteamWishlistParser.decodeForTest(valid.copyOf(valid.size - 1)))
        assertNull(RustSteamWishlistParser.decodeForTest(valid + byteArrayOf(1)))

        val invalidFlag = valid.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 2)
        }
        assertNull(RustSteamWishlistParser.decodeForTest(invalidFlag))
    }

    private fun bridge(vararg items: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'W'.code.toByte(), '1'.code.toByte()))
        out.writeInt(items.size)
        items.forEach(out::write)
        return out.toByteArray()
    }

    private fun item(
        appId: Int,
        packageId: Int?,
        discountPercent: Int,
        priority: Int,
        addedAtEpochSeconds: Long,
        name: String,
        imageUrl: String,
        formattedInitialPrice: String,
        formattedFinalPrice: String
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeInt(appId)
        out.writeInt(if (packageId != null) 1 else 0)
        out.writeInt(packageId ?: 0)
        out.writeInt(discountPercent)
        out.writeInt(priority)
        out.writeLong(addedAtEpochSeconds)
        out.writeString(name)
        out.writeString(imageUrl)
        out.writeString(formattedInitialPrice)
        out.writeString(formattedFinalPrice)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun ByteArrayOutputStream.writeLong(value: Long) {
        write(ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
    }
}
