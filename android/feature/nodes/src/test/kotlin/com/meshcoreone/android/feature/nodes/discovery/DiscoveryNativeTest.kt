// AndroidOnly: WP-311 Native coverage of discovery add/clear, coalesced reloads and blocked-contact loading.
package com.meshcoreone.android.feature.nodes.discovery

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.feature.nodes.contacts.BlockedContactsStateHolder
import com.meshcoreone.android.feature.nodes.deps.NodesContactFailure
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Fixtures.node
import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.scenario
import com.meshcoreone.android.feature.nodes.support.settle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Test

class DiscoveryNativeTest {
    @Test
    fun `adding a node sends its frame and marks it added after reload`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val relay = node(radioId = radio, name = "Relay", publicKey = Fixtures.repeated(0x31))
        harness.store.discovered += relay
        val holder = DiscoveryStateHolder(harness.dependencies, scope)
        holder.loadDiscoveredNodes()
        assertFalse(holder.isAdded(relay))
        holder.addNode(relay)
        assertEquals(listOf("add:Relay"), harness.contactService.calls)
        assertTrue(holder.isAdded(relay))
        assertNull(holder.state.value.addingNodeId)
    }

    @Test
    fun `a full node table reports the device limit or the simple message`() = scenario {
        val harness = Harness(clock).connect()
        harness.contactService.addBehavior = { _, _ -> throw NodesContactFailure.ContactTableFull() }
        val holder = DiscoveryStateHolder(harness.dependencies, scope)
        holder.addNode(node(name = "Relay"))
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_add_error_nodelistfull, 350), holder.state.value.errorMessage)
        harness.session.device = null
        holder.addNode(node(name = "Relay"))
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_add_error_nodelistfullsimple), holder.state.value.errorMessage)
    }

    @Test
    fun `bursts of reload requests coalesce into one load after the debounce`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val holder = DiscoveryStateHolder(harness.dependencies, scope)
        holder.scheduleCoalescedReload()
        holder.scheduleCoalescedReload()
        harness.store.discovered += node(radioId = radio, name = "Late")
        settle()
        clock.advanceBy(49.milliseconds)
        assertFalse(holder.state.value.hasLoadedOnce)
        clock.advanceBy(1.milliseconds)
        assertEquals(listOf("Late"), holder.state.value.discoveredNodes.map { it.name })
        assertEquals(listOf(DiscoveryStateHolder.RELOAD_DEBOUNCE), clock.sleeps)
    }

    @Test
    fun `clear all empties the list and announces it`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        harness.store.discovered += node(radioId = radio, name = "A")
        val holder = DiscoveryStateHolder(harness.dependencies, scope)
        holder.loadDiscoveredNodes()
        holder.clearAllAndAnnounce()
        assertTrue(holder.state.value.discoveredNodes.isEmpty())
        assertTrue(harness.store.discovered.isEmpty())
        assertEquals(listOf(NodesMessage.res(R.string.l10n_app_contacts_contacts_discovery_clearedallnodes)), harness.announcer.messages)
    }

    @Test
    fun `long paths collapse to the first and last three hops`() {
        assertEquals("A,B,C,D,E,F", DiscoveryStateHolder.formattedPath(listOf("A", "B", "C", "D", "E", "F")))
        assertEquals("A,B,C\u2026E,F,G", DiscoveryStateHolder.formattedPath(listOf("A", "B", "C", "D", "E", "F", "G")))
    }

    @Test
    fun `blocked contacts load from the connected store`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        harness.store.contacts += Fixtures.contact(radioId = radio, name = "Blocked", isBlocked = true)
        harness.store.contacts += Fixtures.contact(radioId = radio, name = "Open")
        val holder = BlockedContactsStateHolder(harness.dependencies)
        holder.loadBlockedContacts()
        assertEquals(listOf("Blocked"), holder.state.value.contacts.map { it.name })
        assertFalse(holder.state.value.isLoading)
    }
}
