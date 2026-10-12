// AndroidOnly: WP-316 Mount the real CLI screen through disconnected/connected generations without hardware.
package com.meshcoreone.android.wp316diagnostic

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.feature.tools.diagnostics.ToolsDiagnosticsDependencies
import com.meshcoreone.android.feature.tools.diagnostics.ToolsDiagnosticsScreen
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliRepeaterAdminPort
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliToolFeatureDependencies
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogFeatureDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "ldrtl-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisconnectedCliTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val version = MutableStateFlow(0)
    private var service: CliRepeaterAdminPort? = null
    private val advertisements = mutableListOf<Boolean>()
    private val connectedService = CliRepeaterAdminPort { _, _, _ ->
        error("Mounting the CLI must not send a remote command")
    }
    private val dependencies = ToolsDiagnosticsDependencies(
        cli = CliToolFeatureDependencies(
            repeaterAdminService = { service },
            remoteNodeService = { null },
            settingsService = { null },
            dataStore = { null },
            radioId = { null },
            connectedDevice = { null },
            sendSelfAdvert = { advertisements += it },
        ),
        rxLog = RxLogFeatureDependencies({ null }, { null }, { null }),
        radioStats = { null },
        isConnected = { true },
        localPublicKeyPrefix = { null },
        connectionVersion = version,
    )

    @Suppress("DEPRECATION")
    @Before fun mountNativeScreen() {
        val application = RuntimeEnvironment.getApplication()
        application.applicationInfo.flags = application.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val resources = application.resources
        val configuration = Configuration(resources.configuration).apply {
            fontScale = 2f
            setLayoutDirection(Locale.forLanguageTag("ar"))
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().applicationInfo.flags = host.get().applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        host.get().resources.updateConfiguration(configuration, host.get().resources.displayMetrics)
        host.get().window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        assertEquals(2f, host.get().resources.configuration.fontScale, 0f)
        assertEquals(View.LAYOUT_DIRECTION_RTL, host.get().resources.configuration.layoutDirection)
        host.get().setContent {
            Box(Modifier.width(360.dp).height(900.dp)) {
                MeshCoreTheme(ThemeRegistry.default, AppColorSchemePreference.LIGHT, systemDark = false, motionScale = 0f) {
                    ToolsDiagnosticsScreen(dependencies)
                }
            }
        }
    }

    @After fun disposeNativeScreen() {
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
    }

    private fun offlineState() {
        compose.onNodeWithText(host.get().getString(AppToolsStrings.toolsCliNotConnected))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText(host.get().getString(AppToolsStrings.toolsCliNotConnectedDescription))
            .assertIsDisplayed()
        compose.onNodeWithTag("cli-input").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).assertCountEquals(0)
    }

    @Test fun missingCliServiceShowsLocalizedExplanationWithoutAnEditableOrFocusedTerminal() {
        offlineState()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), advertisements) }
    }

    @Test fun reconnectRestoresFocusedCommandsAndHistoryWithoutDuplicateServiceActions() {
        offlineState()
        compose.runOnIdle { service = connectedService; version.value += 1 }
        compose.onNodeWithTag("cli-input").assertIsDisplayed().assertIsFocused()
            .performTextInput("advert")
        compose.onNodeWithTag("cli-input").performImeAction()
        compose.runOnIdle { assertEquals(listOf(false), advertisements) }
        compose.runOnIdle { service = null; version.value += 1 }
        offlineState()
        compose.runOnIdle { assertEquals(listOf(false), advertisements) }
        compose.runOnIdle { service = connectedService; version.value += 1 }
        compose.onNodeWithTag("cli-input").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText(host.get().getString(AppToolsStrings.toolsCliHistoryUp)).performClick()
        compose.onNodeWithTag("cli-input").assertTextContains("advert").performImeAction()
        compose.runOnIdle { assertEquals(listOf(false, false), advertisements) }
    }
}
