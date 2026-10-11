// AndroidOnly: WP-407 Unlock and process navigation ownership regressions.
package com.meshcoreone.android.app.platform

import com.meshcoreone.android.app.navigation.*
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ProcessStartTest {
    @Test fun lockedEntryDoesNotConstructStorageAndRacingUnlockStartsOnce() {
        val calls = AtomicInteger()
        val gate = UnlockedProcessStart { calls.incrementAndGet() }
        repeat(10) { gate.onUnlockState(false) }
        assertEquals(0, calls.get())
        val work = (1..20).map { CompletableFuture.runAsync { gate.onUnlockState(true) } }
        CompletableFuture.allOf(*work.toTypedArray()).join()
        assertEquals(1, calls.get())
    }

    @Test fun publicRestorationAttachesToTheProcessWithoutReplacingReadiness() {
        val radio = RadioId(UUID.randomUUID())
        val process = NavigationCoordinator(NavigationState(radioId = radio, generation = 4, navigationReady = true))
        val host = NavigationCoordinator()
        host.navigateToSetting(SettingsDetail.LANGUAGE)
        process.attachHostState(NavigationSavedState.restore(NavigationSavedState.encode(host.state.value)))
        val state = process.state.value
        assertEquals(AppTab.SETTINGS, state.selectedTab)
        assertEquals(SettingsDetail.LANGUAGE, state.selectedSetting)
        assertFalse(state.tabBarVisible)
        assertEquals(radio, state.radioId)
        assertEquals(4L, state.generation)
        assertTrue(state.navigationReady)
    }

    @Test fun startupActionWinsOverRestoredHostDefaults() {
        val process = NavigationCoordinator()
        val contact = contact(RadioId(UUID.randomUUID()))
        process.navigateToChat(contact)
        val expected = process.state.value
        val restored = NavigationCoordinator().apply { navigateToSetting(SettingsDetail.BACKUP) }.state.value
        process.attachHostState(restored)
        assertEquals(expected, process.state.value)
    }

    @Test fun equalUuidOnDifferentRadiosIsNotTheSameConversation() {
        val first = contact(RadioId(UUID.randomUUID()))
        val second = first.copy(radioId = RadioId(UUID.randomUUID()))
        assertNotEquals(ChatSelection.Direct(first), ChatSelection.Direct(second))
        assertEquals(2, setOf(ChatSelection.Direct(first), ChatSelection.Direct(second)).size)
    }

    private fun contact(radio: RadioId) = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 5 }),
        name = "Synthetic peer", lastHeardTimestamp = null)
}
