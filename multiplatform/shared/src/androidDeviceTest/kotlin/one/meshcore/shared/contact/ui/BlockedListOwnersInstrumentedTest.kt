package one.meshcore.shared.contact.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.contact.BlockedChannelSenderService
import one.meshcore.shared.contact.BlockedChannelSendersViewModel
import one.meshcore.shared.contact.BlockedContactsViewModel
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.model.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BlockedListOwnersInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementProvidersKeepOwnersAndServeLaterLoads() {
        val contactServiceReads = AtomicInteger()
        val contactRadioReads = AtomicInteger()
        val senderServiceReads = AtomicInteger()
        val senderRadioReads = AtomicInteger()
        val compositions = AtomicInteger()
        val contactService = mutableStateOf<() -> ContactService?>({ null })
        val contactRadio = mutableStateOf<() -> Uuid?>({ null })
        val senderService = mutableStateOf<() -> BlockedChannelSenderService?>({ null })
        val senderRadio = mutableStateOf<() -> Uuid?>({ null })
        var contactsOwner: BlockedContactsViewModel? = null
        var sendersOwner: BlockedChannelSendersViewModel? = null

        compose.setContent {
            val scope = rememberCoroutineScope()
            val contacts = rememberBlockedContactsViewModel(
                scope = scope,
                contactService = contactService.value,
                radioId = contactRadio.value,
                errorText = { "mapped" },
            )
            val senders = rememberBlockedChannelSendersViewModel(
                service = senderService.value,
                radioId = senderRadio.value,
            )
            SideEffect {
                contactsOwner = contacts
                sendersOwner = senders
                compositions.incrementAndGet()
            }
        }
        compose.waitUntil { contactsOwner != null && sendersOwner != null }
        val retainedContacts = requireNotNull(contactsOwner)
        val retainedSenders = requireNotNull(sendersOwner)
        val initialCompositions = compositions.get()

        compose.runOnIdle {
            contactService.value = {
                contactServiceReads.incrementAndGet()
                null
            }
            contactRadio.value = {
                contactRadioReads.incrementAndGet()
                null
            }
            senderService.value = {
                senderServiceReads.incrementAndGet()
                null
            }
            senderRadio.value = {
                senderRadioReads.incrementAndGet()
                null
            }
        }
        compose.waitUntil { compositions.get() > initialCompositions }

        runBlocking {
            retainedContacts.load()
            retainedSenders.load()
        }

        compose.runOnIdle {
            assertSame(retainedContacts, contactsOwner)
            assertSame(retainedSenders, sendersOwner)
            assertEquals(1, contactServiceReads.get())
            assertEquals(1, contactRadioReads.get())
            assertEquals(1, senderServiceReads.get())
            assertEquals(1, senderRadioReads.get())
        }
    }
}
