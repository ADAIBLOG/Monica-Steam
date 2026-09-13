package takagi.ru.monica.steam.workshop

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
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

/** Real screen and shared selection bar, with offline list data and no stored account. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, InternalComposeApi::class)
class SteamWorkshopSelectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var activity: MonicaSteamActivity? = null

    @After fun finishFixture() {
        screenshot("selection-fixture-final")
        instrumentation.runOnMainSync { activity?.finish() }
    }

    @Test fun subscriptionsSelectionStaysAboveDockAndOnlyOffersRemovalAndSharing() {
        showFixture((1..56).map { item(it.toString(), true) }, subscribedOnly = true)
        click(context.getString(R.string.workshop_manage))
        click(context.getString(R.string.workshop_select))
        assertFalse(clickable(context.getString(R.string.workshop_bulk_unsubscribe)).isEnabled)
        click(context.getString(R.string.workshop_select_loaded))
        awaitText("56")
        assertTrue(nodes(context.getString(R.string.workshop_bulk_subscribe)).isEmpty())
        val remove = clickable(context.getString(R.string.workshop_bulk_unsubscribe))
        assertTrue(remove.isEnabled)
        val removeBounds = bounds(remove)
        val dockBounds = bounds(awaitText(DOCK_LABEL))
        assertTrue("Selection actions must be above the Dock", removeBounds.bottom < dockBounds.top)
        assertTrue("Selection actions must be at the bottom of the screen", removeBounds.centerY() > dockBounds.centerY() / 2)
        screenshot("my-subscriptions-selection")

        click(context.getString(R.string.workshop_bulk_unsubscribe))
        awaitText(context.getString(R.string.workshop_bulk_unsubscribe_prompt, 56))
        click(context.getString(R.string.workshop_cancel))
        click(context.getString(R.string.workshop_share_selected))
        awaitText(context.getString(R.string.workshop_share_count, 56))
        click(context.getString(R.string.workshop_done))
        click(context.getString(R.string.workshop_clear_selection))
        assertTrue(nodes(context.getString(R.string.workshop_select_loaded)).isEmpty())
    }

    @Test fun browseConfirmationCountsOnlyItemsThatMayNeedSubscription() {
        showFixture(listOf(item("1", true), item("2", false),
            item("3", false).copy(fileType = 2), item("4", null)), subscribedOnly = false)
        click(context.getString(R.string.workshop_manage))
        click(context.getString(R.string.workshop_select))
        click(context.getString(R.string.workshop_select_loaded))
        awaitText("4")
        screenshot("browse-selection")
        click(context.getString(R.string.workshop_bulk_subscribe))
        awaitText(context.getString(R.string.workshop_bulk_subscribe_prompt, 2))
        click(context.getString(R.string.workshop_cancel))
    }

    private fun showFixture(rows: List<WorkshopItem>, subscribedOnly: Boolean) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        context.startActivity(Intent(context, MonicaSteamActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (activity == null && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MonicaSteamActivity>().firstOrNull()
            }
            if (activity == null) Thread.sleep(50)
        }
        val host = requireNotNull(activity)
        val account = SteamAccount(904002, "76561198000904002", "workshop-fixture", "Test account", "", "",
            null, null, null, "not-a-real-token", "not-a-real-refresh", null, "{}", false, 0, 0, 0)
        // An unstored account has no repository handle; match the screen's fallback key.
        val modelKey = "workshop_guest_${SteamStorageSource.Local}_${account.steamId}_550"
        val dockModifier = Modifier.fillMaxSize().wrapContentSize(Alignment.BottomCenter)
        val dockItems = listOf(SteamToolbarItem(Icons.Default.Storefront, DOCK_LABEL, {}))
        val gateway = object : SteamWorkshopGateway {
            override suspend fun supportsWorkshop(appId: Int) = true
            override suspend fun browse(account: SteamAccount?, appId: Int, query: WorkshopQuery, page: Int) =
                WorkshopBatch(rows, 1, false, rows.size)
            override suspend fun details(account: SteamAccount, appId: Int, id: String) = rows.first { it.id == id }
            override suspend fun setSubscription(account: SteamAccount, item: WorkshopItem, subscribe: Boolean, dependencies: Boolean) {
                throw AssertionError("This UI fixture must never submit a subscription write")
            }
        }
        instrumentation.runOnMainSync {
            val model = SteamWorkshopViewModel(550, account, gateway)
            if (subscribedOnly) model.query(WorkshopQuery(subscribedOnly = true, sort = WorkshopSort.UPDATED))
            host.viewModelStore.put(modelKey, model)
            // The fixture contains no secrets. Enable screenshots only for this isolated test window.
            host.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            host.setSteamUiScaledContent {
                MonicaTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalSteamDockContentClearance provides SteamDockContentClearance) {
                        WorkshopSelectionRenderer.render(account, SteamStorageSource.Local, dockModifier, dockItems, currentComposer)
                    }
                }
            }
        }
        awaitText(rows.first().title)
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            // Refresh cached virtual nodes after Compose changes selection or enabled state.
            if (!node.refresh()) return
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun nodes(text: String): List<AccessibilityNodeInfo> = allNodes().filter {
        it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text)
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            nodes(text).firstOrNull()?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("Missing UI text: $text\n" + allNodes().joinToString("\n") {
            "${it.className} visible=${it.isVisibleToUser} text=${it.text} description=${it.contentDescription} bounds=${bounds(it)}"
        })
    }

    private fun clickable(text: String): AccessibilityNodeInfo {
        var node = awaitText(text)
        while (!node.isClickable) node = node.parent ?: throw AssertionError("No clickable parent for $text")
        return node
    }

    private fun click(text: String) {
        val target = clickable(text)
        assertTrue("Disabled action: $text", target.isEnabled)
        val position = bounds(target)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                position.exactCenterX(), position.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun screenshot(name: String) {
        SystemClock.sleep(350)
        instrumentation.waitForIdleSync()
        val directory = requireNotNull(context.getExternalFilesDir("workshop-ui-verification"))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    companion object {
        private const val DOCK_LABEL = "Store"
        private fun item(id: String, subscribed: Boolean?) =
            WorkshopItem(id, 550, "Workshop fixture $id", canSubscribe = true, subscribed = subscribed)
    }
}
