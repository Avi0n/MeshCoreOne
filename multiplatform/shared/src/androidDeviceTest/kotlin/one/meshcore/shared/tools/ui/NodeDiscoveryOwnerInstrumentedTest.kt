package one.meshcore.shared.tools.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import one.meshcore.shared.meshcore.DiscoverResponse
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_node_discovery_repeaters
import one.meshcore.shared.resources.tools_node_discovery_scan_button
import one.meshcore.shared.resources.tools_node_discovery_stop_button
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodeDiscoveryOwnerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementEventOwnerStopsRetiredScanAndUsesReplacementFlow() {
        val firstEvents = MutableSharedFlow<MeshEvent>(replay = 1)
        val replacementEvents = MutableSharedFlow<MeshEvent>(replay = 1)
        var eventOwner by mutableStateOf<Flow<MeshEvent>?>(firstEvents)
        lateinit var scanLabel: String
        lateinit var stopLabel: String

        compose.setContent {
            val repeaterLabel = stringResource(Res.string.tools_node_discovery_repeaters)
            scanLabel = formattedStringResource(Res.string.tools_node_discovery_scan_button, repeaterLabel)
            stopLabel = stringResource(Res.string.tools_node_discovery_stop_button)
            MaterialTheme {
                NodeDiscoveryScreen(
                    sendDiscover = { _, _ -> TAG },
                    events = { eventOwner },
                    contactService = { null },
                    discoveredNodeStore = { null },
                    radioId = { RADIO },
                    isRadioReady = true,
                    isRadioConnected = { true },
                    onBack = {},
                )
            }
        }

        compose.onNode(hasText(scanLabel) and hasClickAction()).performClick()
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithText(stopLabel).fetchSemanticsNodes().isNotEmpty()
        }
        firstEvents.tryEmit(discoverEvent(FIRST_KEY_BYTE))
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithText(keyHex(FIRST_KEY_BYTE)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnIdle { eventOwner = replacementEvents }
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithText(scanLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(keyHex(FIRST_KEY_BYTE)).assertDoesNotExist()

        compose.onNode(hasText(scanLabel) and hasClickAction()).performClick()
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithText(stopLabel).fetchSemanticsNodes().isNotEmpty()
        }
        replacementEvents.tryEmit(discoverEvent(REPLACEMENT_KEY_BYTE))
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithText(keyHex(REPLACEMENT_KEY_BYTE)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun discoverEvent(keyByte: Int): MeshEvent = MeshEvent.DiscoverResponseReceived(
        DiscoverResponse(
            nodeType = ContactType.REPEATER.raw,
            snrIn = 5.0,
            snr = 8.5,
            rssi = -90,
            pathLength = 1,
            tag = one.meshcore.shared.tools.NodeDiscoveryViewModel.tagToLittleEndianBytes(TAG),
            publicKey = ByteArray(PUBLIC_KEY_LENGTH) { keyByte.toByte() },
        ),
    )

    private fun keyHex(keyByte: Int): String = ByteArray(PUBLIC_KEY_LENGTH) { keyByte.toByte() }.toUppercaseHex()

    private companion object {
        const val TAG = 0x1122_3344L
        const val FIRST_KEY_BYTE = 0x11
        const val REPLACEMENT_KEY_BYTE = 0x22
        const val PUBLIC_KEY_LENGTH = 32
        const val TIMEOUT_MS = 5_000L
        val RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000099")
    }
}
