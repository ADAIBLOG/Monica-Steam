package takagi.ru.monica.steam.core

/**
 * Optional JNI bridge for the Rust Steam core.
 *
 * Local Android builds remain functional when the native library has not been
 * produced yet; callers transparently fall back to the Kotlin implementation.
 * The native build helper packages the library for the supported ABIs.
 * Secret import, TOTP/signing and authenticator/login mutations stay in Kotlin;
 * they deliberately have no JNI entry points in this facade.
 */
internal object RustSteamCoreNative {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("monica_steam_android")
        true
    }.getOrDefault(false)

    val isAvailable: Boolean
        get() = loaded

    fun encodeCmMessageOrNull(
        eMsg: Int,
        steamId: Long,
        sessionId: Int,
        body: ByteArray,
        jobIdSource: Long,
        jobIdTarget: Long,
        targetJobName: String?
    ): ByteArray? {
        if (!loaded) return null
        return runCatching {
            nativeEncodeCmMessage(
                eMsg = eMsg,
                steamId = steamId,
                sessionId = sessionId,
                body = body,
                jobIdSource = jobIdSource,
                jobIdTarget = jobIdTarget,
                targetJobName = targetJobName.orEmpty()
            )
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun decodeCmMessagesOrNull(payload: ByteArray): ByteArray? {
        if (!loaded || payload.isEmpty()) return null
        return runCatching { nativeDecodeCmMessages(payload) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseChatSessionsOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParseChatSessions(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseChatMessagesOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParseChatMessages(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseOwnedGamesOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParseOwnedGames(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseAchievementProgressOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParseAchievementProgress(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseStoreItemsOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParseStoreItems(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseAchievementDetailsOrNull(
        definitionsResponse: ByteArray,
        userResponse: ByteArray
    ): ByteArray? {
        if (!loaded) return null
        return runCatching {
            nativeParseAchievementDetails(definitionsResponse, userResponse)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun parseFamilySharedAppsOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParseFamilySharedApps(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseFriendNicknamesOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParseFriendNicknames(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parsePendingLoginClientIdsOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParsePendingLoginClientIds(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseAuthSessionInfoOrNull(response: ByteArray): ByteArray? {
        if (!loaded) return null
        return runCatching { nativeParseAuthSessionInfo(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseAuthConfirmationOrNull(response: ByteArray): ByteArray? {
        if (!loaded) return null
        return runCatching { nativeParseAuthConfirmation(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseAuthorizedDevicesOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParseAuthorizedDevices(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseTradeOffersOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParseTradeOffers(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseWishlistOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParseWishlist(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseGroupChatHistoryOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParseGroupChatHistory(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun parseGroupChatSummariesOrNull(response: ByteArray): ByteArray? {
        if (!loaded || response.isEmpty()) return null
        return runCatching { nativeParseGroupChatSummaries(response) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun webLogonBodyOrNull(webLogonToken: String): ByteArray? {
        if (!loaded) return null
        return runCatching { nativeWebLogonBody(webLogonToken) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    private external fun nativeEncodeCmMessage(eMsg: Int, steamId: Long, sessionId: Int, body: ByteArray, jobIdSource: Long, jobIdTarget: Long, targetJobName: String): ByteArray

    @JvmStatic private external fun nativeDecodeCmMessages(payload: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseChatSessions(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseChatMessages(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseOwnedGames(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseAchievementProgress(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseStoreItems(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseAchievementDetails(definitionsResponse: ByteArray, userResponse: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseFamilySharedApps(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseFriendNicknames(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParsePendingLoginClientIds(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseAuthSessionInfo(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseAuthConfirmation(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseAuthorizedDevices(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseTradeOffers(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseWishlist(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseGroupChatHistory(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeParseGroupChatSummaries(response: ByteArray): ByteArray
    @JvmStatic private external fun nativeWebLogonBody(webLogonToken: String): ByteArray
}
