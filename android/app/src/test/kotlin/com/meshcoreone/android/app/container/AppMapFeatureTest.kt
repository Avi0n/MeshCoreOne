// AndroidOnly: WP-312 Real App map adapters over the process Room store and existing synthetic radio graph.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.contacts.ContactServiceError
import com.meshcoreone.android.feature.map.MapSelection
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.junit.Test

class AppMapFeatureTest : RoomProcessTest() {
    @Test fun locatedMarkersKeepActualIdentityAndUseExistingDetailAndChatNavigation() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = ContactDTO(
                radioId = radio, publicKey = key(42), name = "Map contact", typeRawValue = 1u,
                latitude = 37.0, longitude = -122.0, lastHeardTimestamp = 0u, isFavorite = true,
            )
            store.saveContact(contact)
            store.upsertDiscoveredNode(
                radio,
                ContactFrame(
                    contact.publicKey, contact.type, 0u, 255u,
                    com.meshcoreone.android.core.protocol.bytes.Bytes.EMPTY,
                    "Already a contact", 1u, 38.0, -123.0, 1u,
                ),
            )
            val invalid = store.upsertDiscoveredNode(
                radio,
                ContactFrame(
                    key(44), contact.type, 0u, 255u,
                    com.meshcoreone.android.core.protocol.bytes.Bytes.EMPTY,
                    "Invalid fix", 1u, 91.0, -123.0, 1u,
                ),
            ).node
            val feature = h.container.mapFeature
            val markers = feature.dataSource.load().markers
            assertFalse(markers.any { it.marker.id == invalid.id || it.isDiscovered })
            val row = markers.single { it.marker.id == contact.id }
            assertEquals(contact, (row.selection as MapSelection.Contact).contact)
            assertTrue(row.isFavorite)
            val actions = assertNotNull(feature.actions)
            actions.openContact(contact)
            assertEquals(contact, h.container.navigation.state.value.pendingContactDetail)
            actions.openChat(contact)
            assertEquals(contact, h.container.navigation.state.value.pendingChatContact)
        } finally { h.container.close() }
    }

    @Test fun discoveryRetainsItsDtoAndAStaleRadioCannotNavigateOrAddToTheReplacementSession() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val radio = assertNotNull(h.appState.currentRadioId)
            val frame = ContactFrame(
                key(43), com.meshcoreone.android.core.protocol.model.ContactType.REPEATER,
                0u, 255u, com.meshcoreone.android.core.protocol.bytes.Bytes.EMPTY,
                "Discovered map node", 20u, 38.0, -123.0, 20u,
            )
            val node = store.upsertDiscoveredNode(radio, frame).node
            val feature = h.container.mapFeature
            val row = feature.dataSource.load().markers.single { it.marker.id == node.id }
            assertEquals(node, (row.selection as MapSelection.Discovered).node)
            val oldContact = ContactDTO(
                radioId = radio, publicKey = key(42), name = "Old radio contact",
                latitude = 37.0, longitude = -122.0, lastHeardTimestamp = 0u,
            )
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED); h.settle()
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            // Both links have the same synthetic radio key by default; use a genuinely foreign identity.
            val foreign = RadioId(java.util.UUID(0, 999))
            val actions = assertNotNull(feature.actions)
            assertFailsWith<ContactServiceError.NotConnected> { actions.openContact(oldContact.copy(radioId = foreign)) }
            assertFailsWith<ContactServiceError.NotConnected> { actions.addDiscovered(node.copy(radioId = foreign)) }
            assertNull(h.container.navigation.state.value.pendingContactDetail)
        } finally { h.container.close() }
    }
}
