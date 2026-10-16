package one.meshcore.shared.pathedit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.savedContactStore
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.meshcore.PathInfo
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Insert, save, and direct-routing edits on a contact path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PathManagementViewModelTest {
    // MARK: insert / isPathFull

    @Test
    fun insertAddsAtEndOfEditablePath() = runTest {
        val vm = viewModel(service = null)
        vm.insert(PathEditFixtures.key(0x01), "A")
        assertEquals(1, vm.state.value.editablePath.size)
        assertContentEquals(PathEditFixtures.key(0x01), vm.state.value.editablePath[0].publicKey)
    }

    @Test
    fun insertPreservesInsertionIntent() = runTest {
        val vm = viewModel(service = null)
        vm.setInsertionIntent(AddHopIntent.APPEND)
        vm.insert(PathEditFixtures.key(0x01), "A")
        assertEquals(AddHopIntent.APPEND, vm.state.value.insertionIntent)
    }

    @Test
    fun insertIsNoOpOncePathFull() = runTest {
        val vm = viewModel(service = null, hashSize = 3) // maxHopCount = 21
        repeat(vm.maxHopCount()) { i -> vm.insert(PathEditFixtures.key(i, i, i), "H$i") }
        assertTrue(vm.isPathFull())
        vm.insert(PathEditFixtures.key(0xFF, 0xFF, 0xFF), "Overflow")
        assertEquals(vm.maxHopCount(), vm.state.value.editablePath.size)
    }

    @Test
    fun isPathFullFalseWithNoHopsAtHashSizeOne() = runTest {
        val vm = viewModel(service = null, hashSize = 1)
        assertFalse(vm.isPathFull())
        assertEquals(63, vm.maxHopCount())
    }

    @Test
    fun maxHopCountByHashSize() = runTest {
        assertEquals(32, viewModel(service = null, hashSize = 2).maxHopCount())
        assertEquals(21, viewModel(service = null, hashSize = 3).maxHopCount())
    }

    @Test
    fun insertRecordsRecent() = runTest {
        val vm = viewModel(service = null)
        vm.insert(PathEditFixtures.key(0x0A), "A")
        runCurrent()
        assertEquals(1, vm.state.value.recentPublicKeys.size)
        assertContentEquals(PathEditFixtures.key(0x0A), vm.state.value.recentPublicKeys[0])
    }

    @Test
    fun removingAPathHopDoesNotRadio() = runTest {
        val session = FakeContactSession()
        val vm = viewModel(testContactService(session = session, store = savedContactStore()))
        vm.insert(PathEditFixtures.key(0x01), "A")
        runCurrent()
        assertEquals(1, vm.state.value.editablePath.size)
        val discoveries = session.pathDiscoveryDestinations.size
        val resets = session.resetPathPublicKeys.size
        val adds = session.addedContacts.size
        val removes = session.removedPublicKeys.size
        val shares = session.sharedPublicKeys.size
        val flags = session.changeFlagsCalls.size
        val lookups = session.getContactKeys.size

        vm.removeRepeater(0)

        assertEquals(discoveries, session.pathDiscoveryDestinations.size)
        assertEquals(resets, session.resetPathPublicKeys.size)
        assertEquals(adds, session.addedContacts.size)
        assertEquals(removes, session.removedPublicKeys.size)
        assertEquals(shares, session.sharedPublicKeys.size)
        assertEquals(flags, session.changeFlagsCalls.size)
        assertEquals(lookups, session.getContactKeys.size)
        assertTrue(vm.state.value.editablePath.isEmpty())
    }

    @Test
    fun removingAHopFromAnEmptyPathDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        val vm = PathManagementViewModel(
            scope = backgroundScope,
            recents = RecentHopsStore(InMemoryPreferencesStore()),
            dependencies = PathManagementViewModel.Dependencies(
                contactService = {
                    serviceReads += 1
                    null
                },
                radioId = {
                    radioReads += 1
                    null
                },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        assertTrue(vm.state.value.editablePath.isEmpty())
        assertNull(vm.state.value.insertionIntent)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSettingPath)
        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)

        vm.removeRepeater(0)

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertTrue(vm.state.value.editablePath.isEmpty())
        assertNull(vm.state.value.insertionIntent)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSettingPath)
    }

    @Test
    fun movingAHopOnAnEmptyPathDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        val vm = PathManagementViewModel(
            scope = backgroundScope,
            recents = RecentHopsStore(InMemoryPreferencesStore()),
            dependencies = PathManagementViewModel.Dependencies(
                contactService = {
                    serviceReads += 1
                    null
                },
                radioId = {
                    radioReads += 1
                    null
                },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        assertTrue(vm.state.value.editablePath.isEmpty())
        assertNull(vm.state.value.insertionIntent)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSettingPath)
        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)

        vm.moveRepeater(0, 1)

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertTrue(vm.state.value.editablePath.isEmpty())
        assertNull(vm.state.value.insertionIntent)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSettingPath)
    }

    @Test
    fun movingAPathHopDoesNotRadio() = runTest {
        val session = FakeContactSession()
        val vm = viewModel(testContactService(session = session, store = savedContactStore()))
        vm.insert(PathEditFixtures.key(0x01), "A")
        vm.insert(PathEditFixtures.key(0x02), "B")
        runCurrent()
        assertEquals("A", vm.state.value.editablePath[0].resolvedName)
        assertEquals("B", vm.state.value.editablePath[1].resolvedName)
        val discoveries = session.pathDiscoveryDestinations.size
        val resets = session.resetPathPublicKeys.size
        val adds = session.addedContacts.size
        val removes = session.removedPublicKeys.size
        val shares = session.sharedPublicKeys.size
        val flags = session.changeFlagsCalls.size
        val lookups = session.getContactKeys.size

        vm.moveRepeater(0, 2)

        assertEquals(discoveries, session.pathDiscoveryDestinations.size)
        assertEquals(resets, session.resetPathPublicKeys.size)
        assertEquals(adds, session.addedContacts.size)
        assertEquals(removes, session.removedPublicKeys.size)
        assertEquals(shares, session.sharedPublicKeys.size)
        assertEquals(flags, session.changeFlagsCalls.size)
        assertEquals(lookups, session.getContactKeys.size)
        assertEquals("B", vm.state.value.editablePath[0].resolvedName)
        assertEquals("A", vm.state.value.editablePath[1].resolvedName)
    }

    @Test
    fun openingTheAddHopPickerDoesNotRadio() = runTest {
        val session = FakeContactSession()
        val vm = viewModel(testContactService(session = session, store = savedContactStore()))
        val discoveries = session.pathDiscoveryDestinations.size
        val resets = session.resetPathPublicKeys.size
        val adds = session.addedContacts.size
        val removes = session.removedPublicKeys.size
        val shares = session.sharedPublicKeys.size
        val flags = session.changeFlagsCalls.size
        val lookups = session.getContactKeys.size

        vm.setInsertionIntent(AddHopIntent.APPEND)

        assertEquals(discoveries, session.pathDiscoveryDestinations.size)
        assertEquals(resets, session.resetPathPublicKeys.size)
        assertEquals(adds, session.addedContacts.size)
        assertEquals(removes, session.removedPublicKeys.size)
        assertEquals(shares, session.sharedPublicKeys.size)
        assertEquals(flags, session.changeFlagsCalls.size)
        assertEquals(lookups, session.getContactKeys.size)
        assertEquals(AddHopIntent.APPEND, vm.state.value.insertionIntent)
        assertTrue(vm.state.value.editablePath.isEmpty())
    }

    @Test
    fun closingTheAddHopPickerDoesNotRadio() = runTest {
        val session = FakeContactSession()
        val vm = viewModel(testContactService(session = session, store = savedContactStore()))
        vm.setInsertionIntent(AddHopIntent.APPEND)
        val discoveries = session.pathDiscoveryDestinations.size
        val resets = session.resetPathPublicKeys.size
        val adds = session.addedContacts.size
        val removes = session.removedPublicKeys.size
        val shares = session.sharedPublicKeys.size
        val flags = session.changeFlagsCalls.size
        val lookups = session.getContactKeys.size

        vm.setInsertionIntent(null)

        assertEquals(discoveries, session.pathDiscoveryDestinations.size)
        assertEquals(resets, session.resetPathPublicKeys.size)
        assertEquals(adds, session.addedContacts.size)
        assertEquals(removes, session.removedPublicKeys.size)
        assertEquals(shares, session.sharedPublicKeys.size)
        assertEquals(flags, session.changeFlagsCalls.size)
        assertEquals(lookups, session.getContactKeys.size)
        assertNull(vm.state.value.insertionIntent)
        assertTrue(vm.state.value.editablePath.isEmpty())
    }

    @Test
    fun clearingAnAlreadyEmptyAddHopIntentDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        val vm = PathManagementViewModel(
            scope = backgroundScope,
            recents = RecentHopsStore(InMemoryPreferencesStore()),
            dependencies = PathManagementViewModel.Dependencies(
                contactService = {
                    serviceReads += 1
                    null
                },
                radioId = {
                    radioReads += 1
                    null
                },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        assertTrue(vm.state.value.editablePath.isEmpty())
        assertNull(vm.state.value.insertionIntent)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSettingPath)
        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)

        vm.setInsertionIntent(null)

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertTrue(vm.state.value.editablePath.isEmpty())
        assertNull(vm.state.value.insertionIntent)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSettingPath)
    }

    // MARK: initializeEditablePath

    @Test
    fun initializeClearsStaleInsertionIntent() = runTest {
        val service = testContactService(store = savedContactStore())
        val vm = viewModel(service)
        vm.setInsertionIntent(AddHopIntent.APPEND)
        vm.initializeEditablePath(target()) // flood contact, no path
        assertNull(vm.state.value.insertionIntent)
        assertTrue(vm.state.value.editablePath.isEmpty())
    }

    @Test
    fun initializeResolvesPublicKeyForUniqueContact() = runTest {
        val fullKey = PathEditFixtures.key(0xA1, 0xB2, 0xC3)
        val repeater = PathEditFixtures.contact(
            name = "Hop1",
            publicKey = fullKey,
            type = ContactType.REPEATER,
            radioID = radio,
            id = PathEditFixtures.uuid(1),
        )
        val contact = target(outPathLength = 0x01, outPath = byteArrayOf(0xA1.toByte()))
        val service = testContactService(store = savedContactStore(repeater, contact))
        val vm = viewModel(service)
        vm.loadContacts(radio)
        vm.initializeEditablePath(contact)
        assertEquals(1, vm.state.value.editablePath.size)
        assertContentEquals(fullKey, vm.state.value.editablePath[0].publicKey)
        assertEquals("Hop1", vm.state.value.editablePath[0].resolvedName)
    }

    @Test
    fun initializeNarrowsWiderStoredHopsWhenResolutionFails() = runTest {
        // Device hashSize 1; stored mode 1 (2 bytes/hop), unresolvable.
        val contact = target(outPathLength = 0x41, outPath = byteArrayOf(0xA1.toByte(), 0xB2.toByte()))
        val service = testContactService(store = savedContactStore(contact))
        val vm = viewModel(service, hashSize = 1)
        vm.loadContacts(radio)
        vm.initializeEditablePath(contact)
        assertEquals(1, vm.state.value.editablePath.size)
        assertContentEquals(byteArrayOf(0xA1.toByte()), vm.state.value.editablePath[0].hashBytes)
        assertNull(vm.state.value.editablePath[0].publicKey)
    }

    @Test
    fun initializeReslicesResolvedHopToDeviceHashSize() = runTest {
        val fullKey = PathEditFixtures.key(0xA1, 0xB2, 0xC3)
        val repeater = PathEditFixtures.contact(
            name = "Hop1",
            publicKey = fullKey,
            type = ContactType.REPEATER,
            radioID = radio,
            id = PathEditFixtures.uuid(1),
        )
        val contact = target(outPathLength = 0x41, outPath = byteArrayOf(0xA1.toByte(), 0xB2.toByte()))
        val service = testContactService(store = savedContactStore(repeater, contact))
        val vm = viewModel(service, hashSize = 1)
        vm.loadContacts(radio)
        vm.initializeEditablePath(contact)
        assertEquals(1, vm.state.value.editablePath.size)
        assertContentEquals(fullKey, vm.state.value.editablePath[0].publicKey)
        assertContentEquals(byteArrayOf(0xA1.toByte()), vm.state.value.editablePath[0].hashBytes)
    }

    @Test
    fun loadContactsClearsTheListsWhenTheReadFails() = runTest {
        var fail = false
        val backing = savedContactStore(
            PathEditFixtures.contact(name = "Hop", radioID = radio, id = PathEditFixtures.uuid(3)),
        )
        val throwing = object : ContactStore by backing {
            override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> {
                if (fail) error("db locked")
                return backing.fetchContacts(radioID)
            }
        }
        val vm = viewModel(testContactService(store = throwing))
        vm.loadContacts(radio)
        assertEquals(listOf("Hop"), vm.state.value.allContacts.map { it.name })

        fail = true
        vm.loadContacts(radio, forceReload = true)

        assertTrue(vm.state.value.allContacts.isEmpty())
        assertTrue(vm.state.value.availableRepeaters.isEmpty())
        assertTrue(vm.state.value.availableRooms.isEmpty())
        assertTrue(vm.state.value.discoveredNodes.isEmpty())
    }

    @Test
    fun loadContactsPropagatesCancellationAndKeepsTheLoadedLists() = runTest {
        val cancelled = CancellationException("superseded")
        var cancel = false
        val backing = savedContactStore(
            PathEditFixtures.contact(name = "Hop", radioID = radio, id = PathEditFixtures.uuid(3)),
        )
        val throwing = object : ContactStore by backing {
            override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> {
                if (cancel) throw cancelled
                return backing.fetchContacts(radioID)
            }
        }
        val vm = viewModel(testContactService(store = throwing))
        vm.loadContacts(radio)
        assertEquals(listOf("Hop"), vm.state.value.allContacts.map { it.name })

        cancel = true
        assertSame(cancelled, assertFailsWith<CancellationException> { vm.loadContacts(radio, forceReload = true) })
        assertEquals(listOf("Hop"), vm.state.value.allContacts.map { it.name })
    }

    @Test
    fun loadContactsPropagatesAnErrorAndKeepsTheLoadedLists() = runTest {
        val probe = PathEditProbeError()
        var fail = false
        val backing = savedContactStore(
            PathEditFixtures.contact(name = "Hop", radioID = radio, id = PathEditFixtures.uuid(3)),
        )
        val throwing = object : ContactStore by backing {
            override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> {
                if (fail) throw probe
                return backing.fetchContacts(radioID)
            }
        }
        val vm = viewModel(testContactService(store = throwing))
        vm.loadContacts(radio)
        assertEquals(listOf("Hop"), vm.state.value.allContacts.map { it.name })

        fail = true
        assertSame(probe, assertFailsWith<PathEditProbeError> { vm.loadContacts(radio, forceReload = true) })
        assertEquals(listOf("Hop"), vm.state.value.allContacts.map { it.name })
    }

    // MARK: save / reset / direct

    @Test
    fun saveEditedPathEncodesAndPersistsWholePath() = runTest {
        val contact = target()
        val store = savedContactStore(contact)
        var refreshed = false
        val vm = viewModel(
            testContactService(store = store),
            hashSize = 1,
            options = PathOptions(onRefresh = { refreshed = true }),
        )
        vm.insert(PathEditFixtures.key(0xA1, 0xB2), "H1")
        val ok = vm.saveEditedPath(contact)
        assertTrue(ok)
        assertTrue(refreshed)
        val saved = store.fetchContact(radio, contact.publicKey)
        assertEquals(0x01, saved?.outPathLength) // mode 0, 1 hop
        assertContentEquals(byteArrayOf(0xA1.toByte()), saved?.outPath)
    }

    @Test
    fun saveEditedPathEncodesAtTheDeviceHashSize() = runTest {
        // A radio on path-hash mode 1 must receive 2-byte hops; a 1-byte encode is mis-parsed against the
        // declared length. Proves the injected hash size reaches the encoder, not just the hop cap.
        val contact = target()
        val store = savedContactStore(contact)
        val vm = viewModel(testContactService(store = store), hashSize = 2)
        vm.insert(PathEditFixtures.key(0xA1, 0xB2, 0xC3), "H1")
        assertTrue(vm.saveEditedPath(contact))
        val saved = store.fetchContact(radio, contact.publicKey)
        assertEquals(0x41, saved?.outPathLength) // mode 1, 1 hop
        assertContentEquals(byteArrayOf(0xA1.toByte(), 0xB2.toByte()), saved?.outPath)
    }

    @Test
    fun saveEditedPathRejectionSetsError() = runTest {
        // Stored 1-byte hop, unresolvable, device hashSize 2 -> can't widen.
        val contact = target(outPathLength = 0x01, outPath = byteArrayOf(0xA1.toByte()))
        val vm = viewModel(testContactService(store = savedContactStore(contact)), hashSize = 2)
        vm.loadContacts(radio)
        vm.initializeEditablePath(contact)
        val ok = vm.saveEditedPath(contact)
        assertFalse(ok)
        assertIs<PathEditError.HopResizeRequired>(vm.state.value.errorMessage)
    }

    @Test
    fun saveEditedPathPersistFaultSetsErrorAndClearsBusy() = runTest {
        val contact = target()
        val backing = savedContactStore(contact)
        val store = object : ContactStore by backing {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
                error("room down")
            }
        }
        val vm = viewModel(testContactService(store = store), hashSize = 1)
        vm.insert(PathEditFixtures.key(0xA1, 0xB2), "H1")
        val ok = vm.saveEditedPath(contact)
        assertFalse(ok)
        val error = vm.state.value.errorMessage
        assertIs<PathEditError.SaveFailed>(error)
        assertEquals(LOCALIZED_ERROR, error.message)
        assertFalse(vm.state.value.isSettingPath)
    }

    @Test
    fun saveEditedPathPersistFaultShowsMappedCopy() = runTest {
        val contact = target()
        val backing = savedContactStore(contact)
        val store = object : ContactStore by backing {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
                error("room down")
            }
        }
        val vm = viewModel(
            testContactService(store = store),
            hashSize = 1,
            options = PathOptions(errorText = { "path fail" }),
        )
        vm.insert(PathEditFixtures.key(0xA1, 0xB2), "H1")
        val ok = vm.saveEditedPath(contact)
        assertFalse(ok)
        val error = vm.state.value.errorMessage
        assertIs<PathEditError.SaveFailed>(error)
        assertEquals("path fail", error.message)
    }

    @Test
    fun saveEditedPathPersistFaultUsesTheLatestLocalizedCopy() = runTest {
        val contact = target()
        val backing = savedContactStore(contact)
        val store = object : ContactStore by backing {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
                error("room down")
            }
        }
        var message = "first language"
        val vm = viewModel(
            testContactService(store = store),
            hashSize = 1,
            options = PathOptions(errorText = { message }),
        )
        vm.insert(PathEditFixtures.key(0xA1, 0xB2), "H1")

        message = "second language"
        assertFalse(vm.saveEditedPath(contact))

        val error = vm.state.value.errorMessage
        assertIs<PathEditError.SaveFailed>(error)
        assertEquals("second language", error.message)
    }

    @Test
    fun saveEditedPathCancellationClearsBusyAndLeavesNoError() = runTest {
        val contact = target()
        val cancelled = CancellationException("superseded")
        val backing = savedContactStore(contact)
        val store = object : ContactStore by backing {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid = throw cancelled
        }
        val vm = viewModel(testContactService(store = store), hashSize = 1)
        vm.insert(PathEditFixtures.key(0xA1, 0xB2), "H1")

        assertSame(cancelled, assertFailsWith<CancellationException> { vm.saveEditedPath(contact) })
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSettingPath)
    }

    @Test
    fun saveEditedPathPropagatesAnErrorAndLeavesNoSaveFailure() = runTest {
        val contact = target()
        val probe = PathEditProbeError()
        val backing = savedContactStore(contact)
        val store = object : ContactStore by backing {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid = throw probe
        }
        val vm = viewModel(testContactService(store = store), hashSize = 1)
        vm.insert(PathEditFixtures.key(0xA1, 0xB2), "H1")

        assertSame(probe, assertFailsWith<PathEditProbeError> { vm.saveEditedPath(contact) })
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSettingPath)
    }

    @Test
    fun resetPathFaultShowsMappedCopyNotDeviceError() = runTest {
        val contact = target()
        val session = FakeContactSession().apply { resetPathError = 1 }
        val vm = viewModel(
            testContactService(session = session, store = savedContactStore(contact)),
            options = PathOptions(errorText = { "reset fail" }),
        )
        val ok = vm.resetPath(contact)
        assertFalse(ok)
        val error = vm.state.value.errorMessage
        assertIs<PathEditError.ResetFailed>(error)
        assertEquals("reset fail", error.message)
    }

    @Test
    fun resetPathCancellationClearsBusyAndLeavesNoError() = runTest {
        val contact = target()
        val cancelled = CancellationException("superseded")
        val session = FakeContactSession().apply { resetPathThrowable = cancelled }
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))

        assertSame(cancelled, assertFailsWith<CancellationException> { vm.resetPath(contact) })
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSettingPath)
    }

    @Test
    fun setDirectRoutingFaultShowsMappedCopy() = runTest {
        val contact = target()
        val backing = savedContactStore(contact)
        val store = object : ContactStore by backing {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
                error("room down")
            }
        }
        val vm = viewModel(testContactService(store = store), options = PathOptions(errorText = { "set fail" }))
        val ok = vm.setDirectRouting(contact)
        assertFalse(ok)
        val error = vm.state.value.errorMessage
        assertIs<PathEditError.SetFailed>(error)
        assertEquals("set fail", error.message)
    }

    @Test
    fun setDirectRoutingCancellationClearsBusyAndLeavesNoError() = runTest {
        val contact = target()
        val cancelled = CancellationException("superseded")
        val backing = savedContactStore(contact)
        val store = object : ContactStore by backing {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid = throw cancelled
        }
        val vm = viewModel(testContactService(store = store))

        assertSame(cancelled, assertFailsWith<CancellationException> { vm.setDirectRouting(contact) })
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSettingPath)
    }

    @Test
    fun setDirectRoutingWritesZeroLength() = runTest {
        val contact = target()
        val store = savedContactStore(contact)
        var refreshed = false
        val vm = viewModel(testContactService(store = store), options = PathOptions(onRefresh = { refreshed = true }))
        val ok = vm.setDirectRouting(contact)
        assertTrue(ok)
        assertTrue(refreshed)
        val saved = store.fetchContact(radio, contact.publicKey)
        assertEquals(0x00, saved?.outPathLength)
        assertEquals(0, saved?.outPath?.size)
    }
}

/**
 * Reset, add-by-code, and path discovery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PathManagementResetTest {
    @Test
    fun resetPathCallsServiceReset() = runTest {
        val contact = target()
        val session = FakeContactSession()
        var refreshed = false
        val vm = viewModel(
            testContactService(session = session, store = savedContactStore(contact)),
            options = PathOptions(onRefresh = { refreshed = true }),
        )
        val ok = vm.resetPath(contact)
        assertTrue(ok)
        assertTrue(refreshed)
        assertEquals(1, session.resetPathPublicKeys.size)
        assertContentEquals(contact.publicKey, session.resetPathPublicKeys[0])
    }

    @Test
    fun resetPathWhileDisconnectedDoesNotCallTheService() = runTest {
        val contact = target()
        val session = FakeContactSession()
        val vm = viewModel(
            testContactService(session = session, store = savedContactStore(contact)),
            options = PathOptions(isRadioConnected = false),
        )
        val ok = vm.resetPath(contact)
        assertFalse(ok)
        assertTrue(session.resetPathPublicKeys.isEmpty())
    }

    @Test
    fun savingAnEditedPathWhileDisconnectedDoesNotRadio() = runTest {
        val contact = target()
        val session = FakeContactSession()
        var refreshCount = 0
        val vm = viewModel(
            testContactService(session = session, store = savedContactStore(contact)),
            options = PathOptions(onRefresh = { refreshCount += 1 }, isRadioConnected = false),
        )
        vm.insert(PathEditFixtures.key(0x01), "A")
        runCurrent()

        val ok = vm.saveEditedPath(contact)

        assertFalse(ok)
        assertEquals(0, refreshCount)
        assertTrue(session.pathDiscoveryDestinations.isEmpty())
        assertTrue(session.resetPathPublicKeys.isEmpty())
        assertTrue(session.addedContacts.isEmpty())
        assertTrue(session.removedPublicKeys.isEmpty())
        assertTrue(session.sharedPublicKeys.isEmpty())
        assertTrue(session.changeFlagsCalls.isEmpty())
        assertTrue(session.getContactKeys.isEmpty())
        assertEquals("A", vm.state.value.editablePath.single().resolvedName)
        assertFalse(vm.state.value.isSettingPath)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun clearingAPathEditErrorDoesNotRadio() = runTest {
        val session = FakeContactSession()
        val vm = viewModel(testContactService(session = session, store = savedContactStore()))
        val discoveries = session.pathDiscoveryDestinations.size
        val resets = session.resetPathPublicKeys.size
        val adds = session.addedContacts.size
        val removes = session.removedPublicKeys.size
        val shares = session.sharedPublicKeys.size
        val flags = session.changeFlagsCalls.size
        val lookups = session.getContactKeys.size

        vm.clearError()

        assertEquals(discoveries, session.pathDiscoveryDestinations.size)
        assertEquals(resets, session.resetPathPublicKeys.size)
        assertEquals(adds, session.addedContacts.size)
        assertEquals(removes, session.removedPublicKeys.size)
        assertEquals(shares, session.sharedPublicKeys.size)
        assertEquals(flags, session.changeFlagsCalls.size)
        assertEquals(lookups, session.getContactKeys.size)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSettingPath)
    }

    // MARK: bulk codes

    @Test
    fun addCodesAppendsResolvableCode() = runTest {
        val repeater = PathEditFixtures.contact(
            name = "R",
            publicKey = PathEditFixtures.key(0xA1),
            type = ContactType.REPEATER,
            radioID = radio,
            id = PathEditFixtures.uuid(1),
        )
        val vm = viewModel(testContactService(store = savedContactStore(repeater)), hashSize = 1)
        vm.loadContacts(radio)
        val result = vm.addCodes("A1")
        assertEquals(listOf("A1"), result.added)
        assertEquals(1, vm.state.value.editablePath.size)
    }

    @Test
    fun addCodesBucketsInvalidAndNotFound() = runTest {
        val vm = viewModel(testContactService(store = savedContactStore()), hashSize = 1)
        vm.loadContacts(radio)
        val result = vm.addCodes("ZZ, B1")
        assertEquals(listOf("ZZ"), result.invalidFormat)
        assertEquals(listOf("B1"), result.notFound)
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun handleDiscoveryResponseSuccess() = runTest {
        val contact = target()
        var refreshed = false
        val session = FakeContactSession()
        val service = testContactService(session = session, store = savedContactStore(contact))
        val vm = viewModel(service, options = PathOptions(onRefresh = { refreshed = true }))
        startDiscovery(vm, contact)
        vm.handleDiscoveryResponse(2)
        assertEquals(PathDiscoveryResult.Success(2), vm.state.value.discoveryResult)
        assertTrue(vm.state.value.showDiscoveryResult)
        assertFalse(vm.state.value.isDiscovering)
        assertTrue(refreshed)
    }

    @Test
    fun dismissingADiscoveryResultDoesNotRadio() = runTest {
        val contact = target()
        var refreshCount = 0
        val session = FakeContactSession()
        val service = testContactService(session = session, store = savedContactStore(contact))
        val vm = viewModel(service, options = PathOptions(onRefresh = { refreshCount += 1 }))
        startDiscovery(vm, contact)
        vm.handleDiscoveryResponse(2)
        assertEquals(PathDiscoveryResult.Success(2), vm.state.value.discoveryResult)
        assertTrue(vm.state.value.showDiscoveryResult)
        val sendsAfterSuccess = session.pathDiscoveryDestinations.size
        val refreshesAfterSuccess = refreshCount

        vm.dismissDiscoveryResult()

        assertEquals(sendsAfterSuccess, session.pathDiscoveryDestinations.size)
        assertEquals(refreshesAfterSuccess, refreshCount)
        assertFalse(vm.state.value.showDiscoveryResult)
        assertEquals(PathDiscoveryResult.Success(2), vm.state.value.discoveryResult)
        assertFalse(vm.state.value.isDiscovering)
    }

    @Test
    fun handleDiscoveryResponseNullIsNoPathFound() = runTest {
        val contact = target()
        val session = FakeContactSession()
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        startDiscovery(vm, contact)
        vm.handleDiscoveryResponse(null)
        assertEquals(PathDiscoveryResult.NoPathFound, vm.state.value.discoveryResult)
    }

    @Test
    fun handleDiscoveryWriteFailureShowsTheRadioErrorWhileDiscovering() = runTest {
        val contact = target()
        var refreshed = false
        val vm = viewModel(
            testContactService(store = savedContactStore(contact)),
            options = PathOptions(onRefresh = { refreshed = true }, errorText = { "radio rejected" }),
        )
        startDiscovery(vm, contact)
        vm.handleDiscoveryWriteFailure(ContactServiceError.ContactTableFull())
        val result = vm.state.value.discoveryResult
        assertIs<PathDiscoveryResult.Failed>(result)
        assertEquals("radio rejected", result.message)
        assertTrue(vm.state.value.showDiscoveryResult)
        assertFalse(vm.state.value.isDiscovering)
        assertFalse(refreshed)
    }

    @Test
    fun handleDiscoveryWriteFailureLeavesAFinishedAlertAlone() = runTest {
        val contact = target()
        val vm = viewModel(testContactService(store = savedContactStore(contact)))
        startDiscovery(vm, contact)
        vm.handleDiscoveryResponse(2)
        vm.handleDiscoveryWriteFailure(ContactServiceError.SendFailed())
        assertEquals(PathDiscoveryResult.Success(2), vm.state.value.discoveryResult)
    }

    @Test
    fun handleDiscoveryResponseIgnoredWhenNotDiscovering() = runTest {
        var refreshed = false
        val vm = viewModel(
            testContactService(store = savedContactStore()),
            options = PathOptions(onRefresh = { refreshed = true }),
        )
        vm.handleDiscoveryResponse(2)
        assertNull(vm.state.value.discoveryResult)
        assertFalse(vm.state.value.showDiscoveryResult)
        assertFalse(refreshed)
    }

    @Test
    fun discoverPathTimesOutToNoPathFound() = runTest {
        val contact = target()
        val session = FakeContactSession() // suggestedTimeoutMs 5000 -> floored to 20s overall
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        startDiscovery(vm, contact)
        testScheduler.advanceTimeBy(19_000)
        testScheduler.runCurrent()
        assertTrue(vm.state.value.isDiscovering)
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertEquals(PathDiscoveryResult.NoPathFound, vm.state.value.discoveryResult)
        assertFalse(vm.state.value.isDiscovering)
    }

    @Test
    fun lateResponseAfterTimeoutStaysFailed() = runTest {
        val contact = target()
        var refreshed = false
        val session = FakeContactSession()
        val service = testContactService(session = session, store = savedContactStore(contact))
        val vm = viewModel(service, options = PathOptions(onRefresh = { refreshed = true }))
        startDiscovery(vm, contact)
        testScheduler.advanceTimeBy(21_000)
        testScheduler.runCurrent()
        assertEquals(PathDiscoveryResult.NoPathFound, vm.state.value.discoveryResult)

        vm.handleDiscoveryResponse(3)
        assertEquals(PathDiscoveryResult.NoPathFound, vm.state.value.discoveryResult)
        assertTrue(vm.state.value.showDiscoveryResult)
        assertFalse(vm.state.value.isDiscovering)
        assertFalse(refreshed)
    }

    @Test
    fun lateResponseAfterCancelIsIgnored() = runTest {
        val contact = target()
        var refreshed = false
        val session = FakeContactSession()
        val service = testContactService(session = session, store = savedContactStore(contact))
        val vm = viewModel(service, options = PathOptions(onRefresh = { refreshed = true }))
        startDiscovery(vm, contact)
        vm.cancelDiscovery()

        vm.handleDiscoveryResponse(3)
        assertFalse(vm.state.value.isDiscovering)
        assertNull(vm.state.value.discoveryResult)
        assertFalse(vm.state.value.showDiscoveryResult)
        assertFalse(refreshed)
    }

    @Test
    fun closeCancelsAndRetiresPathWork() = runTest {
        val contact = target()
        val session = FakeContactSession()
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        startDiscovery(vm, contact)

        vm.close()
        vm.discoverPath(contact)
        vm.insert(PathEditFixtures.key(0x01), "late")
        runCurrent()

        assertFalse(vm.state.value.isDiscovering)
        assertTrue(vm.state.value.editablePath.isEmpty())
        assertTrue(vm.state.value.recentPublicKeys.isEmpty())
        assertEquals(1, session.pathDiscoveryDestinations.size)
    }

    @Test
    fun matchingContactResponseResolvesToSuccess() = runTest {
        val contact = target()
        val session = FakeContactSession()
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        startDiscovery(vm, contact)
        // outPathLength 0x02 = mode 0 (1 byte/hop), 2 hops.
        val response = pathInfo(prefix = contact.publicKey.copyOfRange(0, 1), outPathLength = 0x02)
        vm.handleDiscoveryResponse(response)
        assertEquals(PathDiscoveryResult.Success(2), vm.state.value.discoveryResult)
        assertFalse(vm.state.value.isDiscovering)
    }

    @Test
    fun mismatchedContactResponseIsIgnored() = runTest {
        val contact = target()
        val session = FakeContactSession()
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        startDiscovery(vm, contact)
        val response = pathInfo(prefix = byteArrayOf(0x11), outPathLength = 0x02)
        vm.handleDiscoveryResponse(response)
        assertNull(vm.state.value.discoveryResult)
        assertTrue(vm.state.value.isDiscovering)
    }

    @Test
    fun emptyPrefixResponseLeavesDiscoveryInFlight() = runTest {
        val contact = target()
        val session = FakeContactSession()
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        startDiscovery(vm, contact)
        // An empty key prefix must never match, or it would resolve every contact's in-flight discovery.
        val response = pathInfo(prefix = ByteArray(0), outPathLength = 0x02)
        vm.handleDiscoveryResponse(response)
        assertNull(vm.state.value.discoveryResult)
        assertTrue(vm.state.value.isDiscovering)
    }

    @Test
    fun resendFiresWhileWaiting() = runTest {
        val contact = target()
        val session = FakeContactSession() // suggestedTimeoutMs 5000 -> resend every 10s within a 20s budget
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        startDiscovery(vm, contact)
        assertEquals(1, session.pathDiscoveryDestinations.size) // initial send
        testScheduler.advanceTimeBy(11_000)
        testScheduler.runCurrent()
        assertTrue(vm.state.value.isDiscovering)
        assertEquals(2, session.pathDiscoveryDestinations.size) // one resend fired
    }

    @Test
    fun discoverPathSendFaultShowsUnmappedFallbackNotDeviceError() = runTest {
        val contact = target()
        val session = FakeContactSession().apply { pathDiscoveryError = 1 }
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        vm.discoverPath(contact)
        testScheduler.runCurrent()
        val result = vm.state.value.discoveryResult
        assertIs<PathDiscoveryResult.Failed>(result)
        assertEquals(LOCALIZED_ERROR, result.message)
        assertTrue(vm.state.value.showDiscoveryResult)
        assertFalse(vm.state.value.isDiscovering)
    }

    @Test
    fun discoverPathSendCancellationLeavesNoFailure() = runTest {
        val contact = target()
        val session = FakeContactSession().apply {
            onSendPathDiscovery = { throw CancellationException("superseded") }
        }
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))

        vm.discoverPath(contact)
        testScheduler.runCurrent()

        assertNull(vm.state.value.discoveryResult)
        assertFalse(vm.state.value.showDiscoveryResult)
        assertFalse(vm.state.value.isDiscovering)
        assertTrue(session.pathDiscoveryDestinations.isEmpty())
    }

    @Test
    fun aFailedResendKeepsDiscoveryWaiting() = runTest {
        val contact = target()
        val session = FakeContactSession()
        var sends = 0
        session.onSendPathDiscovery = {
            sends += 1
            if (sends >= 2) error("resend down")
        }
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        startDiscovery(vm, contact)

        testScheduler.advanceTimeBy(11_000)
        testScheduler.runCurrent()

        assertTrue(vm.state.value.isDiscovering)
        assertNull(vm.state.value.discoveryResult)
        assertEquals(1, session.pathDiscoveryDestinations.size)
    }

    @Test
    fun aFailedResendIsLoggedAndDiscoveryKeepsWaiting() = runTest {
        val contact = target()
        val session = FakeContactSession()
        var sends = 0
        session.onSendPathDiscovery = {
            sends += 1
            if (sends >= 2) error("resend down")
        }
        val warnings = mutableListOf<String>()
        val vm = viewModel(
            testContactService(session = session, store = savedContactStore(contact)),
            options = PathOptions(warning = { warnings += it }),
        )
        startDiscovery(vm, contact)

        testScheduler.advanceTimeBy(11_000)
        testScheduler.runCurrent()

        assertTrue(vm.state.value.isDiscovering)
        assertNull(vm.state.value.discoveryResult)
        assertEquals(listOf(pathDiscoveryResendFailedLine(1, "resend down")), warnings)
    }

    @Test
    fun discoverPathSendFaultShowsMappedCopy() = runTest {
        val contact = target()
        val session = FakeContactSession().apply { pathDiscoveryError = 1 }
        val vm = viewModel(
            testContactService(session = session, store = savedContactStore(contact)),
            options = PathOptions(errorText = { "discover fail" }),
        )
        vm.discoverPath(contact)
        testScheduler.runCurrent()
        val result = vm.state.value.discoveryResult
        assertIs<PathDiscoveryResult.Failed>(result)
        assertEquals("discover fail", result.message)
    }
}

private val radio = PathEditFixtures.uuid(100)

private data class PathOptions(
    val discovered: List<DiscoveredNode> = emptyList(),
    val preferences: InMemoryPreferencesStore = InMemoryPreferencesStore(),
    val onRefresh: () -> Unit = {},
    val errorText: (Throwable) -> String = { LOCALIZED_ERROR },
    val isRadioConnected: Boolean = true,
    val warning: (String) -> Unit = {},
)

private fun TestScope.viewModel(
    service: ContactService?,
    hashSize: Int? = 1,
    options: PathOptions = PathOptions(),
): PathManagementViewModel = PathManagementViewModel(
    scope = backgroundScope,
    recents = RecentHopsStore(options.preferences),
    dependencies = PathManagementViewModel.Dependencies(
        contactService = { service },
        radioId = { radio },
        isRadioConnected = { options.isRadioConnected },
        hashSize = { hashSize },
        discoveredNodes = { options.discovered },
        onContactNeedsRefresh = options.onRefresh,
        errorText = options.errorText,
        warning = options.warning,
    ),
)

private fun target(
    publicKey: ByteArray = PathEditFixtures.key(0xFE),
    type: ContactType = ContactType.CHAT,
    outPathLength: Int = PathEditFixtures.FLOOD_SENTINEL,
    outPath: ByteArray = ByteArray(0),
) = PathEditFixtures.contact(
    name = "Target",
    publicKey = publicKey,
    type = type,
    radioID = radio,
    outPathLength = outPathLength,
    outPath = outPath,
    id = PathEditFixtures.uuid(200),
)

// MARK: discovery

/** Starts a discovery and lets the initial send settle so the view model is in the discovering state. */
private fun TestScope.startDiscovery(vm: PathManagementViewModel, contact: ContactDto) {
    vm.discoverPath(contact)
    testScheduler.runCurrent()
    assertTrue(vm.state.value.isDiscovering)
}

private class PathEditProbeError : Error("probe")

private fun pathInfo(prefix: ByteArray, outPathLength: Int) = PathInfo(
    publicKeyPrefix = prefix,
    outPathLength = outPathLength,
    outPath = ByteArray(0),
    inPathLength = 0,
    inPath = ByteArray(0),
)

private const val LOCALIZED_ERROR = "Localized error"
