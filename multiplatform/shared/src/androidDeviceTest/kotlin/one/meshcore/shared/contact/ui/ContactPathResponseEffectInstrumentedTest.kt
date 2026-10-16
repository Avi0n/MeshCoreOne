package one.meshcore.shared.contact.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.contact.ContactServiceEvent
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.meshcore.PathInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactPathResponseEffectInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sessionReplacementStopsTheRetiredEventFlow() {
        val first = contactService()
        val second = contactService()
        var service by mutableStateOf<ContactService?>(first)
        val accepted = mutableListOf<Int>()
        val failures = mutableListOf<String>()
        compose.setContent {
            ContactPathResponseEffect(
                CONTACT_ID,
                service,
                onPathAccepted = { accepted += it.outPathLength },
                onWriteFailed = { failures += it.message.orEmpty() },
            )
        }

        compose.runOnIdle { yieldAccepted(first, FIRST_PATH_LENGTH) }
        compose.waitUntil { accepted.contains(FIRST_PATH_LENGTH) }

        compose.runOnIdle { service = second }
        compose.waitForIdle()
        compose.runOnIdle {
            yieldAccepted(first, RETIRED_PATH_LENGTH)
            yieldFailed(second)
            yieldAccepted(second, SECOND_PATH_LENGTH)
        }
        compose.waitUntil { accepted.contains(SECOND_PATH_LENGTH) }
        compose.runOnIdle {
            assertFalse(accepted.contains(RETIRED_PATH_LENGTH))
            assertEquals(listOf(FIRST_PATH_LENGTH, SECOND_PATH_LENGTH), accepted)
            assertEquals(listOf(ContactServiceError.ContactNotFound().message), failures)
        }
    }

    private fun contactService() = ContactService(
        session = NoOpContactSession(),
        store = InMemoryContactStore(),
        now = { 0L },
    )

    private fun yieldAccepted(service: ContactService, outPathLength: Int) {
        runBlocking {
            service.eventBroadcaster.yield(ContactServiceEvent.PathDiscoveryAccepted(path(outPathLength)))
        }
    }

    private fun yieldFailed(service: ContactService) {
        runBlocking {
            service.eventBroadcaster.yield(
                ContactServiceEvent.PathDiscoveryWriteFailed(
                    path(FAILED_PATH_LENGTH),
                    ContactServiceError.ContactNotFound(),
                ),
            )
        }
    }

    private fun path(outPathLength: Int) = PathInfo(
        publicKeyPrefix = byteArrayOf(1),
        outPathLength = outPathLength,
        outPath = ByteArray(0),
        inPathLength = 0,
        inPath = ByteArray(0),
    )

    private companion object {
        const val CONTACT_ID = "contact-id"
        const val FIRST_PATH_LENGTH = 1
        const val RETIRED_PATH_LENGTH = 2
        const val SECOND_PATH_LENGTH = 3
        const val FAILED_PATH_LENGTH = 4
    }
}
