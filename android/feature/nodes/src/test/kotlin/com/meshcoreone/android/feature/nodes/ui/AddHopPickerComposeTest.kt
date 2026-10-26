// AndroidOnly: WP-311 Mounted Add-Hop consumer policy and multi-add regressions.
package com.meshcoreone.android.feature.nodes.ui

import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.feature.nodes.NodesNavigation
import com.meshcoreone.android.feature.nodes.path.AddHopFilter
import com.meshcoreone.android.feature.nodes.path.PathManagementStateHolder
import com.meshcoreone.android.feature.nodes.path.RecentHopsStore
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Harness
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddHopPickerApi31ComposeTest : AddHopPickerComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddHopPickerApi37ComposeTest : AddHopPickerComposeTestBase()

abstract class AddHopPickerComposeTestBase : NodesScreensComposeTestBase() {
    override fun rootView(): View =
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: super.rootView()

    private fun search(text: String) {
        val field = allSemanticsNodes().single { it.config.contains(SemanticsActions.SetText) }
        val action = assertNotNull(field.config[SemanticsActions.SetText].action)
        controller.get().runOnUiThread { assertTrue(action(AnnotatedString(text))) }
        advanceFrames()
    }

    private fun select(filter: AddHopFilter) {
        click(scrollToAction(controller.get().getString(filter.labelRes)))
    }

    @Test
    fun contactDetailPathEditorMountsThePolicyPickerRatherThanRawCandidates() {
        val harness = Harness().connect()
        val radio = checkNotNull(harness.session.radioId)
        val target = Fixtures.pathContact(radioId = radio, name = "Path target", publicKey = Fixtures.repeated(0xDD))
        val favorite = Fixtures.pathContact(radioId = radio, name = "Favorite relay", publicKey = Fixtures.repeated(0xCA), isFavorite = true)
        val other = Fixtures.pathContact(radioId = radio, name = "Other relay", publicKey = Fixtures.repeated(0x11))
        harness.store.contacts += listOf(target, favorite, other)
        mount { ContactDetailScreen(target, harness.dependencies, NodesNavigation.NONE) }
        click(scrollToAction(controller.get().getString(C.contactsDetailEditPath)))
        click(awaitAction(controller.get().getString(com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_pathedit_addhop)))
        select(AddHopFilter.FAVORITES)
        assertTrue(isDisplayed(scrollToAction(favorite.name)))
        assertEquals(null, actionNode(other.name))
        search("zz")
        assertEquals(null, actionNode(favorite.name))
        search("")
        click(scrollToAction(favorite.name))
        assertTrue(isDisplayed(awaitAction(controller.get().getString(C.contactsCommonDone))))
        assertTrue(harness.contactService.setPaths.isEmpty())
    }

    @Test
    fun mountedSearchKeepsIndependentNameAndKeyBranchesAndMultiAddOpen() {
        val harness = Harness().connect()
        val radio = checkNotNull(harness.session.radioId)
        val named = Fixtures.pathContact(radioId = radio, name = "Cafe relay", publicKey = Fixtures.repeated(0x11), isFavorite = true)
        val keyed = Fixtures.pathContact(radioId = radio, name = "Key relay", publicKey = Fixtures.repeated(0xCA))
        harness.store.contacts += listOf(named, keyed)
        val holder = PathManagementStateHolder(harness.dependencies, CoroutineScope(Dispatchers.Main))
        runBlocking { holder.loadContacts(radio) }
        var dismissed = false
        mount { AddHopPickerDialog(holder) { dismissed = true } }

        search("ca")
        assertTrue(isDisplayed(scrollToAction(named.name)))
        val keyRow = scrollToAction(keyed.name)
        assertTrue(isDisplayed(keyRow))
        assertTrue(keyRow.boundsInRoot.height / currentDensity() >= 48f)
        search("relay")
        assertTrue(isDisplayed(scrollToAction(named.name)))
        assertTrue(isDisplayed(scrollToAction(keyed.name)))
        search("Key")
        assertEquals(null, actionNode(named.name))
        click(scrollToAction(keyed.name))
        assertEquals(listOf(keyed.publicKey), holder.state.value.editablePath.map { it.publicKey })
        assertFalse(dismissed, "A successful first add must not dismiss the multi-add picker")
        search("")
        select(AddHopFilter.FAVORITES)
        click(scrollToAction(named.name))
        assertEquals(listOf(keyed.publicKey, named.publicKey), holder.state.value.editablePath.map { it.publicKey })
        assertFalse(dismissed)
        click(awaitAction(controller.get().getString(C.contactsCommonDone)))
        assertTrue(dismissed)
        assertTrue(harness.contactService.calls.isEmpty(), "Editing the draft must not send radio work")
    }

    @Test
    fun reopenedRecentFilterShowsRadioScopedOrderedDeduplicatedEightKeyHistory() {
        val harness = Harness().connect()
        val radio = checkNotNull(harness.session.radioId)
        val contacts = (0xA1..0xA9).map { value ->
            Fixtures.pathContact(radioId = radio, name = "Relay $value", publicKey = Fixtures.repeated(value))
        }
        harness.store.contacts += contacts
        val holder = PathManagementStateHolder(harness.dependencies, CoroutineScope(Dispatchers.Main))
        runBlocking { holder.loadContacts(radio) }
        contacts.forEach(holder::appendHop)
        holder.appendHop(contacts[5])
        val expected = listOf(contacts[5]) + contacts.asReversed().filterNot { it == contacts[5] }.take(7)
        assertEquals(expected.map { it.publicKey.hexString }, harness.preferences.values[RecentHopsStore.defaultsKey(radio)])
        assertTrue(harness.preferences.values.getValue(RecentHopsStore.defaultsKey(radio)).all { it == it.lowercase() })
        val reopened = PathManagementStateHolder(harness.dependencies, CoroutineScope(Dispatchers.Main))
        runBlocking { reopened.loadContacts(radio) }
        mount { AddHopPickerDialog(reopened) {} }
        select(AddHopFilter.RECENT)
        assertTrue(isDisplayed(scrollToAction(expected.first().name)))
        click(scrollToAction(expected.first().name))
        assertEquals(expected.first().publicKey, reopened.state.value.editablePath.single().publicKey)
        assertEquals(expected.map { it.publicKey }, reopened.state.value.recentPublicKeys)
        val other = PathManagementStateHolder(harness.dependencies, CoroutineScope(Dispatchers.Main))
        other.loadRecentKeys(Fixtures.radio())
        assertTrue(other.recentPublicKeys.isEmpty())
    }

    @Test
    fun discoveredFilterLabelsActualDiscoveredRowsAndFullPathDismisses() {
        val harness = Harness().connect()
        val radio = checkNotNull(harness.session.radioId)
        val node = Fixtures.discoveredRepeater(radioId = radio, name = "Discovered relay")
        harness.store.discovered += node
        val holder = PathManagementStateHolder(harness.dependencies, CoroutineScope(Dispatchers.Main))
        runBlocking { holder.loadContacts(radio) }
        repeat(holder.maxHopCount - 1) { holder.appendHop(node) }
        var dismissed = false
        mount { AddHopPickerDialog(holder) { dismissed = true } }
        select(AddHopFilter.DISCOVERED)
        val row = scrollToAction(node.name)
        assertTrue(row.config[SemanticsProperties.Text].any { it.text == controller.get().getString(AddHopFilter.DISCOVERED.labelRes) })
        click(row)
        assertTrue(holder.isPathFull)
        assertTrue(dismissed)
        assertTrue(harness.contactService.calls.isEmpty())
    }
}
