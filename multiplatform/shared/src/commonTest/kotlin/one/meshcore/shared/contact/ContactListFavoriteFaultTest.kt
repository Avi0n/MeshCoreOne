package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Favorite-toggle faults that must keep the thrown instance. A mapped exception still becomes a list
 * error in the existing action tests; cancellation and a store error clear the spinner and propagate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListFavoriteFaultTest {
    @Test
    fun aCancelledFavoriteRethrowsTheSameExceptionAndClearsTheSpinner() = runTest {
        val cancelled = CancellationException("superseded")
        val session = FakeContactSession().apply { changeFlagsThrowable = cancelled }
        val contact = favoriteChat("Alice")
        val vm = favoriteViewModel(testContactService(session = session, store = savedContactStore(contact)))
        vm.load()

        val thrown = assertFailsWith<CancellationException> {
            vm.toggleFavorite(vm.state.value.contacts.first())
        }

        assertSame(cancelled, thrown)
        assertNull(vm.state.value.togglingFavoriteId)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.contacts.first().isFavorite)
    }

    @Test
    fun aFavoriteSaveErrorRethrowsTheSameErrorAndClearsTheSpinner() = runTest {
        val failure = FavoriteSaveProbeError()
        val contact = favoriteChat("Alice")
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
                backing.updateContactFields(id) { current ->
                    val dto = update(current)
                    if (dto.isFavorite) throw failure
                    dto
                }
        }
        val vm = favoriteViewModel(testContactService(session = FakeContactSession(), store = failing))
        vm.load()

        val thrown = assertFailsWith<FavoriteSaveProbeError> {
            vm.toggleFavorite(vm.state.value.contacts.first())
        }

        assertSame(failure, thrown)
        assertNull(vm.state.value.togglingFavoriteId)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.contacts.first().isFavorite)
    }
}

private class FavoriteSaveProbeError : Error("probe")

private val favoriteRadio = ContactTestIds.RADIO

private fun favoriteChat(name: String) = contactDto(
    id = ContactTestIds.fresh(),
    radioID = favoriteRadio,
    keyByte = 0x01,
    name = name,
    typeRawValue = ContactType.CHAT.raw,
    isFavorite = false,
)

private fun TestScope.favoriteViewModel(service: ContactService): ContactListViewModel = ContactListViewModel(
    scope = backgroundScope,
    dependencies = ContactListViewModel.Dependencies(
        contactService = { service },
        radioId = { favoriteRadio },
        isConnected = { true },
        isReady = { true },
        errorText = { "Localized error" },
    ),
)
