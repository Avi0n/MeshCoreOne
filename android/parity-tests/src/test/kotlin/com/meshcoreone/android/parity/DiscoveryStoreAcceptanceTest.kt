// PortedFrom: MC1Tests/ViewModels/DiscoveryViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-501 Restore the original real-store delete assertion at the mounted native node consumer.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.container.createNodesFeatureDependencies
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.discovery.DiscoveryStateHolder
import com.meshcoreone.android.feature.nodes.model.DiscoverSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryStoreAcceptanceTest : RoomProcessTest() {
    @Test fun deletingDiscoveredNodeUpdatesRealRoomAndMountedFilterWithoutTouchingAnotherRadio() = runTest {
        val harness = ContainerHarness(this, store)
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val radio = requireNotNull(harness.appState.currentRadioId)
            val otherRadio = RadioId(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
            fun frame(byte: Int, name: String) = ContactFrame(
                publicKey = Bytes(ByteArray(32) { byte.toByte() }), type = ContactType.CHAT,
                flags = 0u, outPathLength = 0u, outPath = Bytes.EMPTY, name = name,
                lastAdvertTimestamp = 1_700_000_000u, latitude = 0.0, longitude = 0.0,
                lastModified = 1_700_000_000u,
            )
            store.upsertDiscoveredNode(radio, frame(0x11, "Keep"))
            store.upsertDiscoveredNode(radio, frame(0x22, "Drop"))
            store.upsertDiscoveredNode(otherRadio, frame(0x22, "Other radio"))
            val other = store.fetchDiscoveredNodes(otherRadio).single()
            val holder = DiscoveryStateHolder(harness.container.createNodesFeatureDependencies(), backgroundScope)
            holder.loadDiscoveredNodes()
            holder.updateVisibleNodes("", DiscoverSegment.ALL, NodeSortOrder.NAME, null)
            assertEquals(2, holder.state.value.visibleNodes.size)
            val drop = holder.state.value.discoveredNodes.single { it.name == "Drop" }

            holder.deleteDiscoveredNode(drop)

            assertNull(holder.state.value.errorMessage)
            assertEquals(listOf("Keep"), holder.state.value.discoveredNodes.map { it.name })
            assertEquals(listOf("Keep"), holder.state.value.visibleNodes.map { it.name })
            assertEquals(listOf("Keep"), store.fetchDiscoveredNodes(radio).map { it.name })
            assertEquals(other, store.fetchDiscoveredNodes(otherRadio).single())
            holder.loadDiscoveredNodes()
            assertEquals(listOf("Keep"), holder.state.value.visibleNodes.map { it.name })
        } finally {
            harness.container.close()
        }
    }
}
