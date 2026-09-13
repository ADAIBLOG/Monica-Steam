package takagi.ru.monica.steam.links.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.steam.workshop.WorkshopShare
import takagi.ru.monica.steam.workshop.WorkshopShareCode

class SteamExternalLinkRouterTest {
    @Test fun monicaWorkshopLinkRoutesOnlyToAReviewableGameAndItemList() {
        val code = WorkshopShareCode.encode(WorkshopShare(570, listOf("1", "2")))
        val link = WorkshopShareCode.link(code)
        assertEquals(SteamExternalLinkTarget.WorkshopSubscriptions(570, code), SteamExternalLinkRouter.route(link))
        for (invalid in listOf(link + "?subscribe=true", link.replace("workshop/", "workshop.evil/"),
            link.replace("/v1/", "/v2/"), "monica://workshop/v1/broken")) {
            assertNull(SteamExternalLinkRouter.route(invalid))
        }
    }

    @Test
    fun storeAppLinkOpensNativeDetail() {
        assertEquals(
            SteamExternalLinkTarget.StoreApp(1091500),
            SteamExternalLinkRouter.route(
                "https://store.steampowered.com/app/1091500/Cyberpunk_2077/?snr=1"
            )
        )
    }

    @Test
    fun numericCommunityProfileOpensNativeProfile() {
        assertEquals(
            SteamExternalLinkTarget.CommunityProfile("76561198000000000"),
            SteamExternalLinkRouter.route(
                "https://steamcommunity.com/profiles/76561198000000000/"
            )
        )
    }

    @Test
    fun shortAndOtherSteamLinksOpenTrustedWebView() {
        assertEquals(
            SteamExternalLinkTarget.Web("https://s.team/p/example"),
            SteamExternalLinkRouter.route("http://s.team/p/example")
        )
        assertEquals(
            SteamExternalLinkTarget.Web("https://steamcommunity.com/id/joyin/"),
            SteamExternalLinkRouter.route("https://steamcommunity.com/id/joyin/")
        )
    }

    @Test
    fun steamOpenUrlWrapperRoutesEmbeddedStoreAndCommunityLinks() {
        assertEquals(
            SteamExternalLinkTarget.StoreApp(730),
            SteamExternalLinkRouter.route(
                "steam://openurl/https://store.steampowered.com/app/730/?snr=search"
            )
        )
        assertEquals(
            SteamExternalLinkTarget.CommunityProfile("76561198000000000"),
            SteamExternalLinkRouter.route(
                "steam://openurl/https%3A%2F%2Fsteamcommunity.com%2Fprofiles%2F" +
                    "76561198000000000%2F"
            )
        )
    }

    @Test
    fun lookalikeAndNonWebLinksAreRejected() {
        assertNull(SteamExternalLinkRouter.route("https://store.steampowered.com.evil.example/app/730"))
        assertNull(SteamExternalLinkRouter.route("javascript:alert(1)"))
        assertNull(SteamExternalLinkRouter.route("steam://rungameid/730"))
        assertNull(SteamExternalLinkRouter.route("steam://openurl/javascript%3Aalert%281%29"))
    }

    @Test
    fun manifestRegistersWebHostsAndConstrainedSteamOpenUrlProtocol() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.intent.action.VIEW"))
        assertTrue(manifest.contains("android:host=\"s.team\""))
        assertTrue(manifest.contains("android:host=\"steamcommunity.com\""))
        assertTrue(manifest.contains("android:host=\"store.steampowered.com\""))
        assertTrue(manifest.contains("android:scheme=\"steam\""))
        assertTrue(manifest.contains("android:host=\"openurl\""))
    }

    private fun projectFile(relativePath: String): File {
        val root = generateSequence(File(System.getProperty("user.dir").orEmpty())) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle").isFile }
            ?: error("Project root not found")
        return File(root, relativePath)
    }
}
