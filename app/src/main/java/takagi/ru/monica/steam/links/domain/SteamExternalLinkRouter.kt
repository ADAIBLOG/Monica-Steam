package takagi.ru.monica.steam.links.domain

import java.net.URI
import java.util.Locale
import takagi.ru.monica.steam.workshop.WorkshopShareCode

internal sealed interface SteamExternalLinkTarget {
    data class StoreApp(val appId: Int) : SteamExternalLinkTarget
    data class CommunityProfile(val steamId: String) : SteamExternalLinkTarget
    data class Web(val url: String) : SteamExternalLinkTarget
    data class WorkshopSubscriptions(val appId: Int, val code: String) : SteamExternalLinkTarget
}

internal object SteamExternalLinkRouter {
    private val trustedHosts = setOf(
        "s.team",
        "steamcommunity.com",
        "store.steampowered.com"
    )

    fun route(rawUrl: String?): SteamExternalLinkTarget? {
        if (rawUrl.orEmpty().length > WorkshopShareCode.MAX_TEXT_LENGTH) return null
        val normalizedInput = unwrapSteamOpenUrl(rawUrl) ?: rawUrl.orEmpty().trim()
        if (normalizedInput.startsWith(WorkshopShareCode.LINK_PREFIX)) {
            if (!normalizedInput.removePrefix(WorkshopShareCode.LINK_PREFIX).matches(Regex("[A-Za-z0-9_-]+"))) return null
            val share = runCatching { WorkshopShareCode.decode(normalizedInput) }.getOrNull() ?: return null
            return SteamExternalLinkTarget.WorkshopSubscriptions(share.appId, WorkshopShareCode.encode(share))
        }
        val uri = runCatching { URI(normalizedInput) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host !in trustedHosts) return null

        val normalizedUrl = URI(
            "https",
            null,
            host,
            -1,
            uri.rawPath.ifBlank { "/" },
            uri.rawQuery,
            uri.rawFragment
        ).toASCIIString()
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)

        if (host == "store.steampowered.com" &&
            segments.firstOrNull()?.equals("app", ignoreCase = true) == true
        ) {
            segments.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { appId ->
                return SteamExternalLinkTarget.StoreApp(appId)
            }
        }
        if (host == "steamcommunity.com" &&
            segments.firstOrNull()?.equals("profiles", ignoreCase = true) == true
        ) {
            segments.getOrNull(1)
                ?.takeIf { it.length in 15..20 && it.all(Char::isDigit) }
                ?.let { steamId -> return SteamExternalLinkTarget.CommunityProfile(steamId) }
        }
        return SteamExternalLinkTarget.Web(normalizedUrl)
    }

    private fun unwrapSteamOpenUrl(rawUrl: String?): String? {
        val wrapper = runCatching { URI(rawUrl.orEmpty().trim()) }.getOrNull() ?: return null
        if (!wrapper.scheme.equals("steam", ignoreCase = true) ||
            !wrapper.host.equals("openurl", ignoreCase = true)
        ) {
            return null
        }
        val embeddedPath = wrapper.path.orEmpty().removePrefix("/")
        if (embeddedPath.isBlank()) return ""
        return buildString {
            append(embeddedPath)
            wrapper.rawQuery?.let { append('?').append(it) }
            wrapper.rawFragment?.let { append('#').append(it) }
        }
    }
}
