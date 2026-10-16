package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.handleSystemBack
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_diagnostics_header
import one.meshcore.shared.resources.settings_feedback_title
import one.meshcore.shared.resources.tools_rx_log
import org.jetbrains.compose.resources.stringResource

internal class AdaptiveShellFixture(
    val navigation: NavigationCoordinator,
    private val physicalWidth: Int,
    dependencies: ShellDependencies?,
) {
    var dependencies by mutableStateOf(dependencies)
    var widthDp by mutableStateOf(COMPACT_WIDTH)
    val tabLabels = mutableMapOf<AppTab, String>()
    var rxLogLabel = ""
        private set
    var diagnosticsLabel = ""
        private set
    var feedbackLabel = ""
        private set

    @Composable
    fun Content() {
        TimestampFormatting {
            val current by navigation.selectedTab.collectAsState()
            val tools by navigation.selectedTool.collectAsState()
            val settings by navigation.selectedSetting.collectAsState()
            val stacks by navigation.backStacks.collectAsState()
            val discovery by navigation.nodesShowingDiscovery.collectAsState()
            val tab = AppTab.fromIndex(current)
            rememberShellBackState(
                sheet = null,
                currentTab = tab,
                selectedTool = tools,
                selectedSetting = settings,
                backStack = stacks[tab],
                onSheet = {},
                onSystemBack = { navigation.handleSystemBack() },
                onClearStaging = {},
            )
            AppTab.entries.forEach { tabLabels[it] = tabLabel(it) }
            rxLogLabel = stringResource(Res.string.tools_rx_log)
            diagnosticsLabel = stringResource(Res.string.settings_diagnostics_header)
            feedbackLabel = stringResource(Res.string.settings_feedback_title)
            val native = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(physicalWidth / WIDE_WIDTH, native.fontScale)) {
                AppShell(
                    coordinator = navigation,
                    modifier = Modifier.width(widthDp.dp).fillMaxHeight().testTag(ROOT),
                    nodesContent = { Marker(if (discovery) DISCOVERY else NODES) },
                    chatsContent = { Marker(CHATS) },
                    chatDetailContent = { Marker(it.conversationId) },
                    contactDetailContent = { id, _ -> Marker(id) },
                    toolsContent = {
                        RolePane(TOOLS) {
                            val deps = dependencies
                            if (deps == null) {
                                Text(tools?.name ?: TOOLS)
                            } else {
                                ShellToolsTab(
                                    deps,
                                    DeviceConnectionState.READY,
                                    networkOffline = true,
                                    deviceLocation = { null },
                                )
                            }
                        }
                    },
                    settingsContent = {
                        RolePane(SETTINGS) {
                            val deps = dependencies
                            if (deps == null) {
                                Text(settings?.name ?: SETTINGS)
                            } else {
                                ShellSettingsTab(deps, DeviceConnectionState.READY, networkOffline = true, onSheet = {})
                            }
                        }
                    },
                    mapContent = { Marker(MAP) },
                )
            }
        }
    }

    @Composable
    private fun Marker(tag: String) {
        Box(Modifier.fillMaxSize().testTag(tag)) { Text(tag) }
    }

    @Composable
    private fun RolePane(tag: String, content: @Composable () -> Unit) {
        val role = LocalShellContentRole.current
        Box(Modifier.fillMaxSize().testTag("$tag:$role")) { content() }
    }

    companion object {
        const val COMPACT_WIDTH = 480f
        const val MEDIUM_WIDTH = 700f
        const val WIDE_WIDTH = 1_000f
        const val ROOT = "adaptive-shell"
        const val NODES = "nodes-list"
        const val DISCOVERY = "nodes-discovery"
        const val CHATS = "chats-list"
        const val TOOLS = "tools-pane"
        const val SETTINGS = "settings-pane"
        const val MAP = "map-pane"
    }
}
