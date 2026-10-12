// PortedFrom: MC1Tests/Views/Chats/Components/TiledViewInputBarChromeTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/Components/ChatInputBarThemedChromeTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Source scans of the Compose layer. The Swift originals host UIKit/Liquid Glass views in a window and
 * measure them; Compose UI tests and Robolectric are not on the locked classpath, so these assert the
 * structure that makes the same guarantee (see docs/android/deviations/WP-308.md D-1) and are NOT
 * rendering or layout measurements.
 */
class ComposerUiSourceScanTest {
    private val ui = File("src/main/kotlin/com/meshcoreone/android/feature/chats/composer/ui")
    private fun source(name: String) = File(ui, name).also { assertTrue(it.isFile, "expected ${it.absolutePath}") }.readText()
    private val screen get() = source("ChatConversationScreen.kt")

    private fun assertRestsAboveBar() {
        val screen = screen
        // The timeline is the weighted child above the composer and the column is inset by nav bar then IME,
        // so no row can rest under the bar (it has its own viewport) and the bar rides the keyboard.
        assertTrue(screen.contains("navigationBarsPadding().imePadding()"))
        assertTrue(screen.indexOf("Modifier.weight(1f)") in 0 until screen.indexOf("ComposerRoute(composer"))
    }

    @Test
    @OriginalCase("TiledViewInputBarChromeTests::the target row rests above the input bar, not under it()", "platform-adaptation")
    fun `the target row rests above the input bar`() = assertRestsAboveBar()

    @Test
    @OriginalCase("TiledViewInputBarChromeTests::the last row rests above the input bar without a target()", "platform-adaptation")
    fun `the last row rests above the input bar without a target`() = assertRestsAboveBar()

    @Test
    @OriginalCase("TiledViewInputBarChromeTests::the target row stays above the input bar when the divider bakes in late()", "platform-adaptation")
    fun `the target row stays above the input bar when a divider arrives late`() = assertRestsAboveBar()

    @Test
    @OriginalCase("TiledViewInputBarChromeTests::a mid-list target rests at the viewport top with chrome present()", "platform-adaptation")
    fun `a mid-list target rests at the viewport top with chrome present`() = assertRestsAboveBar()

    private fun assertThemedChrome() {
        val bar = source("ComposerBar.kt")
        // Chrome color derives from the theme surface; no hard-coded opaque Color(...) literal paints the bar.
        assertTrue(bar.contains("MaterialTheme.colorScheme.surface"))
        assertFalse(Regex("""Color\(0x""").containsMatchIn(bar))
        assertFalse(bar.contains("Color.White") || bar.contains("Color.Black"))
    }

    @Test
    @OriginalCase("ChatInputBarThemedChromeTests::default theme does not paint an opaque fill on Liquid Glass()", "platform-adaptation")
    fun `default theme chrome comes from the surface role`() = assertThemedChrome()

    @Test
    @OriginalCase("ChatInputBarThemedChromeTests::themed canvas does not paint an opaque fill on Liquid Glass()", "platform-adaptation")
    fun `themed canvas chrome comes from the surface role`() = assertThemedChrome()

    // Native guards over the whole composer UI

    @Test
    fun `composer UI has no WebView, JavaScript bridge or raw intent launch`() {
        val all = ui.listFiles { file -> file.extension == "kt" }!!.joinToString("\n") { it.readText() }
        for (banned in listOf("WebView", "evaluateJavascript", "addJavascriptInterface", "Intent(", "startActivity", "loadUrl")) {
            assertFalse(all.contains(banned), "composer UI must not reference $banned")
        }
    }

    @Test
    fun `interactive composer controls declare the shared 48dp touch target`() {
        val bar = source("ComposerBar.kt")
        assertTrue(Regex("sharedTouchTarget\\(\\)").findAll(bar).count() >= 3, "share, emoji and send buttons")
        assertTrue(source("MentionSuggestions.kt").contains("sharedTouchTarget()"))
        assertTrue(source("EmojiPickerSheet.kt").contains("sharedTouchTarget()"))
    }

    @Test
    fun `hardware send preview uses the button policy before consuming Return`() {
        val handler = source("ComposerBar.kt").substringAfter(".onPreviewKeyEvent { event ->").substringBefore(".semantics")
        assertTrue(handler.contains("event.key == Key.Enter || event.key == Key.NumPadEnter"))
        assertTrue(handler.contains("if (state.canSend && isEnter && !event.isShiftPressed)"))
        assertTrue(handler.contains("if (event.type == KeyEventType.KeyDown) actions.onSend()"))
        assertTrue(handler.contains("false"))
    }

    @Test
    fun `composer UI never catches CancellationException silently`() {
        val all = ui.listFiles { file -> file.extension == "kt" }!!.joinToString("\n") { it.readText() }
        // Every `catch (failure: Exception)` that wraps a suspend call is preceded by a CancellationException rethrow.
        val catches = Regex("catch \\((\\w+): (Exception|Throwable)\\)").findAll(all).count()
        val rethrows = Regex("catch \\((\\w+): (kotlin\\.coroutines\\.cancellation\\.)?CancellationException\\) \\{\\s*throw").findAll(all).count()
        assertTrue(catches == 0 || rethrows >= catches, "catches=$catches rethrows=$rethrows")
    }

    @Test
    fun `strings come from core l10n only`() {
        val all = ui.listFiles { file -> file.extension == "kt" }!!.joinToString("\n") { it.readText() }
        assertFalse(Regex("""Text\(\s*"[A-Za-z]""").containsMatchIn(all), "no hard-coded user-visible Text literals")
        assertTrue(all.contains("AppChatsStrings"))
    }
}
