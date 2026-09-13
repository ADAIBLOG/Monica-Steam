package takagi.ru.monica.steam.workshop

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.LocaleManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.MonicaSteamActivity
import takagi.ru.monica.R
import takagi.ru.monica.steam.data.SteamAccount
import takagi.ru.monica.steam.data.SteamStorageSource
import takagi.ru.monica.steam.foundation.ui.setSteamUiScaledContent
import takagi.ru.monica.steam.navigation.ui.LocalSteamDockContentClearance
import takagi.ru.monica.steam.navigation.ui.SteamDockContentClearance
import takagi.ru.monica.steam.navigation.ui.SteamToolbarItem
import takagi.ru.monica.ui.theme.MonicaTheme

/** Exercises the minified production screens with private offline fixtures and local disk storage. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, InternalComposeApi::class)
class SteamWorkshopPresetUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var fixtureContext: Context? = null
    private val context get() = fixtureContext ?: instrumentation.targetContext
    private val language = InstrumentationRegistry.getArguments().getString("workshopLocale") ?: "en"
    private val darkTheme = InstrumentationRegistry.getArguments().getString("workshopTheme") != "light"
    private val animeName get() = if (language.startsWith("zh")) "二次元 MOD" else "Anime MODs"
    private val realisticName get() = if (language.startsWith("zh")) "写实 MOD" else "Realistic MODs"
    private val revisedName get() = if (language.startsWith("zh")) "写实 MOD · 修订" else "Realistic revised"
    private val dockLabel get() = if (language.startsWith("zh")) "商店" else "Store"
    private var activity: MonicaSteamActivity? = null
    private lateinit var repository: WorkshopPresetRepository
    private lateinit var model: SteamWorkshopViewModel
    private val writes = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private var pauseAddition: CompletableDeferred<Unit>? = null

    @After fun finishFixture() {
        pauseAddition?.complete(Unit)
        instrumentation.runOnMainSync { activity?.finish() }
    }

    @Test fun createFromSubscriptionsAndCodeThenRenameShareAndDelete() {
        showFixture()
        click(text(R.string.workshop_presets))
        click(text(R.string.workshop_preset_create))
        screenshot("preset-create-menu")
        click(text(R.string.workshop_preset_from_subscriptions))
        awaitText(text(R.string.workshop_share_count, 2))
        enterText(animeName)
        click(text(R.string.workshop_preset_save))
        awaitText(text(R.string.workshop_preset_switch_to, animeName))

        click(text(R.string.workshop_preset_create))
        click(text(R.string.workshop_preset_from_code))
        enterText(WorkshopShareCode.encode(WorkshopShare(550, listOf("2", "3"))))
        click(text(R.string.workshop_open_share))
        click(text(R.string.workshop_import_actions))
        click(text(R.string.workshop_preset_save_share))
        enterText(realisticName)
        click(text(R.string.workshop_preset_save))
        awaitText(text(R.string.workshop_preset_switch_to, realisticName))
        screenshot("preset-library")
        assertEquals(listOf("2", "3"), runBlocking { repository.load(550) }.single { it.name == realisticName }.share.itemIds)

        click(text(R.string.workshop_preset_manage_named, realisticName))
        screenshot("preset-card-menu")
        click(text(R.string.workshop_preset_rename))
        enterText(revisedName)
        click(text(R.string.workshop_preset_save))
        awaitText(text(R.string.workshop_preset_switch_to, revisedName))
        click(text(R.string.workshop_preset_manage_named, revisedName))
        click(text(R.string.workshop_share))
        awaitText(text(R.string.workshop_share_count, 2))
        assertEquals(WorkshopShare(550, listOf("2", "3")),
            WorkshopShareCode.decode(requireNotNull(model.state.value.export?.code)))
        screenshot("share-dialog")
        click(text(R.string.workshop_share_actions))
        awaitText(text(R.string.workshop_preset_save_share))
        screenshot("share-menu")
        closePopup()
        click(text(R.string.workshop_copy_code))
        assertEquals(model.state.value.export?.code,
            context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
        click(text(R.string.workshop_done))
        click(text(R.string.workshop_preset_manage_named, revisedName))
        click(text(R.string.workshop_preset_delete))
        awaitText(text(R.string.workshop_preset_delete_prompt, revisedName))
        click(text(R.string.workshop_preset_delete))
        awaitCondition { runBlocking { repository.load(550) }.size == 1 }
        assertEquals(animeName, runBlocking { repository.load(550) }.single().name)
        assertTrue(writes.isEmpty())
    }

    @Test fun switchingShowsBothCountsAndFinishesAfterLeavingThePageAndApp() {
        pauseAddition = CompletableDeferred()
        showFixture(seedPreset = true)
        click(text(R.string.workshop_presets))
        click(text(R.string.workshop_preset_switch_to, realisticName))
        awaitText(text(R.string.workshop_preset_diff, 1, 1, 1))
        assertTrue(writes.isEmpty())
        screenshot("preset-switch-preview")
        click(text(R.string.workshop_preset_confirm))
        awaitText(text(R.string.workshop_background_continue))
        awaitCondition { writes.isNotEmpty() }
        assertEquals(listOf("3" to true), writes.toList())
        click(text(R.string.workshop_background_continue))
        awaitText(text(R.string.workshop_background_view))
        screenshot("background-banner")
        click(text(R.string.workshop_background_view))
        click(text(R.string.workshop_background_continue))
        click(text(R.string.workshop_back))
        assertTrue(model.state.value.bulk?.running == true)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
        instrumentation.waitForIdleSync()
        pauseAddition?.complete(Unit)
        val completed = runBlocking { withTimeout(10_000) { model.state.first { it.bulk?.running == false } } }
        assertTrue(completed.bulk?.presetVerified == true)
        assertEquals(listOf("3" to true, "1" to false), writes.toList())
        assertFalse(requireNotNull(completed.bulk).subscribes("1"))
    }

    @Test fun contextualMenusKeepImportSelectionAndOptionsAboveTheDock() {
        showFixture(seedPreset = true)
        val tabs = listOf(R.string.workshop_browse, R.string.workshop_subscriptions_tab, R.string.workshop_presets)
            .map { bounds(awaitText(text(it))) }
        assertTrue("Navigation labels must not overlap", tabs.zipWithNext().all { (left, right) -> left.right <= right.left })
        assertTrue(nodes(text(R.string.workshop_select)).isEmpty())
        screenshot("workshop-browse")
        click(text(R.string.workshop_filters))
        awaitText(text(R.string.workshop_sort))
        screenshot("workshop-filters")
        click(text(R.string.workshop_newest))
        click(text(R.string.workshop_apply))
        awaitText(text(R.string.workshop_filters_active))
        click(text(R.string.workshop_filters_active))
        click(text(R.string.workshop_reset))
        click(text(R.string.workshop_apply))
        awaitText(text(R.string.workshop_filters))
        click(text(R.string.workshop_manage))
        awaitText(text(R.string.workshop_select))
        assertTrue(nodes(text(R.string.workshop_share_all)).isEmpty())
        screenshot("browse-menu")
        closePopup()

        click(text(R.string.workshop_subscriptions_tab))
        awaitText(text(R.string.workshop_account, "Test account"))
        screenshot("workshop-subscriptions")
        click(text(R.string.workshop_manage))
        awaitText(text(R.string.workshop_preset_save_subscriptions))
        screenshot("subscriptions-menu")
        click(text(R.string.workshop_share_all))
        awaitText(text(R.string.workshop_share_count, 2))
        click(text(R.string.workshop_done))
        click(text(R.string.workshop_manage))
        click(text(R.string.workshop_import_share))
        enterText(WorkshopShareCode.encode(WorkshopShare(550, listOf("1", "3", "4"))))
        click(text(R.string.workshop_open_share))
        awaitText(text(R.string.workshop_selected_count, 2))
        assertTrue(nodes(text(R.string.workshop_preset_save_share)).isEmpty())
        val actionBounds = bounds(awaitText(text(R.string.workshop_import_actions)))
        val dockBounds = bounds(awaitText(dockLabel))
        assertTrue("Import actions belong at the bottom, above the Dock", actionBounds.bottom < dockBounds.top &&
            actionBounds.centerY() > dockBounds.centerY() / 2)
        screenshot("import-preview")
        click(text(R.string.workshop_import_actions))
        assertFalse(actionEnabled(text(R.string.workshop_select_all)))
        screenshot("import-actions-menu")
        click(text(R.string.workshop_clear_selection))
        awaitText(text(R.string.workshop_selected_count, 0))
        assertFalse(actionEnabled(text(R.string.workshop_subscribe_selected_count, 0)))
        click("Workshop MOD 3")
        awaitText(text(R.string.workshop_selected_count, 1))
        awaitCondition { actionEnabled(text(R.string.workshop_subscribe_selected_count, 1)) }
        click(text(R.string.workshop_import_actions))
        click(text(R.string.workshop_with_dependencies))
        awaitText(text(R.string.workshop_dependencies_included))
        screenshot("import-partial-selection")
        click(text(R.string.workshop_import_actions))
        click(text(R.string.workshop_select_all))
        awaitText(text(R.string.workshop_selected_count, 2))
        awaitCondition { actionEnabled(text(R.string.workshop_subscribe_all_count, 2)) }

        click(text(R.string.workshop_view_details))
        awaitText(text(R.string.workshop_detail_title))
        screenshot("workshop-detail")
        assertTrue(nodes(text(R.string.workshop_open_steam)).isEmpty())
        click(text(R.string.workshop_manage))
        awaitText(text(R.string.workshop_open_steam))
        assertTrue(nodes(text(R.string.workshop_import_share)).isEmpty())
        assertTrue(nodes(text(R.string.workshop_select)).isEmpty())
        screenshot("detail-menu")
        closePopup()
        click(text(R.string.workshop_back))
        awaitText(text(R.string.workshop_selected_count, 2))
        awaitText(text(R.string.workshop_dependencies_included))
        assertTrue(writes.isEmpty())
    }

    private fun showFixture(seedPreset: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Popups create their own Android roots; apply the locale to the actual Activity.
            instrumentation.targetContext.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language)
        }
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        repository = FileWorkshopPresetRepository(File(context.cacheDir, "workshop-preset-fixture-${UUID.randomUUID()}"))
        // Supply optional interface arguments explicitly: R8 removes unused DefaultImpls helpers.
        if (seedPreset) runBlocking { repository.save(550, realisticName, WorkshopShare(550, listOf("2", "3")), null) }
        context.startActivity(Intent(context, MonicaSteamActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        awaitCondition {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MonicaSteamActivity>().firstOrNull {
                        !it.isFinishing && (Build.VERSION.SDK_INT < 33 ||
                            it.resources.configuration.locales[0].language == Locale.forLanguageTag(language).language)
                    }
            }
            activity != null
        }
        val host = requireNotNull(activity)
        fixtureContext = host
        val account = SteamAccount(904003, "76561198000904003", "preset-fixture", "Test account", "", "",
            null, null, null, "not-a-real-token", "not-a-real-refresh", null, "{}", false, 0, 0, 0)
        val subscribed = linkedSetOf("1", "2")
        val gateway = object : SteamWorkshopGateway {
            fun item(id: String) = WorkshopItem(id, 550, "Workshop MOD $id", canSubscribe = true,
                subscribed = synchronized(subscribed) { id in subscribed })
            override suspend fun supportsWorkshop(appId: Int) = true
            override suspend fun browse(account: SteamAccount?, appId: Int, query: WorkshopQuery, page: Int): WorkshopBatch {
                val ids = if (query.subscribedOnly) synchronized(subscribed) { subscribed.toList() } else listOf("1", "2", "3")
                return WorkshopBatch(ids.map(::item), page, false, ids.size)
            }
            override suspend fun details(account: SteamAccount, appId: Int, id: String) = item(id)
            override suspend fun inspectItems(account: SteamAccount, appId: Int, ids: List<String>) = ids.map(::item)
            override suspend fun setSubscription(account: SteamAccount, item: WorkshopItem, subscribe: Boolean, dependencies: Boolean) {
                assertEquals(904003L, account.id)
                assertEquals(550, item.appId)
                assertFalse(dependencies)
                synchronized(subscribed) { if (subscribe) subscribed += item.id else subscribed -= item.id }
                writes += item.id to subscribe
                if (subscribe && item.id == "3") pauseAddition?.await()
            }
        }
        val dockModifier = Modifier.fillMaxSize().wrapContentSize(Alignment.BottomCenter)
        val dockItems = listOf(SteamToolbarItem(Icons.Default.Storefront, dockLabel, {}))
        instrumentation.runOnMainSync {
            model = SteamWorkshopViewModel(550, account, gateway, presetRepository = repository)
            host.viewModelStore.put("workshop_guest_${SteamStorageSource.Local}_${account.steamId}_550", model)
            host.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            host.setSteamUiScaledContent {
                MonicaTheme(darkTheme = darkTheme) {
                    CompositionLocalProvider(LocalSteamDockContentClearance provides SteamDockContentClearance) {
                        WorkshopSelectionRenderer.render(account, SteamStorageSource.Local, dockModifier, dockItems, currentComposer)
                    }
                }
            }
        }
        awaitText("Workshop MOD 1")
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun actionEnabled(label: String): Boolean {
        var node = awaitText(label)
        while (!node.isClickable) node = node.parent ?: throw AssertionError("No action for $label")
        return node.isEnabled
    }
    private fun closePopup() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
    }
    private fun allNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.refresh()) return
            result += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun nodes(text: String) = allNodes().filter { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun awaitText(text: String): AccessibilityNodeInfo {
        awaitCondition { nodes(text).isNotEmpty() }
        return nodes(text).first()
    }
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(50) }
        throw AssertionError("UI condition timed out:\n" + allNodes().joinToString("\n") {
            "${it.className}: ${it.text} / ${it.contentDescription}, enabled=${it.isEnabled}, bounds=${bounds(it)}"
        })
    }
    private fun click(text: String) {
        awaitText(text)
        var target: AccessibilityNodeInfo? = null
        awaitCondition {
            target = nodes(text).firstNotNullOfOrNull { node ->
                var parent: AccessibilityNodeInfo? = node
                while (parent != null && !parent.isClickable) parent = parent.parent
                parent?.takeIf { it.isEnabled }
            }
            target != null
        }
        val bounds = Rect().also(requireNotNull(target)::getBoundsInScreen)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }
    private fun enterText(value: String) {
        awaitCondition { allNodes().any { it.isVisibleToUser && it.isEditable } }
        val editor = allNodes().first { it.isVisibleToUser && it.isEditable }
        assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        // Accessibility can settle before a popup's fade/scale animation completes.
        SystemClock.sleep(350)
        instrumentation.waitForIdleSync()
        val directory = requireNotNull(context.getExternalFilesDir("workshop-ui-verification"))
        val suffix = InstrumentationRegistry.getArguments().getString("workshopScreenshotSuffix") ?: "$language-${if (darkTheme) "dark" else "light"}"
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
