package one.meshcore.shared.contact.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.pathedit.PathManagementViewModel
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactPathDiscoveryLifetimeInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacingTheViewModelCancelsTheRetiredDiscoveryOwner() {
        var useReplacement by mutableStateOf(false)
        lateinit var original: PathManagementViewModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            val first = remember { pathViewModel(scope).also { original = it } }
            val replacement = remember { pathViewModel(scope) }
            ContactDetailPathEffects(
                contactId = CONTACT_ID,
                pathViewModel = if (useReplacement) replacement else first,
                discoveryResult = null,
                radioId = null,
                contactService = null,
                reload = {},
            )
        }

        compose.runOnIdle {
            original.editState.value = original.editState.value.copy(isDiscovering = true)
            assertTrue(original.state.value.isDiscovering)
            useReplacement = true
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(original.state.value.isDiscovering) }
    }

    private fun pathViewModel(scope: CoroutineScope) = PathManagementViewModel(
        scope = scope,
        recents = RecentHopsStore(InMemoryPreferencesStore()),
        dependencies = PathManagementViewModel.Dependencies(
            contactService = { null },
            radioId = { null },
            errorText = { TEST_ERROR },
        ),
    )

    private companion object {
        const val CONTACT_ID = "contact-id"
        const val TEST_ERROR = "Test error"
    }
}
