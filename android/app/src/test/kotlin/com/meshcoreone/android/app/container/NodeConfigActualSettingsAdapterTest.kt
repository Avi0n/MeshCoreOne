// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/NodeConfigServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.CommandCode
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeConfigActualSettingsAdapterTest : RoomProcessTest() {
    @Test
    fun unmodeledAdvertisementPolicyReachesTheActualDevicePacket() = runTest {
        withConnectedNode { harness, radio, node ->
            val before = radio.sent.size
            val config = MeshCoreNodeConfig(
                otherSettings = MeshCoreNodeConfig.OtherSettings(manualAddContacts = 0u, advertLocationPolicy = 99u),
            )
            val import = backgroundScope.async {
                node.nodeConfigService.importConfig(config, ConfigSections(otherSettings = true), node.token.radioId)
            }
            harness.eventually("other-params import completes") { import.isCompleted }
            import.await()
            val packets = radio.sent.drop(before).filter { it[0] == CommandCode.SET_OTHER_PARAMS.rawValue }
            assertEquals(1, packets.size)
            assertEquals(99u.toUByte(), packets.single()[3])
        }
    }

    @Test
    fun unchangedOtherParametersSendNoCommitThroughTheActualAdapter() = runTest {
        withConnectedNode { harness, radio, node ->
            val before = radio.sent.size
            val config = MeshCoreNodeConfig(otherSettings = MeshCoreNodeConfig.OtherSettings())
            val import = backgroundScope.async {
                node.nodeConfigService.importConfig(config, ConfigSections(otherSettings = true), node.token.radioId)
            }
            harness.eventually("unchanged other-params import completes") { import.isCompleted }
            import.await()
            assertTrue(radio.sent.drop(before).none { it[0] == CommandCode.SET_OTHER_PARAMS.rawValue })
        }
    }

    private suspend fun TestScope.withConnectedNode(
        block: suspend TestScope.(ContainerHarness, FirmwareRadio, RadioSessionContainer) -> Unit,
    ) {
        val harness = ContainerHarness(this, store)
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val radio = harness.links.radios.single()
            radio.respond = { packet ->
                if (packet[0] == CommandCode.SET_OTHER_PARAMS.rawValue) listOf(Bytes.of(0)) else null
            }
            block(harness, radio, checkNotNull(harness.sessions.current))
        } finally {
            harness.container.close()
        }
    }
}
