package one.meshcore.shared.tools.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.setPointA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LineOfSightModelOwnerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementConnectionReloadsRepeatersWithoutDroppingEdits() {
        val firstService = service(FIRST_RADIO, FIRST_CONTACT_ID, "First repeater")
        val replacementService = service(SECOND_RADIO, SECOND_CONTACT_ID, "Replacement repeater")
        val service = mutableStateOf(firstService)
        val radio = mutableStateOf(FIRST_RADIO)
        var current: LineOfSightViewModel? = null

        compose.setContent {
            val viewModel = rememberLineOfSightViewModel(
                elevationService = null,
                contactService = { service.value },
                radioId = { radio.value },
                deviceFrequencyKHz = null,
            )
            SideEffect { current = viewModel }
        }
        compose.waitUntil { current?.repeatersWithLocation?.singleOrNull()?.name == "First repeater" }
        val retained = requireNotNull(current)

        compose.runOnIdle {
            retained.setPointA(EDITED_POINT)
            service.value = replacementService
            radio.value = SECOND_RADIO
        }
        compose.waitUntil { current?.repeatersWithLocation?.singleOrNull()?.name == "Replacement repeater" }

        compose.runOnIdle {
            assertSame(retained, current)
            assertEquals(EDITED_POINT, current?.pointA?.coordinate)
        }
    }

    private fun service(radioID: Uuid, contactID: Uuid, name: String): ContactService {
        val store = InMemoryContactStore(newId = { contactID })
        runBlocking { store.saveContact(radioID, repeater(name)) }
        return ContactService(NoOpContactSession(), store, now = { 0L })
    }

    private fun repeater(name: String) = ContactFrame(
        publicKey = ByteArray(PUBLIC_KEY_LENGTH) { name.length.toByte() },
        type = ContactType.REPEATER,
        flags = 0,
        outPathLength = FLOOD_PATH,
        outPath = ByteArray(0),
        name = name,
        lastAdvertTimestamp = 0,
        latitude = 37.0,
        longitude = -122.0,
        lastModified = 0,
    )

    private companion object {
        const val PUBLIC_KEY_LENGTH = 32
        const val FLOOD_PATH = 0xFF
        val FIRST_RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val SECOND_RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000002")
        val FIRST_CONTACT_ID: Uuid = Uuid.normalized("B0000000-0000-0000-0000-000000000001")
        val SECOND_CONTACT_ID: Uuid = Uuid.normalized("B0000000-0000-0000-0000-000000000002")
        val EDITED_POINT = LatLng(38.0, -121.0)
    }
}
