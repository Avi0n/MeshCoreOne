package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A persist fault on the share-contact picker must clear the spinner and surface the error.
 * iOS `ShareContactPickerSheet` uses `defer { isLoading = false }` and `.errorAlert`.
 */
class ShareContactPickerViewModelTest {
    @Test
    fun aLoadFaultUsesTheLatestLocalizedCopy() = runTest {
        var message = "first language"
        val viewModel = ShareContactPickerViewModel(loadFailedMessage = { message }) {
            error("db locked")
        }

        viewModel.load()
        assertEquals("first language", viewModel.state.value.errorMessage)

        message = "second language"
        viewModel.load()
        assertEquals("second language", viewModel.state.value.errorMessage)
    }

    @Test
    fun loadShowsShareableContacts() = runTest {
        val alice = sampleContact(name = "Alice")
        val blank = sampleContact(name = "  ", keyByte = 0x02)
        val viewModel = ShareContactPickerViewModel(loadFailedMessage = { LOCALIZED_LOAD_ERROR }) {
            listOf(alice, blank)
        }

        viewModel.load()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(listOf("Alice"), state.contacts.map { it.name })
    }

    @Test
    fun replacementLoaderRefreshesRetainedPickerContacts() = runTest {
        val first = sampleContact(name = "Alice", keyByte = 0x11)
        val replacement = sampleContact(name = "Bob", keyByte = 0x22)
        var contacts = listOf(first)
        val viewModel = ShareContactPickerViewModel(loadFailedMessage = { LOCALIZED_LOAD_ERROR }) {
            contacts
        }
        viewModel.load()

        contacts = listOf(replacement)
        viewModel.load()

        assertEquals(listOf(replacement.id), viewModel.state.value.contacts.map { it.id })
        assertFalse(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun loadPersistFaultClearsSpinnerAndSetsError() = runTest {
        val failure = IllegalStateException("radio down")
        val logged = mutableListOf<Throwable>()
        val viewModel = ShareContactPickerViewModel(
            loadFailedMessage = { "load fail" },
            onLoadError = { logged.add(it) },
        ) {
            throw failure
        }

        viewModel.load()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertTrue(state.contacts.isEmpty())
        assertEquals("load fail", state.errorMessage)
        assertSame(failure, logged.single())
    }

    @Test
    fun dismissErrorClearsTheMessage() = runTest {
        val viewModel = ShareContactPickerViewModel(loadFailedMessage = { LOCALIZED_LOAD_ERROR }) {
            throw IllegalStateException("db locked")
        }
        viewModel.load()

        viewModel.dismissError()

        assertNull(viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun dismissingAShareContactErrorDoesNotRadio() = runTest {
        var loads = 0
        val viewModel = ShareContactPickerViewModel(loadFailedMessage = { LOCALIZED_LOAD_ERROR }) {
            loads += 1
            error("contact load")
        }
        assertNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.contacts.isEmpty())
        assertEquals(0, loads)

        viewModel.dismissError()

        assertEquals(0, loads)
        assertNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.contacts.isEmpty())
    }

    @Test
    fun aCancelledLoadPropagatesWithoutAnError() = runTest {
        val cancelled = CancellationException("superseded")
        val logged = mutableListOf<Throwable>()
        val viewModel = ShareContactPickerViewModel(
            loadFailedMessage = { LOCALIZED_LOAD_ERROR },
            onLoadError = { logged.add(it) },
        ) {
            throw cancelled
        }

        val error = assertFailsWith<CancellationException> { viewModel.load() }

        assertSame(cancelled, error)
        assertTrue(logged.isEmpty())
        assertTrue(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.contacts.isEmpty())
    }

    @Test
    fun aLoadErrorPropagatesWithoutAnError() = runTest {
        val probe = ShareLoadProbeError()
        val logged = mutableListOf<Throwable>()
        val viewModel = ShareContactPickerViewModel(
            loadFailedMessage = { LOCALIZED_LOAD_ERROR },
            onLoadError = { logged.add(it) },
        ) {
            throw probe
        }

        val thrown = assertFailsWith<ShareLoadProbeError> { viewModel.load() }

        assertSame(probe, thrown)
        assertTrue(logged.isEmpty())
        assertTrue(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.contacts.isEmpty())
    }

    private fun sampleContact(name: String, keyByte: Int = 0x01): ContactDto = ContactDto(
        id = Uuid.normalized("00000000-0000-4000-8000-0000000000${byteArrayOf(keyByte.toByte()).toLowercaseHex()}"),
        radioID = Uuid.normalized("00000000-0000-4000-8000-000000000099"),
        publicKey = ByteArray(32) { keyByte.toByte() },
        name = name,
        typeRawValue = ContactType.CHAT.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )
}

private const val LOCALIZED_LOAD_ERROR = "Localized load error"

private class ShareLoadProbeError : Error("probe")
