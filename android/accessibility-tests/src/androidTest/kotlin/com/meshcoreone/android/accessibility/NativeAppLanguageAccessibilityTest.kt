// AndroidOnly: WP-503 Observe real app-locale choice semantics, font200 controls and Activity/configuration return without message translation.
package com.meshcoreone.android.accessibility

import android.content.res.Resources
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.MeshCoreApplication
import com.meshcoreone.android.app.navigation.SettingsDetail
import com.meshcoreone.android.app.platform.AppLanguages
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.feature.onboarding.OnboardingState
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAppLanguageAccessibilityTest : DeviceAccessibilityHarness() {
    @get:Rule val selectionErrors = ErrorCollector()
    private val container by lazy {
        val application = requireNotNull(activity.application as? MeshCoreApplication)
        runBlocking { application.container.await() }
    }
    private val selection = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and hasClickAction()

    @Test fun actualLanguageChoicesExposeTheirOwnNameAndSelectedStateWithContainedFont200Actions() = withLanguage {
        action(activity.getString(com.meshcoreone.android.R.string.platform_system_language))
        val current = AppLanguages.selected(activity)
        val controls = compose.onAllNodes(selection).fetchSemanticsNodes()
        println("WP503_LANGUAGE_SELECTION_INVENTORY|count=${controls.size}|names=${controls.map(::narrative)}" +
            "|selected=${controls.map { it.config[SemanticsProperties.Selected] }}")
        assertEquals(AppLanguages.supported.size, controls.size)
        for ((index, tag) in AppLanguages.supported.withIndex()) {
            val label = autonym(tag)
            action(label)
            selectionErrors.checkSucceeds {
                val control = compose.onAllNodes(selection)[index]
                assertEquals(current == tag, control.fetchSemanticsNode().config[SemanticsProperties.Selected])
                assertTarget(control, "$tag:selected-control")
            }
            val named = compose.onAllNodes(hasText(label) and selection).fetchSemanticsNodes()
            println("WP503_LANGUAGE_SELECTION|tag=$tag|label=$label|namedSelectedControls=${named.size}|expectedSelected=${current == tag}")
            selectionErrors.checkSucceeds {
                assertEquals("Each native language selection must expose its own name and selected state: $tag", 1, named.size)
                assertEquals(current == tag, named.single().config[SemanticsProperties.Selected])
            }
        }
    }

    @Test fun actualNamedChineseChoiceAndSystemResetPreserveLanguageDetailAcrossNativeRecreation() = withLanguage {
        assertEquals("The owned fresh native profile starts with system app language", "", AppLanguages.selected(activity))
        val originalLanguage = activity.resources.configuration.locales[0].language
        val stacks = container.navigation.state.value.stacks
        try {
            val previous = activity
            action(autonym("zh-Hans")).performClick()
            val chinese = awaitLocale(previous, "zh-Hans", "zh")
            assertSame(chinese, activity)
            assertEquals(stacks, container.navigation.state.value.stacks)
            assertEquals(SettingsDetail.LANGUAGE, container.navigation.state.value.selectedSetting)
            assertFont200Unclipped(compose.onNodeWithTag("navigation-heading", useUnmergedTree = true))
            action(autonym("zh-Hans"))
            assertEquals(1, compose.onAllNodes(selection).fetchSemanticsNodes().count { it.config[SemanticsProperties.Selected] })
            println("WP503_LANGUAGE_LOCALE|tag=zh-Hans|resourcesLanguage=zh|newActivity=true|selectedControls=1|detailRetained=true")

            action(activity.getString(com.meshcoreone.android.R.string.platform_system_language)).performClick()
            val system = awaitLocale(chinese, "", originalLanguage)
            assertSame(system, activity)
            assertEquals(stacks, container.navigation.state.value.stacks)
            assertEquals(SettingsDetail.LANGUAGE, container.navigation.state.value.selectedSetting)
            assertFont200Unclipped(compose.onNodeWithTag("navigation-heading", useUnmergedTree = true))
            action(autonym("en"))
            assertEquals(0, compose.onAllNodes(selection).fetchSemanticsNodes().count { it.config[SemanticsProperties.Selected] })
            println("WP503_LANGUAGE_LOCALE|tag=system|resourcesLanguage=${system.resources.configuration.locales[0].language}" +
                "|newActivity=true|selectedControls=0|detailRetained=true")
        } finally {
            val host = requireNotNull(resumedActivity()) { "Locale audit must leave a real resumed host for cleanup" }
            if (AppLanguages.selected(host).isNotEmpty()) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLanguages.select(host, "") }
                awaitLocale(host, "", originalLanguage)
            }
            assertEquals("", AppLanguages.selected(activity))
        }
    }

    private fun withLanguage(block: () -> Unit) {
        val onboarding = requireNotNull(container.onboarding)
        val originalFlag = onboarding.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED)
        val originalStacks = container.navigation.state.value.stacks
        try {
            compose.runOnUiThread { onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, true) }
            compose.waitUntil { compose.onAllNodesWithTag("tab:SETTINGS").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("tab:SETTINGS").performClick()
            action(activity.getString(AppSettingsStrings.languageTitle)).performClick()
            assertEquals(SettingsDetail.LANGUAGE, container.navigation.state.value.selectedSetting)
            assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
            block()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
            assertEquals(null, container.navigation.state.value.selectedSetting)
            assertEquals(originalStacks, container.navigation.state.value.stacks)
            assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
            println("WP503_LANGUAGE_RETURN|nativeBack=true|stacksUnchanged=true|realProcessGraph=true|physicalTalkBack=false")
        } finally {
            compose.runOnUiThread {
                if (container.navigation.state.value.selectedSetting == SettingsDetail.LANGUAGE) container.navigation.back()
                container.navigation.selectTab(AppTab.CHATS)
                onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, originalFlag)
            }
        }
    }

    private fun resumedActivity(): MainActivity? {
        var host: MainActivity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull()
        }
        return host
    }

    private fun awaitLocale(previous: MainActivity, tag: String, language: String): MainActivity {
        var observed: MainActivity? = null
        val systemSnapshot = Resources.getSystem().configuration.locales[0].language
        try {
            compose.waitUntil {
                observed = resumedActivity()
                observed?.let { host ->
                    host !== previous && AppLanguages.selected(host) == tag &&
                        host.resources.configuration.locales[0].language == language
                } == true
            }
        } finally {
            println("WP503_LANGUAGE_LOCALE_WAIT|tag=$tag|expectedLanguage=$language|systemSnapshot=$systemSnapshot" +
                "|observedLanguage=${observed?.resources?.configuration?.locales?.get(0)?.language}" +
                "|observedTag=${observed?.let(AppLanguages::selected)}|newActivity=${observed != null && observed !== previous}")
        }
        val host = requireNotNull(observed)
        assertNotSame(previous, host)
        assertEquals(2f, host.resources.configuration.fontScale, 0f)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { host.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL }
        compose.waitForIdle()
        return host
    }

    private fun autonym(tag: String) = Locale.forLanguageTag(tag).let { it.getDisplayName(it) }

    private fun scrollParent(node: SemanticsNode) = generateSequence(node.parent) { it.parent }.firstOrNull {
        it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
    }

    private fun viewport(node: SemanticsNode): Rect {
        val safe = nativeWindowViewport(compose, activity.window.decorView, node)
        return scrollParent(node)?.let { safe.intersect(it.boundsInWindow) } ?: safe
    }

    private fun action(label: String): SemanticsNodeInteraction {
        val interaction = compose.onNode(hasText(label) and hasClickAction())
        val initial = interaction.fetchSemanticsNode()
        if (scrollParent(initial) != null) interaction.performScrollTo()
        val before = interaction.fetchSemanticsNode()
        val safe = viewport(before)
        val offset = before.boundsInWindow.topLeft - before.boundsInRoot.topLeft
        val top = before.positionInRoot.y + offset.y
        val bottom = top + before.size.height
        val delta = if (bottom > safe.bottom) bottom - safe.bottom else if (top < safe.top) top - safe.top else 0f
        if (delta != 0f) {
            val parent = requireNotNull(scrollParent(before))
            compose.onNode(SemanticsMatcher("Actual native language scroll viewport") { it.id == parent.id }, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, delta)) }
        }
        interaction.assertIsDisplayed()
        assertFont200Unclipped(compose.onNode(
            hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true,
        ))
        assertTarget(interaction, label)
        return interaction
    }

    private fun assertTarget(interaction: SemanticsNodeInteraction, label: String) {
        val node = interaction.fetchSemanticsNode()
        val actual = viewport(node)
        val target = node.touchBoundsInRoot.translate(node.boundsInWindow.topLeft - node.boundsInRoot.topLeft)
        val density = activity.resources.displayMetrics.density
        println("WP503_LANGUAGE_TARGET|label=$label|bounds=$target|viewport=$actual")
        assertTrue("Native language actions must be wholly contained and at least48dp",
            target.left >= actual.left && target.top >= actual.top && target.right <= actual.right && target.bottom <= actual.bottom &&
                target.width / density >= 48f && target.height / density >= 48f)
    }
}
